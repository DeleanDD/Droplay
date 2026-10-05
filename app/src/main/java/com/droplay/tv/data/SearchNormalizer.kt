package com.droplay.tv.data

import java.text.Normalizer
import java.util.Locale

object SearchNormalizer {
    private val accents = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(accents, "")
        .replace(separators, " ")
        .trim()

    private class Query(query: String) {
        val text = normalize(query)
        val words = text.split(' ').filter(String::isNotBlank)
        val compact = words.joinToString("")
        val valid = compact.length >= 3
        fun matches(text: String): Boolean = words.all(text::contains) ||
            (compact.length >= 3 && text.replace(" ", "").contains(compact))
    }

    fun matches(item: MediaEntry, query: String): Boolean = Query(query).let { needle ->
        needle.valid && (needle.matches(item.normalizedName.ifBlank { normalize(item.name) }) ||
            needle.matches(item.normalizedCategoryName.ifBlank { normalize(item.group) }))
    }

    /** Normalize the query once. Title matches take precedence over category matches. */
    fun search(entries: List<MediaEntry>, query: String, limit: Int = 240, checkCancellation: () -> Unit = {}): List<MediaEntry> {
        val needle = Query(query)
        if (!needle.valid || limit <= 0) return emptyList()
        val titles = ArrayList<MediaEntry>()
        val categories = ArrayList<MediaEntry>()
        entries.forEachIndexed { index, item ->
            if (index % 256 == 0) checkCancellation()
            if (needle.matches(item.normalizedName.ifBlank { normalize(item.name) })) {
                titles += item
                if (titles.size == limit) return titles
            } else if (categories.size < limit && needle.matches(item.normalizedCategoryName.ifBlank { normalize(item.group) })) {
                categories += item
            }
        }
        return titles + categories.take(limit - titles.size)
    }
}
