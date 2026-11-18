package net.jbglenn.telltype.core.model

import java.io.File
import net.jbglenn.telltype.core.model.cleanup.Guard
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The enum ids are the keys of the shared files (`shared/guards.json`, `shared/prompts/tones.json`,
 * `shared/prompts/categories.json`). Only names are compared, never values: those belong to the shared files.
 */
class SharedStructureTest {
    private val shared = File(System.getProperty("telltype.sharedDir"))

    @Test
    fun `guards run in the order guards json lists them`() {
        val order = Regex("\"order\"\\s*:\\s*\\[([^]]*)]").find(read("guards.json"))!!.groupValues[1]

        assertEquals(quotedStrings(order), Guard.entries.map { it.id })
    }

    @Test
    fun `tone ids are the keys of tones json`() {
        assertEquals(objectKeys(read("prompts/tones.json"), "tones"), Tone.entries.map { it.id }.toSet())
    }

    @Test
    fun `category ids are the keys of categories json`() {
        assertEquals(
            objectKeys(read("prompts/categories.json"), "categories"),
            AppCategory.entries.map { it.id }.toSet(),
        )
    }

    private fun read(path: String): String = shared.resolve(path).readText()

    private fun quotedStrings(text: String): List<String> = Regex("\"([^\"]+)\"").findAll(text).map {
        it.groupValues[1]
    }.toList()

    /** Keys of the flat JSON object [name] (its values are strings, so the first `}` closes it). */
    private fun objectKeys(json: String, name: String): Set<String> {
        val body = Regex("\"$name\"\\s*:\\s*\\{([^}]*)}").find(json)!!.groupValues[1]
        return Regex("\"([^\"]+)\"\\s*:").findAll(body).map { it.groupValues[1] }.toSet()
    }
}
