package com.screenrec.pro.ui.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.screenrec.pro.core.performance.FrameSnapshot

/**
 * Overlay minimalista: um único TextView, sem layout inflado, sem recomposição
 * Compose a cada frame — o pedido é explícito para não pesar durante jogos.
 * O overlay NUNCA é capturado no arquivo final: ele vive em uma janela do tipo
 * TYPE_APPLICATION_OVERLAY, fora da VirtualDisplay usada para gravação.
 *
 * AUDITORIA (revisão final), item 17: a versão anterior expunha [update] como
 * método público mas o serviço era iniciado só com startService() e
 * onBind() retornava null — não existia NENHUM caminho para MainActivity
 * chamar [update], então o overlay ficava travado para sempre em "Aguardando
 * métricas...". Agora o serviço é bindable via [LocalBinder], permitindo que
 * MainActivity se conecte e encaminhe o StateFlow de métricas real do
 * RecordingService.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null

    private val binder = LocalBinder()
    inner class LocalBinder : android.os.Binder() {
        fun getService(): OverlayService = this@OverlayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 24
        }

        val view = TextView(this).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(16, 12, 16, 12)
            text = "Aguardando métricas..."
        }

        // Item 1 (revisão final): a permissão SYSTEM_ALERT_WINDOW é checada em
        // MainActivity ANTES de startService(), mas isso não é garantia
        // absoluta contra corrida (usuário revoga a permissão nos Ajustes do
        // sistema entre a checagem e este addView, ou entre duas gravações
        // enquanto o serviço já existe). addView() lança
        // WindowManager.BadTokenException/SecurityException nesse caso — sem
        // este try/catch o serviço derrubava o processo. Agora falha de forma
        // silenciosa e auto-encerra, e MainActivity já avisa o usuário
        // antecipadamente com base em Settings.canDrawOverlays().
        try {
            windowManager?.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            android.util.Log.w("OverlayService", "Não foi possível desenhar o overlay (permissão SYSTEM_ALERT_WINDOW ausente ou revogada): ${e.message}")
            overlayView = null
            stopSelf()
        }
    }

    fun update(snapshot: FrameSnapshot) {
        overlayView?.text = buildString {
            append("Alvo: ${snapshot.targetFps} FPS\n")
            // Overlay mostra só a métrica REAL (via PTS do encoder) — a estimativa de
            // captura, não confiável, fica de fora aqui por ser um overlay minimalista.
            append("Encoder: ${"%.1f".format(snapshot.encoderOutputFps)} FPS\n")
            append("Drops: ${snapshot.droppedFrames}  Dup: ${snapshot.duplicatedFrames}\n")
            append("Jitter: ${"%.1f".format(snapshot.jitterMs)} ms\n")
            append("Tempo: ${snapshot.elapsedMs / 1000}s")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { windowManager?.removeView(it) }
    }
}
