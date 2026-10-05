package com.droplay.tv.data

import java.text.Normalizer
import java.util.Calendar
import java.util.IdentityHashMap
import java.util.Locale

data class PreparedCatalog(
    val entries: List<MediaEntry> = emptyList(),
    val movieVariants: Map<String, List<MediaEntry>> = emptyMap(),
    val movies: List<MediaEntry> = emptyList(),
    val series: List<MediaEntry> = emptyList(),
    val live: List<MediaEntry> = emptyList(),
    val kidsMovies: List<MediaEntry> = emptyList(),
    val kidsSeries: List<MediaEntry> = emptyList(),
    val kidsCartoons: List<MediaEntry> = emptyList(),
    val kidsLive: List<MediaEntry> = emptyList(),
    val nationalMovies: List<MediaEntry> = emptyList(),
    val nationalSeries: List<MediaEntry> = emptyList(),
    val nationalNovels: List<MediaEntry> = emptyList(),
    val categoryIndex: Map<MediaKind, Map<String, List<MediaEntry>>> = emptyMap(),
    val liveSubcategoryIndex: Map<String, Map<String, List<MediaEntry>>> = emptyMap(),
    val homeShelves: List<Pair<String, List<MediaEntry>>> = emptyList(),
    val recentMovies: List<MediaEntry> = emptyList(),
    val releaseMovies: List<MediaEntry> = emptyList(),
    val releaseSeries: List<MediaEntry> = emptyList(),
)

object CatalogOrganizer {
    private val camMarker = Regex("(^|\\s|\\[|\\(|\\{)cam($|\\s|\\]|\\)|\\})", RegexOption.IGNORE_CASE)
    private val brazilMarker = Regex("(^|\\s|[|/-])br($|\\s|[|/-])")
    private val subtitleMarker = Regex("(^|\\s|\\[)l(\\]|\\s|$)")
    private val variantTag = Regex("\\[(l|d|dual)]")
    private val variantLanguage = Regex("\\b(legendado|legendada|dublado|dublada|dual audio)\\b")
    private val whitespace = Regex("\\s+")
    private val categoryDecorations = Regex("[\\p{So}\\p{Co}\\uFE0F\\u200B\\u200D]")
    private val categoryPrefix = Regex("(?i)^\\s*(filmes?|movies?|series?|séries?|canais?|tv|ao vivo|vod)\\s*[-:|•/]*\\s*")
    private val categoryLanguageSuffix = Regex("(?i)\\s*[-:|•/]*\\s*(legendados?|dublados?)\\s*$")
    private val categoryNumberSuffix = Regex("[²³⁴⁵⁶⁷⁸⁹]+$")
    private val releaseYear = Regex("(?:19|20)\\d{2}")
    private val diacritics = Regex("\\p{Mn}+")
    const val RECENT = "Últimos adicionados"
    const val RELEASES = "Lançamentos"
    const val FOOTBALL = "Futebol ao vivo"
    private val legacyAdultSignals = listOf("xxx", "adulto", "adultos", "+18", "18+", "porn", "hentai", "onlyfans")
    private val legacyCinemaSignals = listOf("hdcam", "hd cam", "camrip", "cam rip", "telesync", "hdts", "hd ts", "dvdscr", "dvd scr", "workprint")

    fun visibleEntries(entries: List<MediaEntry>, showAdult: Boolean, showCinema: Boolean): List<MediaEntry> =
        entries.asSequence()
            .filterNot(::isAlwaysBlocked)
            .filterNot { it.isHidden && !isAdult(it) && !isCinema(it) }
            .filter { showAdult || !isAdult(it) }
            .filter { showCinema || !isCinema(it) }
            .toList()

