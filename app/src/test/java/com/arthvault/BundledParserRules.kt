package com.arthvault

import com.arthvault.data.local.entity.ParserRuleEntity
import com.arthvault.data.parser.rules.ParserRuleJson
import com.arthvault.data.parser.rules.inEvaluationOrder
import com.arthvault.data.parser.rules.toEntity
import java.io.File

/**
 * The rules the shipped APK actually parses with.
 *
 * Since T2.2 there are no built-in patterns behind [com.arthvault.data.parser.SmsParserEngine]
 * — every rule arrives as data. A test that passes no rules therefore parses
 * nothing, and a test that invents its own rules measures something the app does
 * not do. So the parser tests read the same signed asset the app installs, through
 * the same mapper [toEntity] and in the same order the DAO returns.
 *
 * Reads the asset from the source tree rather than the classpath: `src/main/assets`
 * is packaged into the APK, not onto the unit-test classpath. `user.dir` is the
 * module directory under Gradle.
 *
 * Uses `org.json` via [ParserRuleJson], whose plain-JVM stubs throw — callers must
 * run under Robolectric.
 */
object BundledParserRules {

    private val assetFile =
        File(System.getProperty("user.dir")!!, "src/main/assets/parser_rules_v1.json")

    val entities: List<ParserRuleEntity> by lazy {
        ParserRuleJson.parse(assetFile.readText())
            .rules
            .inEvaluationOrder()
            .map { it.toEntity() }
    }
}
