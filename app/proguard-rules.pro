# T5.4 — release shrinking rules.
#
# Everything kept here is kept for a reason that is written down. A -keep with no
# stated cause is how a shrink configuration rots into "keep everything".

# Stack traces still have to be readable. There is no crash reporter to upload them
# to (F5.4), but a logcat trace from the user's own device is the only debugging
# signal this app will ever produce, so the line table is worth its few KB.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- SQLCipher (T3.1) -------------------------------------------------------
# libsqlcipher.so resolves its Java counterparts by name through JNI, so R8 cannot
# see the references. Renaming or removing any of this fails at runtime with
# UnsatisfiedLinkError on the first database open — that is, at unlock, after
# biometric auth, with the ledger inaccessible.
-keep class net.zetetic.database.** { *; }
-keep interface net.zetetic.database.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---- Room (T3.1 / T3.4) -----------------------------------------------------
# Room looks up its generated implementation by constructing the class name from
# the abstract database class: AppDatabase -> AppDatabase_Impl. That is reflection
# by string, invisible to the shrinker.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class **_Impl { <init>(...); }

# Migrations are instantiated as anonymous subclasses and only ever reached through
# RoomDatabase.Builder, so nothing statically references them.
-keep class * extends androidx.room.migration.Migration { *; }

-dontwarn androidx.room.paging.**

# ---- androidx.sqlite --------------------------------------------------------
-keep class androidx.sqlite.db.** { *; }

# ---- Kotlin -----------------------------------------------------------------
# Coroutines' internal ServiceLoader wiring; the standard AGP consumer rules cover
# most of it, these two silence what is left.
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