    /** Índice mínimo para liberar a interface imediatamente; os índices avançados são montados depois. */
    fun prepareInitial(entries: List<MediaEntry>, showAdult: Boolean = false, showCinema: Boolean = false): PreparedCatalog {
        val visible = ArrayList<MediaEntry>(entries.size)
        val movies = ArrayList<MediaEntry>()
        val series = ArrayList<MediaEntry>()
        val live = ArrayList<MediaEntry>()
        entries.forEach { item ->
            if (isAlwaysBlocked(item) || (item.isHidden && !isAdult(item) && !isCinema(item)) || (!showAdult && isAdult(item)) || (!showCinema && isCinema(item))) return@forEach
            visible += item
            when (item.kind) {
                MediaKind.MOVIE -> movies += item
                MediaKind.SERIES -> series += item
                MediaKind.LIVE -> live += item
            }
        }
        return PreparedCatalog(entries = visible, movies = movies, series = series, live = live)
    }

    fun prepare(entries: List<MediaEntry>, showAdult: Boolean, showCinema: Boolean, checkCancellation: () -> Unit = {}): PreparedCatalog {
        checkCancellation()
        val allowed = visibleEntries(entries, showAdult, showCinema)
        val subtitleByMovie = IdentityHashMap<MediaEntry, Boolean>()
        allowed.asSequence().filter { it.kind == MediaKind.MOVIE }.forEach { subtitleByMovie[it] = isSubtitled(it) }
        val groupedMovies = allowed.asSequence().filter { it.kind == MediaKind.MOVIE }.groupBy(::movieVariantKey)
        val variants = groupedMovies
            .mapValues { (_, items) -> items.distinctBy { subtitleByMovie[it] == true } }
            .filterValues { it.size > 1 }
        val selectedMovieIds = groupedMovies.values.asSequence()
            .map { items -> items.firstOrNull { subtitleByMovie[it] != true } ?: items.first() }
            .mapTo(HashSet(), MediaEntry::id)
        checkCancellation()
        val visible = allowed.filter { it.kind != MediaKind.MOVIE || it.id in selectedMovieIds }
        val movies = visible.filter { it.kind == MediaKind.MOVIE }
        val series = visible.filter { it.kind == MediaKind.SERIES }
        val live = visible.filter { it.kind == MediaKind.LIVE }
        val kids = visible.filter(::isKids)
        val kidsCartoons = kids.filter(::isCartoon)
        val cartoonIds = kidsCartoons.mapTo(HashSet(), MediaEntry::id)
        val national = visible.filter(::isNational)
        val nationalNovels = national.filter(::isNovel)
        val novelIds = nationalNovels.mapTo(HashSet(), MediaEntry::id)
        val categoryByEntry = IdentityHashMap<MediaEntry, String>()
        val categoryNames = HashMap<Pair<MediaKind, String>, String>()
        visible.forEachIndexed { index, item ->
            if (index % 256 == 0) checkCancellation()
            categoryByEntry[item] = if (item.kind == MediaKind.LIVE) category(item)
                else categoryNames.getOrPut(item.kind to item.group) { cleanCategory(item.group, item.kind) }
        }
        val categoryIndex = mapOf(
            MediaKind.MOVIE to movies.groupBy { categoryByEntry[it].orEmpty() },
            MediaKind.SERIES to series.groupBy { categoryByEntry[it].orEmpty() },
            MediaKind.LIVE to live.groupBy { categoryByEntry[it].orEmpty() },
        )
        val liveSubcategoryIndex = live.groupBy { categoryByEntry[it].orEmpty() }
            .mapValues { (_, channels) -> channels.groupBy { categoryNames.getOrPut(it.kind to it.group) { cleanCategory(it.group, MediaKind.LIVE) } } }
        val recent = movies.sortedByDescending { it.addedAt }.let { sorted ->
            if ((sorted.firstOrNull()?.addedAt ?: 0L) > 0) sorted.take(100) else movies.asReversed().take(100)
        }
        checkCancellation()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return PreparedCatalog(
            entries = visible, movieVariants = variants, movies = movies, series = series, live = live,
            kidsMovies = kids.filter { it.kind == MediaKind.MOVIE && it.id !in cartoonIds },
            kidsSeries = kids.filter { it.kind == MediaKind.SERIES && it.id !in cartoonIds },
            kidsCartoons = kidsCartoons, kidsLive = kids.filter { it.kind == MediaKind.LIVE },
            nationalMovies = national.filter { it.kind == MediaKind.MOVIE },
            nationalSeries = national.filter { it.kind == MediaKind.SERIES && it.id !in novelIds },
            nationalNovels = nationalNovels, categoryIndex = categoryIndex, liveSubcategoryIndex = liveSubcategoryIndex,
            homeShelves = visible.filter { it.kind != MediaKind.LIVE }.groupBy { categoryByEntry[it].orEmpty() }.entries
                .sortedByDescending { it.value.size }.take(6).map { it.key to it.value.take(24) },
            recentMovies = recent, releaseMovies = movies.filter { yearOf(it) == currentYear }, releaseSeries = series.filter { yearOf(it) == currentYear },
        )
    }

