package com.example.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.data.repository.PendingIngestMarker

/**
 * Deferred ingestion (T3.2, T5.3).
 *
 * This receiver used to parse each message and write it straight to the
 * database, wrapped in goAsync() to survive the process being killed. That is
 * impossible now, and would be the wrong thing to do even if it weren't:
 *
 *  - The database key is auth-bound. While the phone is locked the Keystore
 *    refuses to unwrap it, so there is no ledger to write to.
 *  - Parsing on every incoming SMS wakes the CPU for messages that are mostly
 *    OTPs and promotions, on a schedule set by other people's marketing
 *    departments. Nothing here is time-critical: the ledger is read when the
 *    user opens the app, and analysis is recomputed then.
 *
 * So the work is deferred wholesale. Android's SMS inbox already stores every
 * message durably; it is the queue, and it costs nothing. All this does is note
 * that something arrived, which takes one SharedPreferences write and lets the
 * device go straight back to sleep. Ingestion happens at unlock, from the
 * watermark, in SmsRepository.ingestNewMessages().
 *
 * No goAsync(), no coroutine, no wakelock, no WorkManager job, no alarm.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val arrivedAt = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        PendingIngestMarker.mark(context, arrivedAt)
    }
}
