package com.screenrec.pro.core.encoder

import android.hardware.display.DisplayManager
import android.view.Display

data class HdrChainResult(val enabled: Boolean, val reason: String)

/**
 * Item 11 da auditoria: a versão anterior habilitava metadados HDR10 só porque
 * `CodecCapabilityScanner` reportava um perfil HDR no encoder. Isso ignora que:
 *
 *  1. o Display precisa relatar suporte a HDR (Display.getHdrCapabilities());
 *  2. não existe API pública para confirmar que o MediaProjection está de fato
 *     entregando conteúdo HDR na composição (o Android não expõe isso) — então
 *     na dúvida, o app NÃO afirma que está gravando HDR de verdade, apenas que
 *     configurou o pipeline para *permitir* HDR caso o conteúdo composto seja HDR;
 *  3. o encoder precisa aceitar tanto o perfil quanto a resolução/bitrate juntos
 *     (um encoder pode ter o profile mas rejeitar a combinação completa — isso só
 *     é confirmado tentando `MediaCodec.configure()`, não apenas lendo capabilities).
 *
 * Por isso o resultado desta função nunca é "HDR confirmado", e sim "HDR
 * habilitado no pipeline, com a ressalva documentada" — a UI deve mostrar essa
 * ressalva, não uma afirmação categórica.
 */
object HdrChainValidator {

    fun evaluate(
        displayManager: DisplayManager,
        encoderInfo: EncoderInfo?
    ): HdrChainResult {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return HdrChainResult(false, "Display não encontrado")

        val displayHdrTypes = try {
            display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        } catch (e: Exception) {
            IntArray(0)
        }

        if (displayHdrTypes.isEmpty()) {
            return HdrChainResult(false, "Tela não reporta suporte a nenhum tipo de HDR (Display.getHdrCapabilities)")
        }

        if (encoderInfo == null) {
            return HdrChainResult(false, "Encoder não determinado ainda")
        }

        if (encoderInfo.hdrProfilesSupported.isEmpty()) {
            return HdrChainResult(false, "Encoder ${encoderInfo.codecName} não expõe profile HDR10/HDR10+ para esta resolução")
        }

        return HdrChainResult(
            true,
            "Tela e encoder confirmam suporte a HDR. ATENÇÃO: não há API pública para confirmar que o " +
                "conteúdo efetivamente composto pelo MediaProjection é HDR — os metadados são aplicados " +
                "de forma condicional, mas isso não é o mesmo que 'gravação HDR confirmada'."
        )
    }
}
