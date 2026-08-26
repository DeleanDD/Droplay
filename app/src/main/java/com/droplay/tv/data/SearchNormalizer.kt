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
        if (needle.length < 3) return false
        val name = item.normalizedName.takeIf(String::isNotBlank) ?: normalize(item.name)
        val category = item.normalizedCategoryName.takeIf(String::isNotBlank) ?: normalize(item.group)
        return name.contains(needle) || category.contains(needle)
    }
}
