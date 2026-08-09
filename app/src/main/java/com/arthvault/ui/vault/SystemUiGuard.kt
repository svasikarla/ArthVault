package com.arthvault.ui.vault

/**
 * Marks the window in which the app has handed control to a system activity it
 * launched itself — the document picker for a backup, the share sheet for an
 * export.
 *
 * Those stop MainActivity exactly like the user pressing Home does, and the lock
 * policy cannot tell the difference on its own. Locking there would close the
 * database in the middle of the operation the user just asked for, and the
 * result would arrive with nowhere to write it.
 *
 * The flag is cleared unconditionally on resume rather than only by the result
 * callback, so a launcher that somehow never reports back cannot leave the vault
 * unlockable in the background for the rest of the process's life.
 */
object SystemUiGuard {

    @Volatile
    private var active = false

    val isActive: Boolean get() = active

    fun enter() {
        active = true
    }

    fun reset() {
        active = false
    }
}
