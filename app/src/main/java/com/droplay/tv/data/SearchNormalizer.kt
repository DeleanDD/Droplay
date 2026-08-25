package com.droplay.tv.data

import java.text.Normalizer
import java.util.Locale

object SearchNormalizer {
    fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun matches(item: MediaEntry, query: String): Boolean {
        val needle = normalize(query)
        return needle.length >= 3 && (normalize(item.name).contains(needle) || normalize(item.group).contains(needle))
    }
}
