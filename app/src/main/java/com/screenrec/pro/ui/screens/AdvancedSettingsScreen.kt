package com.screenrec.pro.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.screenrec.pro.settings.RecordingSettings
import com.screenrec.pro.storage.StorageEstimator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    settings: RecordingSettings,
    onSettingsChange: (RecordingSettings) -> Unit,
    onBack: () -> Unit
) {
    var customBitrateText by remember {
        mutableStateOf((settings.video.customBitrateBps?.div(1_000_000) ?: "").toString())
    }
    val context = LocalContext.current
    // O sistema não avisa o Compose quando a permissão muda nos Ajustes — esse
    // contador força reavaliar Settings.canDrawOverlays() quando o usuário
    // toca em "Verificar novamente" ao voltar da tela de permissão.
    var overlayCheckTrigger by remember { mutableIntStateOf(0) }
    val overlayGranted = remember(overlayCheckTrigger) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Configurações avançadas") }, navigationIcon = {
                TextButton(onClick = onBack) { Text("Voltar") }
            })
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            OutlinedTextField(
                value = customBitrateText,
                onValueChange = { text ->
                    customBitrateText = text
                    val mbps = text.toIntOrNull()
                    onSettingsChange(settings.copy(video = settings.video.copy(customBitrateBps = mbps?.times(1_000_000))))
                },
                label = { Text("Bitrate manual (Mbps) — vazio usa o preset") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Gravar overlay no arquivo final")
                Switch(
                    checked = settings.video.recordOverlayIntoFile,
                    onCheckedChange = { onSettingsChange(settings.copy(video = settings.video.copy(recordOverlayIntoFile = it))) }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Mostrar overlay ao vivo durante gravação")
                Switch(
                    checked = settings.showLiveOverlay,
                    onCheckedChange = { onSettingsChange(settings.copy(showLiveOverlay = it)) }
                )
            }

            // Botão direto para a permissão "Exibir sobre outros apps" — sem ele,
            // o usuário tinha que achar essa tela sozinho nos ajustes do sistema
            // (em MIUI/HyperOS ela fica escondida em "Outras permissões"). A
            // gravação em si nunca depende dessa permissão — só o overlay ao vivo.
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (overlayGranted) "Permissão \"Exibir sobre outros apps\": concedida"
                        else "Permissão \"Exibir sobre outros apps\": não concedida",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Necessária apenas para o overlay de métricas ao vivo. A gravação funciona normalmente sem ela.",
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (!overlayGranted) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }) {
                                Text("Abrir configuração de permissão")
                            }
                            OutlinedButton(onClick = { overlayCheckTrigger++ }) {
                                Text("Verificar novamente")
                            }
                        }
                    }
                }
            }

            val effectiveBitrate = settings.video.customBitrateBps
                ?: (settings.video.bitratePreset.megabits * 1_000_000)
            val estimate = StorageEstimator.estimate(effectiveBitrate, settings.audio.bitrateBps)

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Estimativa de armazenamento (bitrate atual)", style = MaterialTheme.typography.titleSmall)
                    Text("Por minuto: ${StorageEstimator.formatBytes(estimate.perMinuteBytes)}")
                    Text("5 min: ${StorageEstimator.formatBytes(estimate.per5MinBytes)}")
                    Text("10 min: ${StorageEstimator.formatBytes(estimate.per10MinBytes)}")
                    Text("30 min: ${StorageEstimator.formatBytes(estimate.per30MinBytes)}")
                    Text("1 hora: ${StorageEstimator.formatBytes(estimate.per1HourBytes)}")
                }
            }
        }
    }
}
