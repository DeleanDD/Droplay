package com.droplay.tv.data

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

object CatalogOrganizer {
    const val RECENT = "Últimos adicionados"
    const val RELEASES = "Lançamentos"
    const val FOOTBALL = "Futebol ao vivo"

    fun visibleEntries(entries: List<MediaEntry>, showAdult: Boolean, showCinema: Boolean): List<MediaEntry> =
        entries.asSequence()
            .filterNot(::isAlwaysBlocked)
            .filter { showAdult || !isAdult(it) }
            .filter { showCinema || !isCinema(it) }
            .toList()

    fun isAdult(item: MediaEntry): Boolean {
        val text = normalized("${item.group} ${item.name}")
        if (text.contains("adult swim") && listOf("porn", "xxx", "+18", "18+").none(text::contains)) return false
        return listOf("adult", "porn", "porno", "xxx", "erotico", "erotica", "+18", "18+", "18 anos", "onlyfans", "playboy", "sexy hot", "hentai", "redlight", "private spice")
            .any(text::contains)
    }

    fun isCinema(item: MediaEntry): Boolean = item.kind == MediaKind.MOVIE && normalized(item.name).contains("cinema")

    fun isKids(item: MediaEntry): Boolean {
        val text = normalized("${item.group} ${item.name}")
        return listOf("infantil", "kids", "crianca", "desenho", "cartoon", "baby", "junior", "nick jr", "disney jr", "discovery kids", "gloob", "boomerang", "toon", "turma da monica")
            .any(text::contains)
    }

    fun isCartoon(item: MediaEntry): Boolean {
        val text = normalized("${item.group} ${item.name}")
        return listOf("desenho", "cartoon", "animacao", "animation", "anime", "toon").any(text::contains)
    }

    fun isNational(item: MediaEntry): Boolean {
        if (item.kind == MediaKind.LIVE) return false
        val text = normalized("${item.group} ${item.name}")
        return listOf("nacional", "brasil", "brasileir", "globoplay", "cinema nacional", "novela brasileira").any(text::contains)
    }

    fun isSubtitled(item: MediaEntry): Boolean {
        val text = normalized("${item.name} ${item.group}")
        return Regex("(^|\\s|\\[)l(\\]|\\s|$)").containsMatchIn(text) || text.contains("legendad")
    }

