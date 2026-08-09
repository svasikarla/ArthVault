package com.arthvault.ui.vault

import android.app.Application
import android.security.keystore.UserNotAuthenticatedException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthvault.data.crypto.DatabaseKeyManager
import com.arthvault.data.crypto.VaultCrypto
import com.arthvault.data.crypto.VaultKeyInvalidatedException
import com.arthvault.data.local.AppDatabase
import com.arthvault.data.repository.PendingIngestMarker
import com.arthvault.data.repository.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface VaultState {
    /** No screen lock is set, so an auth-bound key cannot exist. */
    data object NeedsDeviceLock : VaultState

    data class Locked(val message: String? = null) : VaultState

    data object Unlocking : VaultState

    data class Unlocked(val ingested: Int = 0, val scanned: Int = 0) : VaultState

    /** The Keystore key is gone; the ledger is unrecoverable without a backup. */
    data object KeyInvalidated : VaultState
}

/**
 * Owns the lock/unlock lifecycle of the encrypted ledger (T3.2).
 *
 * The unlock sequence is: BiometricPrompt succeeds -> the Keystore will now
 * unwrap the database passphrase -> Room opens the SQLCipher file -> the app
 * catches up on SMS that arrived while it was locked. Nothing before the first
 * step can touch a single row.
 */
class VaultSessionViewModel(app: Application) : AndroidViewModel(app) {

    private val keyManager = DatabaseKeyManager(app)

    private val _state = MutableStateFlow<VaultState>(
        if (keyManager.isDeviceSecure()) VaultState.Locked() else VaultState.NeedsDeviceLock
    )
    val state: StateFlow<VaultState> = _state.asStateFlow()

    /** How many messages arrived while the vault was locked, for the lock screen. */
    val pendingCount: Int get() = PendingIngestMarker.pendingCount(getApplication())

    val isFirstRun: Boolean get() = !keyManager.isInitialised()

    fun recheckDeviceLock() {
        if (_state.value is VaultState.NeedsDeviceLock && keyManager.isDeviceSecure()) {
            _state.value = VaultState.Locked()
        }
    }

    /**
     * Called from the activity once BiometricPrompt has reported success. The
     * Keystore operation must happen within the key's authentication validity
     * window, so this runs immediately rather than waiting for a user action.
     */
    fun onAuthenticationSucceeded() {
        if (_state.value is VaultState.Unlocked || _state.value is VaultState.Unlocking) return
        _state.value = VaultState.Unlocking

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val passphrase = keyManager.unlockPassphrase()
                    try {
                        AppDatabase.open(getApplication(), passphrase)
                    } finally {
                        VaultCrypto.wipe(passphrase)
                    }
                }

                // T2.2 / 5.2 — apply the bundled rule file before reading anything.
                //
                // This runs on every unlock, not only on database creation. Seeding
                // from Room's onCreate callback meant a rule fix reached fresh
                // installs only: anyone who already had the app kept the broken rules
                // forever, which is the exact failure a versioned rule file exists to
                // prevent. An unchanged rulesVersion costs one integer comparison.
                //
                // A rejected file leaves the installed rules alone and is not fatal —
                // ingestion continuing with the last known-good rules beats refusing
                // to open the ledger.
                withContext(Dispatchers.IO) {
                    SmsRepository(getApplication()).applyBundledParserRules()
                }

                // Deferred ingestion: everything that arrived while locked is read
                // now, once, from the OS inbox.
                val result = withContext(Dispatchers.IO) {
                    SmsRepository(getApplication()).ingestNewMessages()
                }
                _state.value = VaultState.Unlocked(
                    ingested = result.newTransactionsCount,
                    scanned = result.totalScanned
                )
            } catch (e: UserNotAuthenticatedException) {
                // The validity window lapsed between the prompt and here.
                _state.value = VaultState.Locked("Authentication expired. Please try again.")
            } catch (e: VaultKeyInvalidatedException) {
                _state.value = VaultState.KeyInvalidated
            } catch (e: Exception) {
                _state.value = VaultState.Locked(e.message ?: "The vault could not be opened.")
            }
        }
    }

    fun onAuthenticationFailed(message: String?) {
        if (_state.value is VaultState.Unlocked) return
        _state.value = VaultState.Locked(message)
    }

    fun lock() {
        AppDatabase.lock()
        if (_state.value !is VaultState.NeedsDeviceLock && _state.value !is VaultState.KeyInvalidated) {
            _state.value = VaultState.Locked()
        }
    }

    /**
     * The only exit from [VaultState.KeyInvalidated]: throw away the unreadable
     * file and the dead key, then start over. Destructive and labelled as such in
     * the UI — the alternative is an app that can never open again.
     */
    fun discardUnreadableVault() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.deleteDatabaseFiles(getApplication())
                keyManager.destroyVaultKey()
                PendingIngestMarker.clear(getApplication())
            }
            _state.value = VaultState.Locked("The old vault was discarded. Unlock to start a new one.")
        }
    }
}