    fun isAdult(item: MediaEntry): Boolean {
        if (item.classificationVersion == ContentClassificationEngine.VERSION) return item.isAdult
        val text = "${item.group} ${item.name}".lowercase(Locale.ROOT)
        return legacyAdultSignals.any(text::contains)
    }

    fun isCinema(item: MediaEntry): Boolean {
        if (item.classificationVersion == ContentClassificationEngine.VERSION) return item.isLowQualityCinema
        if (item.kind != MediaKind.MOVIE) return false
        val text = "${item.group} ${item.name}".lowercase(Locale.ROOT)
        return legacyCinemaSignals.any(text::contains) || camMarker.containsMatchIn(item.name)
    }

    fun isKids(item: MediaEntry): Boolean {
        if (item.classificationVersion == ContentClassificationEngine.VERSION) return item.isKids && !item.isAdult
        if (isAdult(item)) return false
        val group = normalized(item.group)
        val name = normalized(item.name)
        val categorySignals = listOf("infantil", "kids", "crianca", "desenho", "cartoon", "baby", "junior", "nick jr", "disney jr", "discovery kids", "gloob", "boomerang", "toon")
        val unmistakableKidsBrands = listOf("baby tv", "babytv", "nick jr", "disney jr", "discovery kids", "cartoon network", "gloob", "boomerang", "turma da monica")
        return categorySignals.any(group::contains) || unmistakableKidsBrands.any(name::contains)
    }

    fun isCartoon(item: MediaEntry): Boolean {
        val text = normalized("${item.group} ${item.name}")
        return listOf("desenho", "cartoon", "animacao", "animation", "anime", "toon").any(text::contains)
    }

    fun isNational(item: MediaEntry): Boolean {
        if (item.classificationVersion == ContentClassificationEngine.VERSION) return item.isBrazilian
        if (item.kind == MediaKind.LIVE) return false
        val text = normalized("${item.group} ${item.name}")
        val brazilianNovela = text.contains("novela") && listOf("turca", "mexic", "corean", "doramas").none(text::contains)
        return listOf("nacional", "brasil", "brasileir", "globoplay", "sbt+", "cinema nacional", "novela brasileira").any(text::contains) ||
            brazilMarker.containsMatchIn(text) || brazilianNovela
    }

    fun isNovel(item: MediaEntry): Boolean {
        if (item.kind != MediaKind.SERIES) return false
        val text = normalized("${item.group} ${item.name}")
        return text.contains("novela") && listOf("turca", "mexic", "corean", "dorama").none(text::contains)
    }

    fun isSubtitled(item: MediaEntry): Boolean {
        val text = normalized("${item.name} ${item.group}")
        return subtitleMarker.containsMatchIn(text) || text.contains("legendad")
    }

    fun movieTitleKey(item: MediaEntry): String = normalized(item.name)
        .replace(variantTag, " ")
        .replace(variantLanguage, " ")
        .replace(whitespace, " ")
        .trim()

    fun movieVariantKey(item: MediaEntry): String = "${movieTitleKey(item)}:${yearOf(item) ?: 0}"

