# ScreenRecorder Pro

App Android nativo de gravação de tela, sem root, focado em captura em alta taxa
de atualização (até 165 Hz) com medição real de FPS. Construído com Kotlin +
Jetpack Compose + APIs públicas do `android.media.*`.

## Como abrir e compilar

1. Abra a pasta `ScreenRecorderPro/` no Android Studio (Koala ou mais recente).
2. Deixe o Gradle sincronizar (baixa as dependências listadas em `app/build.gradle.kts`).
3. Rode em um dispositivo físico — **MediaProjection e MediaCodec não funcionam
   de forma representativa em emulador** para fins de FPS real; o benchmark e o
   diagnóstico dependem do encoder de hardware do SoC real.
4. `minSdk 29` (exigido pela captura de áudio interno via `AudioPlaybackCaptureConfiguration`).

Faltam apenas os ícones `mipmap` de launcher (o manifest usa um vetor simples em
`res/drawable/ic_tile_record.xml` como placeholder) — substitua pelo ícone final
via Image Asset Studio do Android Studio.

## Mapeamento de arquivos

```
app/src/main/java/com/screenrec/pro/
├── MainActivity.kt                  # navegação, permissão MediaProjection, bind do service
├── ScreenRecorderApp.kt             # Application
├── core/capture/ScreenCaptureManager.kt   # MediaProjection -> VirtualDisplay -> Surface
├── core/encoder/CodecCapabilityScanner.kt # introspecção real via MediaCodecList
├── core/encoder/RecordingConfigResolver.kt# valida/ajusta FPS+bitrate+resolução antes de gravar
├── core/encoder/VideoEncoder.kt           # MediaCodec async, Surface input, HDR opcional
├── core/audio/AudioCaptureManager.kt      # áudio interno + microfone + encoder AAC
├── core/muxer/MuxerManager.kt             # MediaMuxer com writer thread + fila
├── core/performance/FrameMetrics.kt       # target/capture/encoder/output FPS, drops, jitter
├── core/diagnostics/DeviceDiagnostics.kt  # relatório de capacidades do aparelho
├── core/diagnostics/BenchmarkRunner.kt    # teste real de captura por FPS alvo
├── service/RecordingService.kt            # foreground service, orquestra tudo
├── ui/overlay/OverlayService.kt           # overlay leve de métricas (não gravado por padrão)
├── ui/screens/*.kt                        # telas Compose (principal, diagnóstico, avançado)
├── ui/theme/Theme.kt                      # Material 3, Dark/Light/System
├── storage/StorageEstimator.kt            # estimativa de tamanho e alerta de espaço
├── settings/RecordingSettings.kt          # data classes de configuração
└── tile/RecordingTileService.kt           # Quick Settings Tile

app/src/androidTest/.../CodecCompatibilityMatrixTest.kt  # matriz real de compatibilidade
app/src/test/.../RecordingConfigResolverTest.kt           # teste unitário (parte da lógica pura)
```

## Limitações técnicas reais (documentadas, não contornadas por hack)

1. **165 FPS depende do jogo, não só do gravador.** `VirtualDisplay` reflete o
   que o `SurfaceFlinger` já compôs. Se o app-alvo renderiza a 90fps, a captura
   nunca ultrapassa isso — o app mostra isso na métrica de **Captura**, distinta
   de **Encoder**.
2. **`getSupportedFrameRatesFor()` é uma declaração do fabricante do codec**, não
   uma garantia absoluta de estabilidade sustentada — por isso existe o
   `BenchmarkRunner`, que testa de verdade por alguns segundos antes de confiar
   no número.
3. **AV1 por hardware é raro** em 2026 mesmo em tablets recentes; o app detecta
   e informa quando só há encoder por software (ou nenhum), sem fingir suporte.
4. **HDR em `MediaProjection`** depende do Android expor o conteúdo HDR da
   composição do sistema — isso varia por versão do Android e por OEM; quando
   `Display.getHdrCapabilities()` não reporta suporte, o app grava em SDR e avisa,
   em vez de tentar forçar metadados HDR falsos.
5. **HyperOS/MIUI pode aplicar throttling térmico e limitação de FPS em
   segundo plano** que nenhuma API pública permite desativar — o app não tem
   como contornar isso e não deveria tentar (implicaria root/APIs privadas).
