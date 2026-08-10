package com.arthvault.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Transient feedback, replacing `Toast`.
 *
 * Nine `Toast.makeText` calls carried the app's most consequential messages — how many
 * transactions a scan imported, whether a signed rule file installed, that the vault
 * was wiped. A Toast takes the system's styling rather than the app's, floats over the
 * navigation bar, and cannot carry an action, so "Imported 12 new transactions" had no
 * way to offer "View them".
 */
@Stable
class SnackbarNotifier(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope?,
) {
    fun show(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
        onAction: (() -> Unit)? = null,
    ) {
        if (scope == null) {
            // No provider above this composable. Say so in the log and carry on —
            // see the note on the CompositionLocal below.
            Log.w("SnackbarNotifier", "Dropped snackbar (no VaultScaffold above): $message")
            return
        }
        scope.launch {
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = actionLabel == null && duration == SnackbarDuration.Long,
                duration = duration,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

/**
 * Defaults to a notifier that drops messages rather than one that throws.
 *
 * This previously defaulted to `error("No SnackbarNotifier …")`, which turned a
 * missing provider into a crash — and it found one immediately: the Vault screen's
 * "Delete all data" dialog is a sibling of [VaultScaffold], not a child, so reading
 * the local threw the instant the dialog composed. That structural mistake is fixed
 * at the call site, but the failure mode was wrong either way. A snackbar is
 * transient feedback; failing to show one is never worth taking the app down with
 * it, least of all on the screen whose whole job is deleting the user's data.
 */
val LocalSnackbar = staticCompositionLocalOf {
    SnackbarNotifier(SnackbarHostState(), scope = null)
}

/**
 * The frame every screen sits in.
 *
 * Each screen previously opened with a bare `Scaffold` and a `Text` in `headlineMedium`
 * as the first item of its `LazyColumn`, so the title scrolled away with the content and
 * there was nowhere to put a screen-level action. Four near-identical header blocks were
 * copy-pasted across four files.
 *
 * A `MediumTopAppBar` wired to `exitUntilCollapsedScrollBehavior` gives the large title
 * on arrival and collapses it to a compact bar as the user scrolls — the title stays, the
 * chrome doesn't.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScaffold(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    // Deliberately not exposing the TopAppBarScrollBehavior: it is an experimental
    // type, and putting it in this signature would force every calling screen to
    // opt in to an API none of them actually touch.
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notifier = remember(hostState, scope) { SnackbarNotifier(hostState, scope) }

    CompositionLocalProvider(LocalSnackbar provides notifier) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.background,
            // This Scaffold is nested inside MainActivity's, which already consumed
            // the status-bar and navigation-bar insets before handing this screen its
            // content area. Leaving the defaults on would apply both a second time —
            // a status bar of dead space above every title and a gesture bar of it
            // below every list.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                MediumTopAppBar(
                    title = { Text(title) },
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    colors = TopAppBarDefaults.mediumTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(hostState) },
            floatingActionButton = floatingActionButton,
        ) { padding ->
            content(padding)
        }
    }
}