    fun collapseMovieVariants(entries: List<MediaEntry>): List<MediaEntry> {
        val movies = entries.filter { it.kind == MediaKind.MOVIE }
            .groupBy(::movieVariantKey)
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

    private data class SortKey(val item: MediaEntry, val name: String, val year: Int, val plays: Int)

    fun sort(entries: List<MediaEntry>, order: ContentSort, playCounts: Map<String, Int>): List<MediaEntry> {
        // Comparators must not normalize names or run regexes O(n log n) times.
        val keys = entries.map { SortKey(it, it.normalizedName.ifBlank { normalized(it.name) },
            if (order == ContentSort.YEAR_DESC) yearOf(it) ?: 0 else 0, playCounts[it.id] ?: 0) }
        val comparator = when (order) {
            ContentSort.YEAR_DESC -> compareByDescending<SortKey> { it.year }.thenByDescending { it.item.addedAt }.thenBy { it.name }
            ContentSort.ALPHABETICAL -> compareBy<SortKey> { it.name }
            ContentSort.MOST_WATCHED -> compareByDescending<SortKey> { it.plays }.thenByDescending { it.item.addedAt }.thenBy { it.name }
        }
        return keys.sortedWith(comparator).map { it.item }
    }

    fun yearOf(item: MediaEntry): Int? = item.year ?: yearFrom("${item.name} ${item.group}")

    fun isCurrentYear(item: MediaEntry): Boolean = yearOf(item) == Calendar.getInstance().get(Calendar.YEAR)

    fun cleanCategory(value: String, kind: MediaKind): String {
        var clean = value.replace(categoryDecorations, " ").trim()
            .replace(categoryPrefix, "")
            .replace(categoryLanguageSuffix, "")
            .replace(categoryNumberSuffix, "")
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
            listOf("futebol", "jogos principais", "jogo principal", "payperview", "pay per view", "pay-per-view", "ppv", "premiere", "brasileirao", "libertadores", "champions", "⚽")
                .any(text::contains) -> FOOTBALL
            listOf("luta", "ufc", "mma", "boxe", "wrestling").any(text::contains) -> "Lutas"
            listOf("esporte", "sport", "espn").any(text::contains) -> "Esportes"
            listOf("document", "curiosidade", "discovery", "history", "animal planet").any(text::contains) -> "Documentários e curiosidades"
            listOf("infantil", "kids", "desenho", "cartoon", "gloob", "nick", "disney jr").any(text::contains) -> "Infantis"
            text.contains("telecine") -> "Telecine"
            listOf("filme", "serie", "movie", "cinema", "hbo", "megapix", "studio universal", "space").any(text::contains) -> "Filmes e séries"
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
        val text = "${item.group} ${item.name}".lowercase(Locale.ROOT)
        if (text.contains("brasil paralelo") || text.contains("brasil pararelo")) return true
        if (item.kind == MediaKind.LIVE && listOf("checklist", "voce sabia", "você sabia", "canais do cliente", "canal do cliente").any(text::contains)) return true
        return false
    }

    private fun canonicalCategory(value: String): String {
        val key = normalized(value).replace(whitespace, " ").trim()
        return when {
        key == "acao" -> "Ação"
        key in setOf("ficcao", "ficcao cientifica") -> "Ficção científica"
        key == "comedia" -> "Comédia"
        key == "animacao" -> "Animação"
        key in setOf("lancamento", "lancamentos") -> "Lançamentos"
        key in setOf("documentario", "documentarios") -> "Documentários"
        key == "series" -> "Séries"
        key in setOf("disney plus", "disney +", "disney+", "disneyplus") -> "Disney Plus"
        key in setOf("apple tv plus", "apple tv +", "apple tv+", "appletv plus", "appletv+") -> "Apple TV+"
        key in setOf("paramount", "paramount plus", "paramount +", "paramount+", "paramountplus") -> "Paramount+"
        key in setOf("amazon prime video", "amazon prime", "prime video", "primevideo") -> "Prime Video"
        key in setOf("dorama", "doramas") -> "Doramas"
        key.contains("novela") -> "Novelas"
        else -> value.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.forLanguageTag("pt-BR")) else it.toString() }
        }
    }

    private fun yearFrom(value: String): Int? = releaseYear.findAll(value).lastOrNull()?.value?.toIntOrNull()

    private fun normalized(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(diacritics, "")
        .replace('²', '2')
}
