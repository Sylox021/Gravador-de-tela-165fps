package com.screenrec.pro.tile

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.screenrec.pro.MainActivity

/**
 * O QS Tile não pode iniciar diretamente uma captura MediaProjection: o consentimento
 * do usuário (createScreenCaptureIntent) exige uma Activity em primeiro plano por
 * requisito de segurança do próprio Android. O tile abre a MainActivity com uma flag
 * para disparar o fluxo de permissão + início imediato.
 */
class RecordingTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_START_FROM_TILE, true)
        }
        // startActivityAndCollapse(Intent) foi descontinuado na API 34 em favor da
        // variante com PendingIntent; mantemos os dois caminhos para cobrir minSdk 29.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.state = Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }
}
