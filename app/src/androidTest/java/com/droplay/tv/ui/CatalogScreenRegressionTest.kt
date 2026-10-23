package com.droplay.tv.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.input.key.Key
import com.droplay.tv.AppState
import com.droplay.tv.data.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CatalogScreenRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val movie = MediaEntry("movie:1", "Missão: Impossível", "", MediaKind.MOVIE, "Ação", year = 2026, addedAt = 100)

    @Test fun liveStartsWithChannelsWhenFavoritesAreEmpty() {
        val live = movie.copy(id = "live:1", name = "Canal de teste", kind = MediaKind.LIVE)
        val state = AppState(preparedCatalog = CatalogOrganizer.prepare(listOf(live), false, false))
        compose.setContent { DroplayTheme { CategoryContent(Section.LIVE, state, "", {}, null, {}, {}, {}) } }
        compose.onNodeWithText(live.name).assertExists()
    }

    @Test fun movieStartsOnPlayAndDirectionalKeysReachFavorite() {
        compose.setContent { DroplayTheme { MovieDetail(movie, null, false, true, {}, {}, {}) } }
        compose.waitUntil(3_000) { compose.onAllNodes(hasText("▶  Assistir") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("▶  Assistir").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("+  Minha lista").assertIsFocused()
    }

    @Test fun seriesStartsOnPlayAndDownReachesSeasonThenEpisode() {
        val series = movie.copy(kind = MediaKind.SERIES, seriesId = "1")
        val episode = movie.copy(id = "episode:1", name = "Piloto", season = 1, episode = 1)
        compose.setContent { DroplayTheme { SeriesDetail(series, listOf(episode), false, AppState(), true, {}, {}, {}) } }
        compose.waitUntil(3_000) { compose.onAllNodes(hasText("▶  Assistir") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("▶  Assistir").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Temporada 1").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("1. Piloto") and hasClickAction()).assertIsFocused()
    }

    @Test fun completedIndexesReplaceInitialEmptyTabsWithoutChangingEntries() {
        val entries = listOf(movie)
        val state = mutableStateOf(AppState(preparedCatalog = CatalogOrganizer.prepareInitial(entries)))
        compose.setContent {
            DroplayTheme { CategoryContent(Section.MOVIES, state.value, "", {}, null, {}, {}, {}) }
        }
        compose.runOnIdle { state.value = state.value.copy(preparedCatalog = CatalogOrganizer.prepare(entries, false, false)) }
        compose.onAllNodesWithText("Ação").onFirst().assertExists()
        compose.onNodeWithText(movie.name).assertExists()
    }

    @Test fun typingShowsMatchingMovieAndClearingRemovesResults() {
        val query = mutableStateOf("")
        val state = AppState(preparedCatalog = CatalogOrganizer.prepare(listOf(movie), false, false))
        compose.setContent {
            DroplayTheme { CategoryContent(Section.SEARCH, state, query.value, { query.value = it }, "Ação", {}, {}, {}) }
        }
        compose.onNode(hasSetTextAction()).performTextInput("missao impossivel")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(movie.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(movie.name).assertExists()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(movie.name).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Digite ao menos 3 caracteres").assertExists()
    }
}
