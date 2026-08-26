package com.droplay.tv.data

import java.text.Normalizer
import java.util.Locale

enum class ClassificationReason { NONE, SERVER_ADULT, ADULT_CATEGORY, EXPLICIT_ADULT, LOW_QUALITY_CINEMA }
enum class ClassificationConfidence { HIGH, MEDIUM, LOW }

data class ClassificationInput(
    val name: String,
    val categoryName: String,
    val kind: MediaKind,
    val serverAdult: Boolean = false,
    val country: String? = null,
    val genre: String? = null,
    val parentalRating: String? = null,
)

data class ContentClassification(
    val normalizedName: String,
    val normalizedCategoryName: String,
    val isAdult: Boolean,
    val isLowQualityCinema: Boolean,
    val isKids: Boolean,
    val isBrazilian: Boolean,
    val isHidden: Boolean,
    val reason: ClassificationReason,
    val confidence: ClassificationConfidence,
    val version: Int = ContentClassificationEngine.VERSION,
)

object ContentClassificationEngine {
    const val VERSION = 2

    private val explicitAdult = setOf("xxx", "porno", "porn", "pornografico", "pornografica", "hentai", "onlyfans", "playboy", "redlight")
    private val adultCategoryTokens = explicitAdult + setOf("adulto", "adultos", "adult", "erotico", "erotica", "sex", "sexy", "hot", "privacy")
    private val kidsCategorySignals = setOf("infantil", "infantis", "kids", "crianca", "criancas", "familia", "family", "desenho", "desenhos", "preschool", "pre escolar")
    private val kidsBrands = setOf("disney junior", "disney jr", "nickelodeon", "nick jr", "cartoon network", "discovery kids", "gloob", "baby tv", "babytv", "turma da monica")
    private val adultAnimationSignals = setOf("adult swim", "hentai", "south park", "family guy", "rick and morty", "terror", "horror", "gore", "violencia adulta")
    private val brazilianCategories = setOf("nacional", "nacionais", "cinema nacional", "filmes brasileiros", "series brasileiras", "brasil", "brasilidades", "producao brasileira", "novelas brasileiras")
    private val lowQualityPhrases = setOf("hdcam", "hd cam", "camrip", "cam rip", "telesync", "hdts", "hd ts", "screener", "dvdscr", "dvd scr", "workprint", "line audio", "mic audio", "cinema audio", "audio de cinema", "gravado no cinema")
    private val releaseContext = Regex("\\b(19|20)\\d{2}\\b|\\b(720p|1080p|2160p|4k|h264|x264|h265|x265|hevc|aac)\\b")
    private val isolatedCam = Regex("(^|\\s)cam($|\\s)")
    private val ambiguousCinemaMarker = Regex("(^|\\s)(ts|tc|scr|wp)($|\\s)")

    fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[._\\-\\[\\](){}|/+:]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun classify(input: ClassificationInput): ContentClassification {
        val rawCategory = input.categoryName.lowercase(Locale.ROOT)
        val name = normalize(input.name)
        val category = normalize(input.categoryName)
        val country = normalize(input.country.orEmpty())
        val genre = normalize(input.genre.orEmpty())
        val combined = "$category $name $genre".trim()
        val nameTokens = name.splitToSequence(' ').filter(String::isNotBlank).toSet()
        val categoryTokens = category.splitToSequence(' ').filter(String::isNotBlank).toSet()

        val serverAdult = input.serverAdult
        val adultCategory = categoryTokens.any(adultCategoryTokens::contains) || adultCategoryTokens.any { phrase -> phrase.contains(' ') && category.contains(phrase) } ||
            rawCategory.contains("18+") || rawCategory.contains("+18") || category.contains("conteudo adulto") || category.contains("adult movies") || category.contains("adult channels")
        val explicitByName = nameTokens.any(explicitAdult::contains) || explicitAdult.any { it.contains(' ') && name.contains(it) }
        val isAdult = serverAdult || adultCategory || explicitByName

        val lowQuality = input.kind == MediaKind.MOVIE && isLowQualityRelease(name, category)
        val hidden = isAdult || lowQuality
        val kidsPositive = kidsCategorySignals.any { category.containsWordOrPhrase(it) } || kidsBrands.any { combined.contains(it) }
        val unsafeForKids = isAdult || lowQuality || adultAnimationSignals.any { combined.contains(it) } || normalize(input.parentalRating.orEmpty()) in setOf("18", "18 anos", "r", "nc 17")
        val brazilCountry = country.splitToSequence(' ', ',', ';', '|', '/').any { it == "brasil" || it == "brazil" } || country.contains("brasil") || country.contains("brazil")
        val portugueseCountry = country.contains("portugal") && !brazilCountry
        val brDedicatedCategory = "br" in categoryTokens && listOf("filme", "filmes", "serie", "series", "novela", "novelas", "nacional").any(categoryTokens::contains)
        val brazilCategory = !portugueseCountry && (brazilianCategories.any { category.containsWordOrPhrase(it) } || brDedicatedCategory)

        val reason = when {
            serverAdult -> ClassificationReason.SERVER_ADULT
            adultCategory -> ClassificationReason.ADULT_CATEGORY
            explicitByName -> ClassificationReason.EXPLICIT_ADULT
            lowQuality -> ClassificationReason.LOW_QUALITY_CINEMA
            else -> ClassificationReason.NONE
        }
        return ContentClassification(name, category, isAdult, lowQuality,
            isKids = kidsPositive && !unsafeForKids,
            isBrazilian = (brazilCountry || brazilCategory) && !hidden,
            isHidden = hidden, reason = reason,
            confidence = if (reason != ClassificationReason.NONE || brazilCountry || kidsPositive) ClassificationConfidence.HIGH else ClassificationConfidence.LOW)
    }

    fun isBlockedCategory(name: String, serverAdult: Boolean = false): Boolean {
        if (serverAdult) return true
        val raw = name.lowercase(Locale.ROOT)
        val normalized = normalize(name)
        val tokens = normalized.splitToSequence(' ').toSet()
        return tokens.any(adultCategoryTokens::contains) || explicitAdult.any { normalized.containsWordOrPhrase(it) } ||
            raw.contains("18+") || raw.contains("+18") || normalized.contains("conteudo adulto") ||
            normalized.contains("adult movies") || normalized.contains("adult channels")
    }

    private fun isLowQualityRelease(name: String, category: String): Boolean {
        val combined = "$name $category"
        if (lowQualityPhrases.any(combined::contains)) return true
        if (isolatedCam.containsMatchIn(name)) return true
        return ambiguousCinemaMarker.containsMatchIn(name) && releaseContext.containsMatchIn(name)
    }

    private fun String.containsWordOrPhrase(signal: String): Boolean =
        Regex("(^|\\s)${Regex.escape(signal)}($|\\s)").containsMatchIn(this)
}