    fun movieTitleKey(item: MediaEntry): String = normalized(item.name)
        .replace(Regex("\\[(l|d|dual)]"), " ")
        .replace(Regex("\\b(legendado|legendada|dublado|dublada|dual audio)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun collapseMovieVariants(entries: List<MediaEntry>): List<MediaEntry> {
        val movies = entries.filter { it.kind == MediaKind.MOVIE }
            .groupBy { "${movieTitleKey(it)}:${it.year ?: 0}" }
            .values.map { variants -> variants.firstOrNull { !isSubtitled(it) } ?: variants.first() }
        val selectedIds = movies.mapTo(HashSet(), MediaEntry::id)
        return entries.filter { it.kind != MediaKind.MOVIE || it.id in selectedIds }
    }

    fun variantsFor(item: MediaEntry, entries: List<MediaEntry>): List<MediaEntry> {
        if (item.kind != MediaKind.MOVIE) return listOf(item)
        val key = movieTitleKey(item)
        return entries.filter { it.kind == MediaKind.MOVIE && movieTitleKey(it) == key && (item.year == null || it.year == null || it.year == item.year) }
            .distinctBy { isSubtitled(it) }
    }

    fun category(item: MediaEntry): String = when (item.kind) {
        MediaKind.LIVE -> liveCategory(item)
        else -> cleanCategory(item.group, item.kind)
    }

    fun sort(entries: List<MediaEntry>, order: ContentSort, playCounts: Map<String, Int>): List<MediaEntry> = when (order) {
        ContentSort.YEAR_DESC -> entries.sortedWith(compareByDescending<MediaEntry> { yearOf(it) ?: 0 }
            .thenByDescending { it.addedAt }.thenBy { normalized(it.name) })
        ContentSort.ALPHABETICAL -> entries.sortedBy { normalized(it.name) }
        ContentSort.MOST_WATCHED -> entries.sortedWith(compareByDescending<MediaEntry> { playCounts[it.id] ?: 0 }
            .thenByDescending { it.addedAt }.thenBy { normalized(it.name) })
    }

    fun yearOf(item: MediaEntry): Int? = item.year ?: yearFrom("${item.name} ${item.group}")

    fun isCurrentYear(item: MediaEntry): Boolean = yearOf(item) == Calendar.getInstance().get(Calendar.YEAR)

    fun cleanCategory(value: String, kind: MediaKind): String {
        var clean = value.trim()
            .replace(Regex("(?i)^\\s*(filmes?|movies?|series?|séries?|canais?|tv|ao vivo|vod)\\s*[-:|•/]*\\s*"), "")
            .replace(Regex("(?i)\\s*[-:|•/]*\\s*(legendados?|dublados?)\\s*$"), "")
            .replace(Regex("[²³⁴⁵⁶⁷⁸⁹]+$"), "")
            .trim(' ', '-', ':', '|', '•', '/')
        if (clean.isBlank()) clean = when (kind) {
            MediaKind.LIVE -> "Outros canais"
            MediaKind.MOVIE -> "Outros filmes"
            MediaKind.SERIES -> "Outras séries"
        }
        return canonicalCategory(clean)
    }

    private fun liveCategory(item: MediaEntry): String {
        val text = normalized("${item.group} ${item.name}")
        return when {
            text.contains("futebol") -> FOOTBALL
            listOf("luta", "ufc", "mma", "boxe", "wrestling").any(text::contains) -> "Lutas"
            listOf("esporte", "sport", "espn", "premiere").any(text::contains) -> "Esportes"
            listOf("document", "curiosidade", "discovery", "history", "animal planet").any(text::contains) -> "Documentários e curiosidades"
            listOf("infantil", "kids", "desenho", "cartoon", "gloob", "nick", "disney jr").any(text::contains) -> "Infantis"
            text.contains("telecine") -> "Telecine"
            text.contains("globo") -> "Globo"
            text.contains("sbt") -> "SBT"
            text.contains("record") -> "Record"
            text.contains("band") -> "Band"
            listOf("rede tv", "tv aberta").any(text::contains) -> "TV aberta"
            listOf("noticia", "news", "cnn", "jovem pan").any(text::contains) -> "Notícias"
            listOf("musica", "music", "mtv").any(text::contains) -> "Música"
            listOf("variedade", "variedades").any(text::contains) -> "Variedades"
            else -> cleanCategory(item.group, MediaKind.LIVE)
        }
    }

    private fun isAlwaysBlocked(item: MediaEntry): Boolean {
        val text = normalized("${item.group} ${item.name}")
        if (item.kind == MediaKind.LIVE && listOf("checklist", "voce sabia", "canais do cliente", "canal do cliente").any(text::contains)) return true
        return item.kind == MediaKind.SERIES && text.contains("brasil paralelo")
    }

    private fun canonicalCategory(value: String): String = when (normalized(value).replace(Regex("\\s+"), " ").trim()) {
        "acao" -> "Ação"
        "ficcao", "ficcao cientifica" -> "Ficção científica"
        "comedia" -> "Comédia"
        "animacao" -> "Animação"
        "documentario", "documentarios" -> "Documentários"
        "series" -> "Séries"
        else -> value.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.forLanguageTag("pt-BR")) else it.toString() }
    }

    private fun yearFrom(value: String): Int? = Regex("(?:19|20)\\d{2}").findAll(value).lastOrNull()?.value?.toIntOrNull()

    private fun normalized(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('²', '2')
}
