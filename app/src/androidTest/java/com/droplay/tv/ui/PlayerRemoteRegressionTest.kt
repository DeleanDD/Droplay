package com.droplay.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.droplay.tv.data.MediaEntry
import com.droplay.tv.data.MediaKind
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class PlayerRemoteRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun centerWorksAfterControlsHideAndTimelineKeepsDirectionalFocus() {
        // Local PCM audio provides a real seekable Media3 timeline without a provider/network.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("remote-test", ".wav", context.cacheDir)
        val size = 8_000 * 2 * 120
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
        file.outputStream().use { it.write(header); it.write(ByteArray(size)) }
        var savedPosition = 0L
        val showPlayer = mutableStateOf(true)
        try {
            compose.setContent {
                DroplayTheme { if (showPlayer.value) PlayerScreen(MediaEntry("test", "Teste", file.toURI().toString(), MediaKind.MOVIE),
                    0, false, {}, { position, _ -> savedPosition = position }, {}) }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Barra de tempo").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(5_000)
            compose.waitForIdle()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitUntil(3_000) { compose.onAllNodesWithText("Pausado").fetchSemanticsNodes().isNotEmpty() }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.onNodeWithContentDescription("Reproduzir").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionUp) }
            compose.onNodeWithContentDescription("Barra de tempo").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionCenter) }
            compose.waitUntil(3_000) { compose.onAllNodesWithText("Reproduzindo").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Barra de tempo").assertIsFocused()
            compose.runOnIdle { showPlayer.value = false }
            compose.waitForIdle()
            assertTrue("Right must advance the actual Media3 timeline", savedPosition >= 29_000)
        } finally { file.delete() }
    }
}
