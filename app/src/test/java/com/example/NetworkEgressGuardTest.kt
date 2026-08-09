package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T1.1 / F5.4 — the zero-egress guarantee, enforced by the build.
 *
 * The app's own privacy claim depends on these assertions holding. Robolectric
 * reads the *merged* manifest, which is what actually ships, rather than the
 * hand-written source manifest — the distinction matters, because a dependency's
 * AAR can merge permissions the source file never mentions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkEgressGuardTest {

    @Test
    fun `merged manifest requests only the permissions the spec sanctions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()

        // App-scoped permissions (androidx adds DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION,
        // namespaced under our own applicationId) grant nothing to anyone else. What
        // must stay locked down is everything outside our namespace.
        val external = declared
            .filterNot { it.startsWith("${context.packageName}.") }
            .toSortedSet()

        // Asserting the whole set, not just INTERNET's absence, so that any new
        // permission — from us or from a dependency — forces a deliberate decision.
        //
        // USE_BIOMETRIC and USE_FINGERPRINT arrive from androidx.biometric's AAR
        // and are required by T3.2's biometric-gated unlock. Both are local to the
        // device's own sensors; neither opens a socket. T6.3's prohibitions
        // (location, contacts, storage) remain unbroken.
        assertEquals(
            sortedSetOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.USE_BIOMETRIC,
                @Suppress("DEPRECATION") Manifest.permission.USE_FINGERPRINT
            ),
            external
        )
    }

    @Test
    fun `SQLCipher does not drag in a networking stack`() {
        // sqlcipher-android statically links OpenSSL's libcrypto. That is the
        // crypto half of OpenSSL, not libssl, and the AAR declares no permission
        // — but "the encryption library added INTERNET" is exactly the class of
        // regression this suite exists to catch, so it is asserted rather than
        // assumed. The permission set test above is the real check; this one
        // makes the intent legible.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()

        assertEquals(
            "SQLCipher must not contribute a network permission",
            emptyList<String>(),
            declared.filter { it.contains("INTERNET") || it.contains("NETWORK_STATE") }
        )
    }

    @Test
    fun `no networking library is on the runtime classpath`() {
        val forbidden = listOf(
            "okhttp3.OkHttpClient",
            "retrofit2.Retrofit",
            "com.google.firebase.FirebaseApp",
            "com.google.firebase.provider.FirebaseInitProvider"
        )

        val present = forbidden.filter { fqcn ->
            try {
                Class.forName(fqcn)
                true
            } catch (expected: ClassNotFoundException) {
                false
            }
        }

        if (present.isNotEmpty()) {
            fail(
                "Networking libraries linked into the app, which breaks T1.1: $present. " +
                    "Their AAR manifests merge android.permission.INTERNET into the APK."
            )
        }
    }

    @Test
    fun `cloud auto-backup is disabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val flags = context.applicationInfo.flags

        // §3 non-goals: no cloud backup. allowBackup=true would make the
        // unencrypted ledger eligible for Google Drive backup.
        assertEquals(
            "android:allowBackup must stay false",
            0,
            flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP
        )
    }
}
