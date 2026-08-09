package com.arthvault.data.parser.rules

import android.content.Context
import com.arthvault.R
import com.arthvault.data.local.dao.AppSettingDao
import com.arthvault.data.local.dao.ParserRuleDao
import com.arthvault.data.local.entity.AppSettingEntity
import com.arthvault.data.local.entity.ParserRuleEntity

/**
 * Applies a signed rule file to the database (T2.2 · 5.2).
 *
 * Runs on **every launch**, not only on database creation. Seeding from the
 * `onCreate` callback — as this used to — means a rule fix only ever reaches a fresh
 * install: everyone who already has the app keeps the broken rules forever, which is
 * precisely the failure mode a versioned rule file exists to prevent.
 *
 * The applied version is recorded in `app_settings`, so an unchanged rule file costs
 * one integer comparison per launch rather than a table rewrite.
 */
class ParserRuleSeeder(
    private val parserRuleDao: ParserRuleDao,
    private val appSettingDao: AppSettingDao,
    private val verifier: RuleAssetVerifier
) {

    sealed interface Outcome {
        /** Rules were written. */
        data class Applied(val rulesVersion: Int, val ruleCount: Int) : Outcome

        /** The file's version is not newer than what is already installed. */
        data class AlreadyCurrent(val rulesVersion: Int) : Outcome

        /**
         * The file was rejected. Whatever rules were already installed stay in place
         * — falling back to no rules would turn a bad file into a total ingestion
         * outage, which is strictly worse than continuing with the last good set.
         */
        data class Rejected(val reason: RuleLoadResult) : Outcome
    }

    suspend fun seedFromAsset(context: Context): Outcome {
        val json = try {
            context.assets.open(BUNDLED_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (missing: Exception) {
            return Outcome.Rejected(
                RuleLoadResult.Malformed("could not read $BUNDLED_ASSET: ${missing.message}")
            )
        }
        return apply(json, allowSameVersion = false)
    }

    /**
     * @param allowSameVersion true for a sideloaded file (5.3). A user who picks a
     *   file explicitly means to install it, even if its `rulesVersion` matches what
     *   is already there — the bundled-asset path, which runs unattended on every
     *   launch, must not do the same or it would rewrite the table every time.
     */
    suspend fun apply(json: String, allowSameVersion: Boolean): Outcome {
        val result = verifier.load(json)
        if (result !is RuleLoadResult.Loaded) return Outcome.Rejected(result)

        val document = result.document
        val installed = installedRulesVersion()

        val isNewer = if (allowSameVersion) {
            document.rulesVersion >= installed
        } else {
            document.rulesVersion > installed
        }
        if (!isNewer) return Outcome.AlreadyCurrent(installed)

        // Read once, before writing anything: reusing an existing row's id makes
        // REPLACE an update rather than a delete-and-insert.
        val existingIds = parserRuleDao.getSystemRules().associate { it.ruleId to it.id }
        val entities = document.rules.map { it.toEntity(existingIds[it.ruleId] ?: 0L) }

        // Upsert by ruleId, then withdraw system rules this file no longer lists.
        // User-authored rules are untouched by both steps.
        parserRuleDao.insertAllRules(entities)
        parserRuleDao.deleteSystemRulesNotIn(entities.map { it.ruleId })

        appSettingDao.put(
            AppSettingEntity(KEY_RULES_VERSION, document.rulesVersion.toString())
        )

        return Outcome.Applied(document.rulesVersion, entities.size)
    }

    private suspend fun installedRulesVersion(): Int =
        appSettingDao.get(KEY_RULES_VERSION)?.toIntOrNull() ?: 0

    companion object {
        const val BUNDLED_ASSET = "parser_rules_v1.json"

        /** `app_settings` key holding the `rulesVersion` currently installed. */
        const val KEY_RULES_VERSION = "parser_rules_version"

        /** The verifier configured with the public key compiled into the APK. */
        fun bundledVerifier(context: Context): RuleAssetVerifier =
            RuleAssetVerifier(
                context.resources.openRawResource(R.raw.parser_rules_public_key)
                    .use { it.readBytes() }
            )
    }
}
