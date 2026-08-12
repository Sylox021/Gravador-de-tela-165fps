package com.screenrec.pro

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Size
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.screenrec.pro.core.diagnostics.BenchmarkRow
import com.screenrec.pro.core.diagnostics.BenchmarkRunner
import com.screenrec.pro.core.diagnostics.BenchmarkTestType
import com.screenrec.pro.core.diagnostics.DeviceDiagnostics
import com.screenrec.pro.core.diagnostics.DeviceDiagnosticsReport
import com.screenrec.pro.core.diagnostics.FinalDiagnosticReport
import com.screenrec.pro.service.RecordingService
import com.screenrec.pro.service.RecordingState
import com.screenrec.pro.settings.RecordingSettings
import com.screenrec.pro.ui.overlay.OverlayService
import com.screenrec.pro.ui.screens.AdvancedSettingsScreen
import com.screenrec.pro.ui.screens.DiagnosticsScreen
import com.screenrec.pro.ui.screens.MainScreen
import com.screenrec.pro.ui.theme.ScreenRecorderTheme
import com.screenrec.pro.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import java.io.File

private enum class Screen { MAIN, DIAGNOSTICS, ADVANCED }

class MainActivity : ComponentActivity() {

    private var recordingService: RecordingService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            recordingService = (service as RecordingService.LocalBinder).getService()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            recordingService = null
        }
    }

    // Item 17: liga o overlay ao StateFlow real de métricas — ver nota em
    // OverlayService.
    private var overlayService: OverlayService? = null
    private var overlayBound = false
    private val overlayConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            overlayService = (service as OverlayService.LocalBinder).getService()
            overlayBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            overlayBound = false
            overlayService = null
        }
    }

    private fun startOverlay() {
        // Item 1 (revisão final): SYSTEM_ALERT_WINDOW é uma permissão especial —
        // não é concedida via ActivityResultContracts.RequestPermission comum,
        // precisa do fluxo Settings.ACTION_MANAGE_OVERLAY_PERMISSION. A versão
        // anterior nunca checava isso: em dispositivos onde a permissão não foi
        // concedida, startService() e addView() falhavam silenciosamente (ou
        // derrubavam o serviço) sem o usuário entender por quê. Agora
        // checamos antes de iniciar e damos um retorno claro; a gravação em si
        // NUNCA é bloqueada por isso — o overlay é estritamente opcional.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(
                this,
                "Overlay ao vivo desativado: conceda a permissão \"Exibir sobre outros apps\" para ScreenRecorder Pro nos Ajustes do sistema. A gravação continua normalmente sem o overlay.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val intent = Intent(this, OverlayService::class.java)
        startService(intent)
        bindService(intent, overlayConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopOverlay() {
        if (overlayBound) {
            try { unbindService(overlayConnection) } catch (e: IllegalArgumentException) { /* já desconectado */ }
            overlayBound = false
        }
        overlayService = null
        stopService(Intent(this, OverlayService::class.java))
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingProjectionCallback?.invoke(result.resultCode, result.data)
        pendingProjectionCallback = null
    }
    private var pendingProjectionCallback: ((Int, Intent?) -> Unit)? = null

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val notifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val serviceIntent = Intent(this, RecordingService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            var screen by remember { mutableStateOf(Screen.MAIN) }
            var settings by remember { mutableStateOf(RecordingSettings()) }
            var diagnosticsReport by remember { mutableStateOf<DeviceDiagnosticsReport?>(null) }
            var benchmarkRows by remember { mutableStateOf<List<BenchmarkRow>>(emptyList()) }
            var finalReportText by remember { mutableStateOf<String?>(null) }
            var benchmarkRunning by remember { mutableStateOf(false) }
            var isRecording by remember { mutableStateOf(false) }
            var rejectionMessage by remember { mutableStateOf<String?>(null) }
            var liveMetrics by remember { mutableStateOf<com.screenrec.pro.core.performance.FrameSnapshot?>(null) }
            var liveMuxerStats by remember { mutableStateOf<com.screenrec.pro.core.muxer.MuxerStats?>(null) }
            var lastRecordingInfo by remember { mutableStateOf<RecordingState.Recording?>(null) }
            var lastStopValidation by remember { mutableStateOf<com.screenrec.pro.core.diagnostics.FileFpsReport?>(null) }

            LaunchedEffect(bound) {
                if (bound) {
                    recordingService?.state?.collect { state ->
                        when (state) {
                            is RecordingState.Recording -> { isRecording = true; rejectionMessage = null; lastRecordingInfo = state }
                            is RecordingState.Rejected -> {
                                isRecording = false
                                rejectionMessage = "${state.reason}\nMaior taxa de captura suportada (declarada pelo encoder, ainda não testada): ${"%.1f".format(state.bestFps)} FPS."
                                stopOverlay()
                            }
                            is RecordingState.Stopped -> { isRecording = false; lastStopValidation = state.fileValidation; stopOverlay() }
                            is RecordingState.Idle -> isRecording = false
                            is RecordingState.Error -> { isRecording = false; rejectionMessage = state.message; stopOverlay() }
                        }
                    }
                }
            }

            LaunchedEffect(bound) {
                if (bound) {
                    recordingService?.metrics?.collect { snap ->
                        liveMetrics = snap
                        // Item 17: encaminha o snapshot real de métricas para o
                        // overlay flutuante sempre que ele estiver vinculado —
                        // é o elo que faltava entre o RecordingService e a
                        // janela TYPE_APPLICATION_OVERLAY (ver OverlayService).
                        snap?.let { overlayService?.update(it) }
                    }
                }
            }
            LaunchedEffect(bound) { if (bound) recordingService?.muxerStats?.collect { s -> liveMuxerStats = s } }

            ScreenRecorderTheme(mode = themeMode) {
                when (screen) {
                    Screen.MAIN -> MainScreen(
                        settings = settings,
                        onSettingsChange = { settings = it },
                        isRecording = isRecording,
                        liveMetrics = liveMetrics,
                        liveMuxerStats = liveMuxerStats,
                        recordingInfo = lastRecordingInfo,
                        lastFileValidation = lastStopValidation,
                        rejectionMessage = rejectionMessage,
                        onStartClick = { requestProjectionAndStart(settings) },
                        onStopClick = { recordingService?.stopRecording() },
                        onOpenDiagnostics = {
                            screen = Screen.DIAGNOSTICS
                            diagnosticsReport = DeviceDiagnostics.run(this@MainActivity)
                        },
                        onOpenAdvancedSettings = { screen = Screen.ADVANCED }
                    )
                    Screen.DIAGNOSTICS -> DiagnosticsScreen(
                        report = diagnosticsReport,
                        benchmarkRows = benchmarkRows,
                        finalReportText = finalReportText,
                        benchmarkRunning = benchmarkRunning,
                        onRunBenchmark = { testType ->
                            benchmarkRunning = true
                            benchmarkRows = emptyList() // limpa a tabela da rodada anterior antes de começar
                            requestProjectionForBenchmark { resultCode, data ->
                                if (data == null) { benchmarkRunning = false; return@requestProjectionForBenchmark }
                                lifecycleScope.launch {
                                    // Cada linha aparece na tela assim que fica pronta (ver
                                    // BenchmarkRunner.onRowReady) — antes disso a tela ficava
                                    // parada em "Testando..." por até vários minutos sem
                                    // nenhum retorno visível, parecendo travada.
                                    val rows = runFullBenchmark(resultCode, data, testType) { row ->
                                        benchmarkRows = benchmarkRows + row
                                    }
                                    finalReportText = FinalDiagnosticReport.build(
                                        deviceName = Build.MODEL,
                                        displayHz = nativeDisplayInfo().second.toInt(),
                                        rows = rows
                                    )
                                    benchmarkRunning = false
                                }
                            }
                        },
                        onBack = { screen = Screen.MAIN }
                    )
                    Screen.ADVANCED -> AdvancedSettingsScreen(
                        settings = settings,
                        onSettingsChange = { settings = it },
                        onBack = { screen = Screen.MAIN }
                    )
                }
            }
        }
    }

    private fun requestProjectionAndStart(settings: RecordingSettings) {
        if (settings.audio.source != com.screenrec.pro.settings.AudioSource.NENHUM &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        val (resolution, refreshRate) = nativeDisplayInfo()
        pendingProjectionCallback = { resultCode, data ->
            if (data != null) {
                val outputDir = getExternalFilesDir(null) ?: filesDir
                val outputPath = File(outputDir, recordingService?.defaultOutputFileName() ?: "recording.mp4").absolutePath
                recordingService?.startRecording(resultCode, data, settings, resolution, refreshRate, outputPath)
                if (settings.showLiveOverlay) {
                    startOverlay()
                }
            }
        }
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun requestProjectionForBenchmark(callback: (Int, Intent?) -> Unit) {
        pendingProjectionCallback = callback
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private suspend fun runFullBenchmark(
        resultCode: Int,
        data: Intent,
        testType: BenchmarkTestType,
        onRowReady: (BenchmarkRow) -> Unit = {}
    ): List<BenchmarkRow> {
        // Ver RecordingService.ensureForeground(): o benchmark pede seu próprio
        // MediaProjection fora do fluxo de startRecording(), então precisa da
        // mesma garantia de foreground service ativa (exigida a partir do
        // Android 14 para qualquer uso de MediaProjection).
        recordingService?.ensureForeground()
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        val (resolution, refreshRate) = nativeDisplayInfo()
        val runner = BenchmarkRunner(
            mediaProjection = projection,
            nativeResolution = resolution,
            nativeRefreshRateHz = refreshRate,
            tempDir = cacheDir.absolutePath
        )
        val rows = runner.runFullMatrix(testType, onRowReady)
        projection.stop()
        return rows
    }

    private fun nativeDisplayInfo(): Pair<Size, Float> {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return Size(metrics.widthPixels, metrics.heightPixels) to display.refreshRate
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) { unbindService(connection); bound = false }
        if (overlayBound) {
            try { unbindService(overlayConnection) } catch (e: IllegalArgumentException) { /* já desconectado */ }
            overlayBound = false
        }
    }

    companion object {
        const val EXTRA_START_FROM_TILE = "start_from_tile"
    }
}
