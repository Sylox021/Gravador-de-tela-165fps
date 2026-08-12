package com.screenrec.pro.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.screenrec.pro.core.diagnostics.BenchmarkRow
import com.screenrec.pro.core.diagnostics.BenchmarkTestType
import com.screenrec.pro.core.diagnostics.DeviceDiagnosticsReport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    report: DeviceDiagnosticsReport?,
    benchmarkRows: List<BenchmarkRow>,
    finalReportText: String?,
    benchmarkRunning: Boolean,
    onRunBenchmark: (BenchmarkTestType) -> Unit,
    onBack: () -> Unit
) {
    var selectedTestType by remember { mutableStateOf(BenchmarkTestType.QUICK) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Diagnóstico do dispositivo") }, navigationIcon = {
                TextButton(onClick = onBack) { Text("Voltar") }
            })
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (report == null) {
                    Text("Carregando capacidades do dispositivo...")
                } else {
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("Tela", fontWeight = FontWeight.Bold)
                            Text("${report.display.widthPx} x ${report.display.heightPx} @ ${report.display.currentRefreshRate.toInt()} Hz")
                            Text("Taxas suportadas: ${report.display.supportedRefreshRates.joinToString { "${it.toInt()}Hz" }}")
                            Text("HDR: ${if (report.display.hdrCapable) "Tela suporta" else "Tela não suporta"}")
                            Text("Android ${report.androidVersion} — SoC: ${report.soc}")
                        }
                    }
                }
            }

            report?.codecReports?.let { codecs ->
                items(codecs) { codecReport ->
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text(codecReport.codecType.label, fontWeight = FontWeight.Bold)
                            if (!codecReport.available) {
                                Text("Não disponível neste dispositivo")
                            } else {
                                codecReport.encoders.forEach { enc ->
                                    Text("• ${enc.codecName} — HW: ${enc.isHardwareAccelerated}")
                                    Text("  FPS máx DECLARADO nesta resolução: ${enc.supportedFrameRatesAtResolution?.upper ?: "desconhecido"} (indicação inicial, não confirmado)")
                                    Text("  Bitrate: ${enc.supportedBitrateRange.lower / 1_000_000}-${enc.supportedBitrateRange.upper / 1_000_000} Mbps")
                                    Text("  HDR: ${if (enc.hdrProfilesSupported.isNotEmpty()) "Profile disponível" else "Não"}")
                                }
                                codecReport.maxResolution?.let { Text("Resolução máx declarada: ${it.width}x${it.height}") }
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Duração do teste real de captura", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BenchmarkTestType.values().forEach { type ->
                            FilterChip(
                                selected = selectedTestType == type,
                                onClick = { selectedTestType = type },
                                label = { Text("${type.label} (${type.durationMs / 1000}s)") }
                            )
                        }
                    }
                    Button(onClick = { onRunBenchmark(selectedTestType) }, enabled = !benchmarkRunning, modifier = Modifier.fillMaxWidth()) {
                        Text(if (benchmarkRunning) "Testando pipeline real..." else "Rodar teste real (165 FPS por codec + alternativas)")
                    }
                    Text(
                        "O teste grava um arquivo .mp4 temporário de verdade e o analisa depois de fechado — " +
                            "não é uma estimativa teórica.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            finalReportText?.let { text ->
                item {
                    Card {
                        Text(
                            text,
                            modifier = Modifier.padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (benchmarkRows.isNotEmpty()) {
                item { Text("Tabela completa", fontWeight = FontWeight.Bold) }
            }

            items(benchmarkRows) { row ->
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (row.stable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${row.codec.label} — ${row.width}x${row.height} — alvo ${row.targetFps} FPS — ${if (row.stable) "ESTÁVEL" else "INSTÁVEL"}",
                            fontWeight = FontWeight.Bold
                        )
                        Text("Encoder: ${row.encoderName}")
                        Text("Encoder (PTS real): ${"%.1f".format(row.encoderOutputFpsLive)} FPS   Throughput: ${"%.1f".format(row.encoderThroughputFps)} FPS")
                        row.fileValidation?.let { Text("Arquivo final: ${it.verdict}") }
                        Text("Drops: ${row.droppedFrames}   Jitter: ${"%.1f".format(row.jitterMs)}ms   Bitrate: ${row.bitrateBps / 1_000_000}Mbps")
                        if (row.failureReasons.isNotEmpty()) {
                            row.failureReasons.forEach { reason -> Text("• $reason", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }
}
