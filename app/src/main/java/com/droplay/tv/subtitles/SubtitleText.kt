package com.droplay.tv.subtitles

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

object SubtitleText {
    private val srtTime = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})")
    private val vttTime = Regex("(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[.](\\d{3})")

    fun decode(bytes: ByteArray): String {
        val raw = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else bytes
        if (raw.size >= 2 && raw[0] == 0xff.toByte() && raw[1] == 0xfe.toByte()) return String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
        if (raw.size >= 2 && raw[0] == 0xfe.toByte() && raw[1] == 0xff.toByte()) return String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
        val offset = if (raw.size >= 3 && raw[0] == 0xef.toByte() && raw[1] == 0xbb.toByte() && raw[2] == 0xbf.toByte()) 3 else 0
        val payload = raw.copyOfRange(offset, raw.size)
        return runCatching {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString()
        }.getOrElse { String(payload, charset("windows-1252")) }
    }

    fun validate(text: String): SubtitleFormat {
        val clean = text.trimStart('\uFEFF', ' ', '\r', '\n')
        return when {
            clean.startsWith("WEBVTT", true) && vttTime.containsMatchIn(clean) -> SubtitleFormat.WEBVTT
            clean.contains("-->") && srtTime.containsMatchIn(clean) -> SubtitleFormat.SRT
            else -> throw IllegalArgumentException("O arquivo baixado não é uma legenda SRT/WebVTT válida.")
        }
    }

    fun shift(text: String, delayMs: Long, format: SubtitleFormat = validate(text)): String {
        if (delayMs == 0L) return text
        val regex = if (format == SubtitleFormat.SRT) srtTime else vttTime
        return regex.replace(text) { match ->
            val millis = if (format == SubtitleFormat.SRT) {
                (((match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) * 60 + match.groupValues[3].toLong()) * 1000 + match.groupValues[4].toLong())
            } else {
                (((match.groupValues[1].toLongOrNull() ?: 0) * 60 + match.groupValues[2].toLong()) * 60 + match.groupValues[3].toLong()) * 1000 + match.groupValues[4].toLong()
            }
            formatTime((millis + delayMs).coerceAtLeast(0), format, match.groupValues[1].isNotEmpty())
        }
    }

    private fun formatTime(ms: Long, format: SubtitleFormat, hadHours: Boolean): String {
        val hours = ms / 3_600_000; val minutes = (ms / 60_000) % 60; val seconds = (ms / 1_000) % 60; val millis = ms % 1_000
        return if (format == SubtitleFormat.SRT) "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
        else if (hadHours || hours > 0) "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
        else "%02d:%02d.%03d".format(minutes, seconds, millis)
    }
}

enum class SubtitleFormat(val extension: String, val mimeType: String) {
    SRT("srt", "application/x-subrip"), WEBVTT("vtt", "text/vtt")
}
