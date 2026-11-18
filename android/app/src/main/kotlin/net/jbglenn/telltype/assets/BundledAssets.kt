package net.jbglenn.telltype.assets

import android.content.res.AssetManager
import java.io.IOException
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.flatMap
import net.jbglenn.telltype.core.common.map
import net.jbglenn.telltype.domain.cleanup.AssetError
import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.cleanup.PromptSet
import net.jbglenn.telltype.domain.cleanup.parseGuardRules
import net.jbglenn.telltype.domain.cleanup.parsePromptSet

/**
 * The parsed `shared/` files (decision D11). The build copies `shared/guards.json` and `shared/prompts/` into the
 * APK's assets under `shared/` (task `copySharedAssets`); nothing from them is written in Kotlin.
 *
 * @property prompts the prompt set.
 * @property guards the guard rules.
 */
internal data class BundledAssets(val prompts: PromptSet, val guards: GuardRules) {
    companion object {
        /** Asset path of the guard rules. */
        const val GUARDS_PATH = "shared/guards.json"

        /** Asset directory of the prompt files. */
        const val PROMPTS_DIRECTORY = "shared/prompts"

        /** Reads and parses both from [assets]. A missing or unreadable file is an [AssetError] for that file. */
        fun load(assets: AssetManager): Outcome<BundledAssets, AssetError> =
            readPrompts(assets).flatMap(::parsePromptSet).flatMap { prompts ->
                read(assets, GUARDS_PATH).flatMap(::parseGuardRules).map { guards -> BundledAssets(prompts, guards) }
            }

        private fun readPrompts(assets: AssetManager): Outcome<Map<String, String>, AssetError> {
            val names = try {
                assets.list(PROMPTS_DIRECTORY).orEmpty().toList()
            } catch (e: IOException) {
                return Outcome.Err(AssetError(PROMPTS_DIRECTORY, "cannot list: ${e::class.simpleName}"))
            }
            val empty: Outcome<Map<String, String>, AssetError> = Outcome.Ok(emptyMap())
            return names.fold(empty) { files, name ->
                files.flatMap { loaded -> read(assets, "$PROMPTS_DIRECTORY/$name").map { loaded + (name to it) } }
            }
        }

        private fun read(assets: AssetManager, path: String): Outcome<String, AssetError> = try {
            Outcome.Ok(assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (e: IOException) {
            Outcome.Err(AssetError(path, "cannot read: ${e::class.simpleName}"))
        }
    }
}

/** Thrown at start-up when the bundled `shared/` files do not parse: a broken build, never a user situation. */
internal class BrokenAssetsException(error: AssetError) :
    IllegalStateException("Bundled ${error.file} does not parse: ${error.problem}")
