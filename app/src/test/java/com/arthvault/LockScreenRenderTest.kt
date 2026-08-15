package com.arthvault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.arthvault.ui.screens.LockScreen
import com.arthvault.ui.theme.MyApplicationTheme
import com.arthvault.ui.vault.VaultState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T3.2 — the one screen every user sees before anything else, and the one with no
 * way back from a layout failure.
 *
 * It carries two layouts behind one entry point: a full introduction on first run,
 * and a turnstile on every unlock after. Both have to survive a short viewport and a
 * 2× font scale, because the first-run copy is long enough to overflow either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LockScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(
        state: VaultState = VaultState.Locked(),
        isFirstRun: Boolean = false,
        pendingCount: Int = 0,
        dark: Boolean = false,
        fontScale: Float = 1f
    ) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale)
            ) {
                MyApplicationTheme(darkTheme = dark) {
                    LockScreen(
                        state = state,
                        pendingCount = pendingCount,
                        isFirstRun = isFirstRun,
                        onUnlock = {},
                        onOpenSecuritySettings = {},
                        onDiscardVault = {}
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // --- first run ---------------------------------------------------------

    @Test
    fun `first run introduces the app and offers to create the vault`() {
        render(isFirstRun = true)
        compose.onNodeWithText("Arth Vault").assertIsDisplayed()
        compose.onNodeWithText("Your bank SMS, turned into a private ledger").assertIsDisplayed()
        compose.onNodeWithText("Create vault").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `first run states all four things the app does`() {
        render(isFirstRun = true)
        listOf(
            "Builds itself from your inbox",
            "Shows where the money went",
            "Encrypted, and opened only by you",
            "Cannot reach the internet"
        ).forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun `the long first-run layout still reaches its button at 2x font scale`() {
        // The case that made this screen scrollable: centred, unscrollable content
        // clips from both ends, and a clipped "Create vault" is an app that cannot
        // be started at all.
        render(isFirstRun = true, fontScale = 2f)
        compose.onNodeWithText("Create vault").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `first run composes in dark theme`() {
        render(isFirstRun = true, dark = true)
        compose.onNodeWithText("Cannot reach the internet").performScrollTo().assertIsDisplayed()
    }

    // --- returning ---------------------------------------------------------

    @Test
    fun `an unlock is not made to sit through the introduction again`() {
        render(isFirstRun = false)
        compose.onNodeWithText("Arth Vault").assertIsDisplayed()
        compose.onNodeWithText("Unlock").assertIsDisplayed()
        compose.onNodeWithText("Your ledger is encrypted. Authenticate to unlock it.")
            .assertIsDisplayed()

        // Absent from the composition, not merely scrolled out of view: a daily
        // turnstile is the wrong place for a feature tour.
        compose.onNodeWithText("Builds itself from your inbox").assertDoesNotExist()
        compose.onNodeWithText("Cannot reach the internet").assertDoesNotExist()
    }

    @Test
    fun `messages that arrived while away are counted on the gate`() {
        render(isFirstRun = false, pendingCount = 3)
        compose.onNodeWithText("3 messages arrived while you were away").assertIsDisplayed()
    }

    @Test
    fun `a failed attempt shows its reason`() {
        render(state = VaultState.Locked("Authentication failed"))
        compose.onNodeWithText("Authentication failed").assertIsDisplayed()
    }

    // --- the other three states --------------------------------------------

    @Test
    fun `a device with no screen lock is told why it cannot proceed`() {
        render(state = VaultState.NeedsDeviceLock)
        compose.onNodeWithText("A screen lock is required").assertIsDisplayed()
        compose.onNodeWithText("Open security settings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an invalidated key offers the only remaining action`() {
        render(state = VaultState.KeyInvalidated, fontScale = 2f)
        compose.onNodeWithText("The vault key is gone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Discard the unreadable vault").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `unlocking says what it is doing`() {
        render(state = VaultState.Unlocking)
        compose.onNodeWithText("Opening the ledger and catching up on new messages…")
            .assertIsDisplayed()
    }
}