6. **`getRealMetrics`/`getRealSize`** usados para resolução nativa estão
   deprecados desde a API 31 em favor de `WindowMetrics`; mantidos aqui por
   compatibilidade com `minSdk 29` — trocar por `WindowMetricsCalculator` do
   Jetpack Window se o `minSdk` subir para 30+.

## Auditoria (revisão 2) — o que mudou e por quê

A revisão anterior tinha um problema central: `FrameMetricsEngine` reportava um
"FPS de captura" que na prática nunca era alimentado por nenhum callback real do
`VirtualDisplay` — não existe essa fonte nas APIs públicas do Android. Isso foi
corrigido removendo a métrica falsa e deixando explícito na UI que ela não é
confiável. Mudanças, arquivo por arquivo:

- **`FrameMetrics.kt`**: reescrito. `encoderOutputFps` agora vem do
  `presentationTimeUs` real dos buffers de saída do `MediaCodec` (não de
  wall-clock). `captureFpsEstimate` continua existindo como proxy indireto, mas
  com `captureFpsIsReliable = false` e documentação de por que não é confiável.
- **`FileFpsValidator.kt`** (novo): reabre o `.mp4` já gravado com
  `MediaExtractor` e calcula FPS médio real a partir da contagem de amostras e
  duração do arquivo. É a única fonte usada para dizer "confirmado"/"não
  confirmado".
- **`EncoderSelector.kt`** (novo): pontua todos os encoders disponíveis por
  hardware, folga de FPS e de bitrate acima do pedido — não usa mais
  `.firstOrNull()`.
- **`HdrChainValidator.kt`** (novo): só habilita metadados HDR quando
  `Display.getHdrCapabilities()` E o encoder confirmam — com o aviso explícito
  de que não há API pública para confirmar que o conteúdo composto é
  efetivamente HDR.
- **`RecordingConfigResolver.kt`**: agora varre uma matriz resolução×FPS
  (`discoverBestOperatingPoint`) em vez de testar só a resolução pedida.
- **`MuxerManager.kt`**: fila agora tem orçamento de bytes fixo (96MB padrão);
  acima disso descarta a amostra de vídeo mais antiga e conta o descarte —
  nunca deixa a RAM crescer sem limite. Expõe `MuxerStats` (fila, throughput de
  escrita, descartes) via `StateFlow`.
- **`AudioCaptureManager.kt`**: leitura de cada fonte em thread própria (antes
  era sequencial, uma atrasava a outra); PTS agora vem da contagem de amostras
  processadas dividida pela sample rate, não de `SystemClock` no momento do
  mix — isso é o que evita drift progressivo em gravações longas.
- **`StabilityCriteria.kt`** (novo): critério único, documentado e configurável
  de PASS/FAIL, usado tanto pelo benchmark quanto (implicitamente) pela
  gravação real.
- **`BenchmarkRunner.kt`**: reescrito para gravar um arquivo `.mp4` temporário
  de verdade (não descarta mais o vídeo), validar com `FileFpsValidator`, e
  suportar Quick/Stability/Stress (8s/30s/60s). Só testa bitrate/resolução
  alternativos quando a config "cheia" falha.
- **`RecordingService.kt`**: conecta tudo acima; ao parar a gravação, roda
  `FileFpsValidator` no arquivo real e expõe o veredito em
  `RecordingState.Stopped`.

### Limitação nova, honestamente documentada

O `OverlayService.update(snapshot)` existe mas ainda não está conectado a
nenhum `Flow` — o overlay abre e mostra "Aguardando métricas..." sem nunca
atualizar. Faltou tempo para o bind bidirecional MainActivity↔OverlayService
nesta revisão; não implementei um `update()` fake só para "parecer" que
funciona. Próximo passo: expor o `RecordingService.metrics` para o
`OverlayService` (via bind próprio ou broadcast local) e chamar `update()` a
cada snapshot.

## O que ainda precisa de trabalho manual antes de rodar em produção

- Ícones de launcher (`mipmap-*`) reais.
- Tratamento de rotação de tela durante gravação ativa (o `resize()` da
  `VirtualDisplay` existe em `ScreenCaptureManager`, mas o `RecordingService`
  ainda não está conectado a um listener de mudança de configuração).
- Persistência de `RecordingSettings` via DataStore (a dependência já está no
  Gradle; falta o `DataStore<Preferences>` concreto — hoje as configurações
  vivem só em memória do Compose `remember`).
- Container MKV como fallback ainda não implementado — hoje só MP4 via
  `MediaMuxer` (fora do escopo do `MediaMuxer` público suportar MKV nativamente).
