package com.droplay.tv.subtitles

import java.io.File

class SubtitleFileCache(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val expiryMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val maxBytes: Long = 50L * 1024 * 1024,
) {
    init { root.mkdirs() }

    fun raw(fileId: Int): File? = root.listFiles()?.firstOrNull { it.name.startsWith("raw-$fileId.") }
        ?.takeIf { now() - it.lastModified() in 0..expiryMs }?.also { it.setLastModified(now()) }

    fun store(fileId: Int, text: String, format: SubtitleFormat): File {
        val target = File(root, "raw-$fileId.${format.extension}")
        target.writeText(text, Charsets.UTF_8); target.setLastModified(now()); prune(); return target
    }

    fun playable(fileId: Int, delayMs: Long): File? = root.listFiles()?.firstOrNull { it.name.startsWith("play-$fileId-$delayMs.") }
        ?.takeIf { now() - it.lastModified() in 0..expiryMs }?.also { it.setLastModified(now()) }

    fun prepare(fileId: Int, raw: File, delayMs: Long): File {
        playable(fileId, delayMs)?.let { return it }
        val text = raw.readText(Charsets.UTF_8)
        val format = SubtitleText.validate(text)
        val target = File(root, "play-$fileId-$delayMs.${format.extension}")
        target.writeText(SubtitleText.shift(text, delayMs, format), Charsets.UTF_8); target.setLastModified(now()); prune(); return target
    }

    fun prune() {
        val files = root.listFiles()?.filter(File::isFile).orEmpty()
        files.filter { now() - it.lastModified() > expiryMs }.forEach(File::delete)
        var size = root.listFiles()?.sumOf(File::length) ?: 0L
        root.listFiles()?.sortedBy(File::lastModified)?.forEach { file ->
            if (size > maxBytes) { size -= file.length(); file.delete() }
        }
    }
}
