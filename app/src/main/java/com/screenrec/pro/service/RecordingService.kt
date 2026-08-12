package com.screenrec.pro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Size
import androidx.core.app.NotificationCompat
import com.screenrec.pro.MainActivity
import com.screenrec.pro.R
import com.screenrec.pro.core.audio.AudioCaptureManager
import com.screenrec.pro.core.capture.ScreenCaptureManager
import com.screenrec.pro.core.diagnostics.FileFpsReport
import com.screenrec.pro.core.diagnostics.FileFpsValidator
import com.screenrec.pro.core.encoder.CodecCapabilityScanner
import com.screenrec.pro.core.encoder.HdrChainValidator
import com.screenrec.pro.core.encoder.RecordingConfigResolver
import com.screenrec.pro.core.encoder.ConfigResolution
import com.screenrec.pro.core.encoder.VideoEncoder
import com.screenrec.pro.core.muxer.MuxerManager
import com.screenrec.pro.core.muxer.MuxerStats
import com.screenrec.pro.core.performance.FrameMetricsEngine
import com.screenrec.pro.core.performance.FrameSnapshot
import com.screenrec.pro.settings.AudioSource
import com.screenrec.pro.settings.HdrMode
import com.screenrec.pro.settings.RecordingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

sealed class RecordingState {
    object Idle : RecordingState()
    data class Rejected(val reason: String, val bestFps: Double) : RecordingState()
    data class Recording(val outputPath: String, val requestedFps: Int, val encoderName: String, val hdrEnabled: Boolean, val hdrNote: String) : RecordingState()
    data class Stopped(val outputPath: String, val fileValidation: FileFpsReport?) : RecordingState()
    data class Error(val message: String) : RecordingState()
}

/**
 * AUDITORIA (revisão 2): agora o serviço
 *  - seleciona o encoder via EncoderSelector (dentro de RecordingConfigResolver),
 *    não mais "o primeiro" (item 12);
 *  - valida a cadeia HDR completa antes de habilitar metadados HDR (item 11);
 *  - expõe MuxerStats (backlog/throughput) além das métricas de frame (item 9);
 *  - ao parar, roda FileFpsValidator no arquivo real gravado e expõe o
 *    resultado via RecordingState.Stopped — a confirmação definitiva de FPS
 *    real pedida no item 5, feita sobre o arquivo que o usuário vai assistir,
 *    não sobre suposições do pipeline.
 */
class RecordingService : Service() {

    private val binder = LocalBinder()
    inner class LocalBinder : android.os.Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state

    private val _metrics = MutableStateFlow<FrameSnapshot?>(null)
    val metrics: StateFlow<FrameSnapshot?> = _metrics

    private val _muxerStats = MutableStateFlow<MuxerStats?>(null)
    val muxerStats: StateFlow<MuxerStats?> = _muxerStats

    private var mediaProjection: MediaProjection? = null
    private var captureManager: ScreenCaptureManager? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var muxerManager: MuxerManager? = null
    private var metricsEngine: FrameMetricsEngine? = null
    private var currentOutputPath: String? = null
    private var currentTargetFps: Int = 0
    private var encoderHadError = false
    // Item 5/6: trava de reentrância. ScreenCaptureManager já evita disparar
    // onProjectionStopped no stop() iniciado por nós, mas essa flag é uma
    // segunda linha de defesa contra QUALQUER caminho que chame
    // stopRecording() duas vezes (ex: usuário toca "Parar" e, no mesmo
    // instante, o sistema revoga a projeção) — sem ela, a segunda chamada
    // executaria stop()/release() duplicado no encoder, no muxer e no
    // MediaProjection já liberados.
    private val stopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Sem isto, os coletores de metrics/muxerStats ficavam vivos após o
        // serviço morrer, vazando a CoroutineScope.
        scope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Pronto"), foregroundServiceType())
        return START_NOT_STICKY
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0

    /**
     * Item 5/12 (revisão final): reafirma o estado de foreground service do tipo
     * mediaProjection sem iniciar gravação nenhuma. Necessário porque
     * BenchmarkRunner (chamado por MainActivity.runFullBenchmark) pede seu
     * próprio MediaProjection diretamente, fora do fluxo de startRecording() —
     * se uma gravação já tiver rodado e sido parada antes (o que chama
     * stopForeground()), o benchmark quebraria da mesma forma que uma segunda
     * gravação quebraria sem essa reafirmação. Idempotente: chamar de novo com
     * o serviço já em foreground não tem efeito colateral.
     */
    fun ensureForeground() {
        startForeground(NOTIFICATION_ID, buildNotification("Pronto"), foregroundServiceType())
    }

