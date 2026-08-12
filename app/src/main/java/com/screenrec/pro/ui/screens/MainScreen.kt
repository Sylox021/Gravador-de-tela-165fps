package com.screenrec.pro.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.screenrec.pro.core.diagnostics.FileFpsReport
import com.screenrec.pro.core.muxer.MuxerStats
import com.screenrec.pro.core.performance.FrameSnapshot
import com.screenrec.pro.service.RecordingState
import com.screenrec.pro.settings.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: RecordingSettings,
    onSettingsChange: (RecordingSettings) -> Unit,
    isRecording: Boolean,
    liveMetrics: FrameSnapshot?,
    liveMuxerStats: MuxerStats?,
    recordingInfo: RecordingState.Recording?,
    lastFileValidation: FileFpsReport?,
    rejectionMessage: String?,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAdvancedSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ScreenRecorder Pro") },
                actions = {
                    TextButton(onClick = onOpenDiagnostics) { Text("Diagnóstico") }
                    TextButton(onClick = onOpenAdvancedSettings) { Text("Avançado") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (rejectionMessage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(rejectionMessage, modifier = Modifier.padding(12.dp))
                }
            }

            if (!isRecording && lastFileValidation != null) {
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (lastFileValidation.confirmed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                )) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Última gravação — validação do arquivo final", fontWeight = FontWeight.Bold)
                        Text(lastFileValidation.verdict)
                        Text("${lastFileValidation.frameCount} frames em ${lastFileValidation.durationUs / 1_000_000}s")
                    }
                }
            }

            if (isRecording && liveMetrics != null) {
                LiveMetricsCard(liveMetrics, liveMuxerStats, recordingInfo)
            }

            ConfigDropdown(
                label = "FPS",
                selected = settings.video.frameRate.label,
                options = FrameRateTarget.values().map { it.label },
                onSelect = { label ->
                    val target = FrameRateTarget.values().first { it.label == label }
                    onSettingsChange(settings.copy(video = settings.video.copy(frameRate = target)))
                }
            )

            ConfigDropdown(
                label = "Codec",
                selected = settings.video.codec.label,
                options = VideoCodecType.values().map { it.label },
                onSelect = { label ->
                    val codec = VideoCodecType.values().first { it.label == label }
                    onSettingsChange(settings.copy(video = settings.video.copy(codec = codec)))
                }
            )

            ConfigDropdown(
                label = "Bitrate",
                selected = settings.video.bitratePreset.label,
                options = BitratePreset.values().map { it.label },
                onSelect = { label ->
                    val preset = BitratePreset.values().first { it.label == label }
                    onSettingsChange(settings.copy(video = settings.video.copy(bitratePreset = preset)))
                }
            )

            ConfigDropdown(
                label = "Áudio",
                selected = settings.audio.source.name,
                options = AudioSource.values().map { it.name },
                onSelect = { name ->
                    val source = AudioSource.values().first { it.name == name }
                    onSettingsChange(settings.copy(audio = settings.audio.copy(source = source)))
                }
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("HDR automático (só habilita se toda a cadeia confirmar)", fontWeight = FontWeight.Medium)
                Switch(
                    checked = settings.video.hdrMode == HdrMode.AUTO,
                    onCheckedChange = { checked ->
                        val mode = if (checked) HdrMode.AUTO else HdrMode.FORCE_SDR
                        onSettingsChange(settings.copy(video = settings.video.copy(hdrMode = mode)))
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = if (isRecording) onStopClick else onStartClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = if (isRecording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
            ) {
                Icon(if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isRecording) "PARAR" else "GRAVAR", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun LiveMetricsCard(snapshot: FrameSnapshot, muxerStats: MuxerStats?, recordingInfo: RecordingState.Recording?) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            recordingInfo?.let {
                Text("Encoder: ${it.encoderName}", style = MaterialTheme.typography.labelMedium)
                if (it.hdrEnabled) Text("HDR habilitado — ${it.hdrNote}", style = MaterialTheme.typography.labelSmall)
            }
            Text("Alvo: ${snapshot.targetFps} FPS", fontWeight = FontWeight.Bold)
            Text("Encoder (real, via PTS): ${"%.1f".format(snapshot.encoderOutputFps)} FPS")
            Text("Throughput do encoder: ${"%.1f".format(snapshot.encoderThroughputFps)} FPS")
            Text(
                "Captura: estimativa não confiável (${"%.1f".format(snapshot.captureFpsEstimate)} FPS) — " +
                    "Android não expõe callback público por frame do VirtualDisplay",
                style = MaterialTheme.typography.labelSmall
            )
            Text("Drops: ${snapshot.droppedFrames}  Duplicados: ${snapshot.duplicatedFrames}  Jitter: ${"%.1f".format(snapshot.jitterMs)}ms")
            muxerStats?.let {
                Text("Fila do muxer: ${it.queuedSamples} amostras / ${it.queuedBytes / 1_048_576}MB")
                Text("Throughput de escrita: ${"%.1f".format(it.throughputBytesPerSec / 1_048_576)} MB/s")
                if (it.samplesDroppedByOverflow > 0) {
                    Text("⚠ ${it.samplesDroppedByOverflow} amostras descartadas (armazenamento não acompanha o bitrate)", color = MaterialTheme.colorScheme.error)
                }
            }
            Text("Tempo: ${snapshot.elapsedMs / 1000}s")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigDropdown(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    onSelect(option)
                    expanded = false
                })
            }
        }
    }
}
