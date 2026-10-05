package com.droplay.tv.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.droplay.tv.AppState
import com.droplay.tv.data.*
import org.junit.Rule
import org.junit.Test

class CatalogScreenRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val movie = MediaEntry("movie:1", "Missão: Impossível", "", MediaKind.MOVIE, "Ação", year = 2026, addedAt = 100)

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