    fun startRecording(
        resultCode: Int,
        resultData: Intent,
        settings: RecordingSettings,
        nativeResolution: Size,
        nativeRefreshRateHz: Float,
        outputPath: String
    ) {
        val resolution = RecordingConfigResolver.resolve(settings.video, nativeResolution, nativeRefreshRateHz)
        if (resolution is ConfigResolution.Rejected) {
            _state.value = RecordingState.Rejected(resolution.reason, resolution.bestAvailableFps)
            return
        }
        val resolved = resolution as ConfigResolution.Success
        currentTargetFps = resolved.fps
        encoderHadError = false
        stopping.set(false)

        // Item 5 (revisão final): stopRecording() sempre chama
        // stopForeground(STOP_FOREGROUND_REMOVE) ao final de CADA gravação —
        // então numa segunda gravação (mesma instância de serviço, já que o
        // binding do MainActivity mantém o processo vivo) o serviço já NÃO é
        // mais foreground quando chegamos aqui. A partir do Android 14 (API 34),
        // MediaProjection.createVirtualDisplay() exige que o app tenha uma
        // foreground service ATIVA do tipo mediaProjection NO MOMENTO da
        // chamada — sem isso, o sistema lança SecurityException. A versão
        // anterior só chamava startForeground() uma vez em onStartCommand()
        // (disparado apenas na primeira inicialização do serviço), então a
        // segunda gravação da sessão quebrava nesse tipo de dispositivo.
        // Reafirmar aqui garante que toda gravação — a primeira ou a décima —
        // comece com o serviço corretamente em foreground.
        startForeground(NOTIFICATION_ID, buildNotification("Pronto"), foregroundServiceType())

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection

        val metricsEng = FrameMetricsEngine(resolved.fps)
        metricsEngine = metricsEng

        val expectsAudio = settings.audio.source != AudioSource.NENHUM
        val muxer = MuxerManager(outputPath, expectsAudio)
        muxerManager = muxer
        currentOutputPath = outputPath

        // Item 11: HDR só é habilitado se a cadeia inteira (tela + encoder) confirma —
        // nunca só porque o preset do usuário pede HDR.
        val hdrResult = if (settings.video.hdrMode == HdrMode.AUTO) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val codecInfo = CodecCapabilityScanner.findEncodersFor(settings.video.codec.mimeType)
                .firstOrNull { it.name == resolved.encoderName }
            val encInfo = codecInfo?.let {
                CodecCapabilityScanner.describeEncoder(it, settings.video.codec.mimeType, resolved.width, resolved.height)
            }
            HdrChainValidator.evaluate(displayManager, encInfo)
        } else {
            com.screenrec.pro.core.encoder.HdrChainResult(false, "HDR desativado nas configurações")
        }

        val encoder = VideoEncoder(
            settings = settings.video.copy(widthPx = resolved.width, heightPx = resolved.height),
            encoderName = resolved.encoderName,
            resolvedWidth = resolved.width,
            resolvedHeight = resolved.height,
            resolvedBitrateBps = resolved.bitrateBps,
            enableHdr = hdrResult.enabled,
            metrics = metricsEng,
            onEncodedSample = { buffer, info -> muxer.writeVideoSample(buffer, info) },
            onFormatChanged = { format -> muxer.addVideoTrack(format) },
            onError = { e -> encoderHadError = true; _state.value = RecordingState.Error("Erro no encoder de vídeo: ${e.message}") }
        )
        videoEncoder = encoder

        val surface = try {
            encoder.configureAndStart()
        } catch (e: Exception) {
            _state.value = RecordingState.Error(e.message ?: "Falha ao configurar encoder")
            return
        }

        val capture = ScreenCaptureManager(projection) {
            stopRecording()
        }
        captureManager = capture
        capture.start(surface, resolved.width, resolved.height, 320)

        if (expectsAudio) {
            val audio = AudioCaptureManager(
                settings = settings.audio,
                mediaProjection = projection,
                onEncodedSample = { buffer, info -> muxer.writeAudioSample(buffer, info) },
                onFormatChanged = { format: MediaFormat -> muxer.addAudioTrack(format) },
                onError = { e -> _state.value = RecordingState.Error("Erro no áudio: ${e.message}") }
            )
            audioCaptureManager = audio
            audio.start()
        }

        metricsEng.start()
        scope.launch { metricsEng.snapshot.collect { snap -> _metrics.value = snap } }
        scope.launch { muxer.stats.collect { s -> _muxerStats.value = s } }

        _state.value = RecordingState.Recording(outputPath, resolved.fps, resolved.encoderName, hdrResult.enabled, hdrResult.reason)
        updateNotification("Gravando @ ${resolved.fps} FPS (${resolved.encoderName})")

        if (resolution.warnings.isNotEmpty()) {
            android.util.Log.w("RecordingService", "Avisos de configuração: ${resolution.warnings.joinToString(" | ")}")
        }
    }

    fun stopRecording() {
        if (!stopping.compareAndSet(false, true)) return

        val path = currentOutputPath
        val targetFps = currentTargetFps

        videoEncoder?.stop()
        audioCaptureManager?.stop()
        captureManager?.stop()
        muxerManager?.stop()

        videoEncoder = null
        audioCaptureManager = null
        captureManager = null
        muxerManager = null
        metricsEngine = null

        // Item 5: prova definitiva no arquivo já fechado, não no que o pipeline
        // "deveria" ter produzido.
        val validation = if (path != null && targetFps > 0) {
            try {
                FileFpsValidator.validate(path, targetFps)
            } catch (e: Exception) {
                null
            }
        } else null

        _state.value = if (path != null) RecordingState.Stopped(path, validation) else RecordingState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Gravação de tela", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenRecorder Pro")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tile_record)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    fun defaultOutputFileName(extension: String = "mp4"): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "ScreenRec_$timestamp.$extension"
    }

    companion object {
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1001
    }
}
