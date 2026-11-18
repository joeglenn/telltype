package net.jbglenn.telltype.domain

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.cleanup.PromptSet
import net.jbglenn.telltype.domain.cleanup.parseGuardRules
import net.jbglenn.telltype.domain.cleanup.parsePromptSet

/** The real `shared/` files (directory from the `telltype.sharedDir` system property set by the build). */
object SharedAssets {
    private val dir = File(checkNotNull(System.getProperty("telltype.sharedDir")) { "telltype.sharedDir not set" })

    /** The files in `shared/prompts`, by file name. */
    val promptFiles: Map<String, String> by lazy {
        File(dir, "prompts").listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readText() }
    }

    /** `shared/guards.json`. */
    val guardsJson: String by lazy { File(dir, "guards.json").readText() }

    /** The parsed guard rules. */
    val rules: GuardRules by lazy { (parseGuardRules(guardsJson) as Outcome.Ok).value }

    /** The parsed prompt set. */
    val prompts: PromptSet by lazy { (parsePromptSet(promptFiles) as Outcome.Ok).value }
}

/** The conformance vectors written by `eval/export_conformance.py` (test resources `conformance/`). */
object Vectors {
    /** One vector file. */
    fun load(name: String): JsonObject {
        val text = checkNotNull(Vectors::class.java.getResource("/conformance/$name")) { "missing $name" }.readText()
        return Json.parseToJsonElement(text).jsonObject
    }
}

/** The string value of this member (null for JSON null). */
fun JsonObject.string(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

/** The string value of a required member. */
fun JsonObject.text(key: String): String = checkNotNull(string(key)) { "$key is null" }

/** The string items of an array member. */
fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

/** The array items of an array member. */
fun JsonObject.items(key: String): List<JsonObject> = (getValue(key) as JsonArray).map(JsonElement::jsonObject)

/**
 * Collects mismatches between vectors and the Kotlin result, so one run lists every failing vector instead of
 * stopping at the first.
 */
class Mismatches {
    private val found = mutableListOf<String>()

    /** Number of comparisons made. */
    var checked: Int = 0
        private set

    /** Records a comparison of [expected] and [actual] for vector [id]. */
    fun compare(id: String, what: String, expected: Any?, actual: Any?) {
        checked++
        if (expected != actual) found += "$id $what:\n  expected: $expected\n  actual:   $actual"
    }

    /** Fails with the first mismatches when there are any. */
    fun assertNone() {
        check(found.isEmpty()) { "${found.size} of $checked comparisons differ:\n" + found.take(20).joinToString("\n") }
    }
}
