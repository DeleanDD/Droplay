package com.droplay.tv.ui

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.mp4.NoDeclaredBrandSniffFailure
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import java.io.IOException

/**
 * Alguns servidores Xtream entregam MP4 reproduzível sem a caixa `ftyp`.
 * O Media3 rejeita esses arquivos durante o sniff; este adaptador só força o
 * extrator MP4 nesse caso específico e deixa todos os demais formatos intactos.
 */
@OptIn(UnstableApi::class)
internal class LenientMp4Extractor : Extractor {
    private var selected: Extractor? = null
    private val subtitleParsers = DefaultSubtitleParserFactory()

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        val fragmented = FragmentedMp4Extractor(subtitleParsers)
        val fragmentedMatch = fragmented.sniff(input)
        val fragmentedNoBrand = fragmented.sniffFailureDetails.any { it is NoDeclaredBrandSniffFailure }
        input.resetPeekPosition()
        if (fragmentedMatch) {
            selected = fragmented
            return true
        }

        val regular = Mp4Extractor(subtitleParsers)
        val regularMatch = regular.sniff(input)
        val regularNoBrand = regular.sniffFailureDetails.any { it is NoDeclaredBrandSniffFailure }
        input.resetPeekPosition()
        if (regularMatch || fragmentedNoBrand || regularNoBrand) {
            selected = regular
            return true
        }
        return false
    }

    override fun init(output: ExtractorOutput) = requireNotNull(selected).init(output)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = requireNotNull(selected).read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = requireNotNull(selected).seek(position, timeUs)
    override fun release() { selected?.release() }
    override fun getUnderlyingImplementation(): Extractor = selected ?: this
}
