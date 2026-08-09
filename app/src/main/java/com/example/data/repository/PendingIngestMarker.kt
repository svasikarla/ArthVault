package com.example.data.repository

import android.content.Context

/**
 * The only thing the SMS receiver is allowed to write while the vault is locked.
 *
 * With an auth-bound database key (T3.2) the receiver *cannot* open the ledger
 * when the phone is locked, and building a plaintext holding pen for message
 * bodies would hand back exactly the unencrypted store that encryption removed.
 *
 * So nothing about the message is stored. This records only a count and the
 * arrival time, which is enough to tell the user "3 messages arrived while you
 * were away" on the unlock screen. The messages themselves stay where Android
 * already keeps them — the SMS inbox is the queue, and reading it back costs
 * nothing until the user opens the app.
 */
object PendingIngestMarker {

    private const val PREFS = "pending_ingest"
    private const val KEY_COUNT = "count"
    private const val KEY_SINCE = "since"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Called from the broadcast receiver. Two integers and a commit — a few
     * hundred microseconds, no wakelock, no coroutine, no database. The receiver
     * returns immediately and the device goes straight back to sleep.
     */
    fun mark(context: Context, arrivedAt: Long) {
        val p = prefs(context)
        val since = p.getLong(KEY_SINCE, 0L)
        p.edit()
            .putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1)
            .putLong(KEY_SINCE, if (since == 0L) arrivedAt else minOf(since, arrivedAt))
            .apply()
    }

    fun pendingCount(context: Context): Int = prefs(context).getInt(KEY_COUNT, 0)

    fun pendingSince(context: Context): Long = prefs(context).getLong(KEY_SINCE, 0L)

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
