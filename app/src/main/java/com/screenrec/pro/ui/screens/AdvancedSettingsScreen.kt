package com.screenrec.pro.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

            val effectiveBitrate = settings.video.customBitrateBps
                ?: (settings.video.bitratePreset.megabits?.times(1_000_000))
                ?: 50_000_000
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
