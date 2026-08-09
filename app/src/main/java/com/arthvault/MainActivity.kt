package com.arthvault

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arthvault.ui.screens.AnalyticsScreen
import com.arthvault.ui.screens.IngestionScreen
import com.arthvault.ui.screens.LedgerScreen
import com.arthvault.ui.screens.LockScreen
import com.arthvault.ui.screens.VaultScreen
import com.arthvault.ui.theme.MyApplicationTheme
import com.arthvault.ui.vault.SystemUiGuard
import com.arthvault.ui.vault.VaultSessionViewModel
import com.arthvault.ui.vault.VaultState
import com.arthvault.ui.viewmodel.AnalyticsViewModel
import com.arthvault.ui.viewmodel.LedgerViewModel
import com.arthvault.ui.viewmodel.VaultViewModel

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Ledger : Screen("ledger", "Ledger", Icons.Default.ReceiptLong)
    object Analytics : Screen("analytics", "Analytics", Icons.Default.Analytics)
    object Ingestion : Screen("ingestion", "Rules & Parse", Icons.Default.Input)
    object Vault : Screen("vault", "Vault & Privacy", Icons.Default.Shield)
}

/**
 * A FragmentActivity rather than a ComponentActivity because BiometricPrompt
 * needs a fragment manager to host its dialog.
 */
class MainActivity : FragmentActivity() {

    private lateinit var session: VaultSessionViewModel

    /**
     * A ViewModel store for the ledger screens, separate from the activity's own.
     *
     * The screens' ViewModels each build an SmsRepository, which resolves the
     * open AppDatabase and holds DAO Flows derived from it. Locking closes that
     * database. Activity-scoped ViewModels survive onStop, so after a lock/unlock
     * cycle they would still be pointed at the closed instance — the ledger kept
     * rendering its last cached list while every new query silently returned
     * nothing. Clearing this store on lock guarantees the next unlock builds
     * everything against the database it actually opened.
     *
     * VaultSessionViewModel deliberately stays in the activity's own store: it
     * owns the lock state and must not be destroyed by the locking it performs.
     */
    private val ledgerViewModelStore = ViewModelStore()

    private val ledgerStoreOwner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore: ViewModelStore get() = ledgerViewModelStore

        // Supplying the store alone is not enough: without a factory the default
        // provider looks for a no-arg constructor, and every ViewModel here is an
        // AndroidViewModel that takes the Application.
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory
            get() = ViewModelProvider.AndroidViewModelFactory.getInstance(application)

        override val defaultViewModelCreationExtras: CreationExtras
            get() = MutableCreationExtras().apply {
                set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, application)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keeps the ledger out of the recents thumbnail and out of screenshots.
        //
        // Debug builds are exempt: FLAG_SECURE makes `adb shell screencap` return
        // a black frame, and being unable to look at the running app is a poor
        // trade for a protection that only matters on a real user's device.
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        setContent {
            MyApplicationTheme {
                session = viewModel()
                val state by session.state.collectAsStateWithLifecycle()

                if (state is VaultState.Unlocked) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides ledgerStoreOwner) {
                        VaultLedgerApp()
                    }
                } else {
                    LockScreen(
                        state = state,
                        pendingCount = session.pendingCount,
                        isFirstRun = session.isFirstRun,
                        onUnlock = ::promptForUnlock,
                        onOpenSecuritySettings = {
                            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        },
                        onDiscardVault = session::discardUnreadableVault
                    )
                }
            }
        }
    }

    private val awaitingSystemUi: Boolean get() = SystemUiGuard.isActive

    override fun onStart() {
        super.onStart()
        if (::session.isInitialized) session.recheckDeviceLock()
    }

    override fun onResume() {
        super.onResume()
        SystemUiGuard.reset()
    }

    /**
     * Re-locks whenever the app leaves the foreground.
     *
     * There is no grace period on purpose. Reopening costs milliseconds, and the
     * point of an auth-bound key is that the ledger is readable only while
     * someone who authenticated is looking at it.
     *
     * The exception is a system activity *we* launched — the document picker for
     * a backup, the share sheet for an export, the credential prompt itself.
     * Those stop the activity too, and locking there would close the database out
     * from under the very operation the user asked for. [awaitingSystemUi] is set
     * by the launcher and cleared when the result lands.
     */
    override fun onStop() {
        super.onStop()
        if (::session.isInitialized && !isChangingConfigurations && !awaitingSystemUi) {
            session.lock()
            ledgerViewModelStore.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ledgerViewModelStore.clear()
    }

    private fun promptForUnlock() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                session.onAuthenticationSucceeded()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val silent = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                session.onAuthenticationFailed(if (silent) null else errString.toString())
            }
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Arth Vault")
            .setSubtitle("Your ledger is encrypted on this device")
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else {
                    // BIOMETRIC_STRONG or DEVICE_CREDENTIAL is not supported
                    // together below API 30; this is the documented equivalent.
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                }
            }
            .build()

        BiometricPrompt(this, executor, callback).authenticate(info)
    }
}

@Composable
fun VaultLedgerApp() {
    val navController = rememberNavController()
    val ledgerViewModel: LedgerViewModel = viewModel()
    val analyticsViewModel: AnalyticsViewModel = viewModel()
    val vaultViewModel: VaultViewModel = viewModel()

    val screens = listOf(
        Screen.Ledger,
        Screen.Analytics,
        Screen.Ingestion,
        Screen.Vault
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier.testTag("nav_item_${screen.route}")
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Ledger.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Ledger.route) {
                LedgerScreen(viewModel = ledgerViewModel)
            }
            composable(Screen.Analytics.route) {
                AnalyticsScreen(viewModel = analyticsViewModel)
            }
            composable(Screen.Ingestion.route) {
                IngestionScreen(ledgerViewModel = ledgerViewModel, vaultViewModel = vaultViewModel)
            }
            composable(Screen.Vault.route) {
                VaultScreen(viewModel = vaultViewModel)
            }
        }
    }
}
