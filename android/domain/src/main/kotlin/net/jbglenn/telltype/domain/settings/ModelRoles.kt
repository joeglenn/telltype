package net.jbglenn.telltype.domain.settings

import net.jbglenn.telltype.core.model.settings.ModelRole
import net.jbglenn.telltype.core.model.settings.OfferedModel

/**
 * Which listed models belong in which list (B10). When the server says what a model is for (Telltype Server's
 * `telltype.roles`), each list shows only its role's models. Otherwise the name decides: models whose id or file
 * says speech (`whisper`, `parakeet`, `canary`, `moonshine`, `sensevoice`, `stt`, `asr`, `transcribe`, `speech`)
 * go only to the speech list, everything else only to the text lists; a speech address with no such name keeps
 * its whole list (nothing tells its models apart). A model file listed under two names is shown once, under the
 * first, and "(Recommended)" marks one model per recommended kind.
 */
public object ModelRoles {
    private val SpeechNames =
        Regex("whisper|parakeet|canary|moonshine|sensevoice|transcri|speech|(^|[^a-z])(stt|asr)([^a-z]|$)")

    /** Whether [model]'s id or file name says it is a speech model. */
    public fun isSpeechName(model: OfferedModel): Boolean =
        listOfNotNull(model.id, model.file).any { SpeechNames.containsMatchIn(it.lowercase()) }

    /** The models of [models] that belong in [role]'s list, each model file once. */
    public fun forRole(models: List<OfferedModel>, role: ModelRole): List<OfferedModel> {
        val matching = models.filter { model ->
            model.roles?.let { role in it } ?: (isSpeechName(model) == (role == ModelRole.SPEECH))
        }
        val shown = if (role == ModelRole.SPEECH && matching.isEmpty() && models.none { it.roles != null }) {
            models
        } else {
            matching
        }
        return shown.distinctBy { model -> model.file?.lowercase()?.let { "file:$it" } ?: "id:${model.id}" }
    }

    /** The ids in [models] that carry "(Recommended)" in [role]'s list: the first model of each recommended kind. */
    public fun recommendedIds(models: List<OfferedModel>, role: ModelRole): Set<String> = models
        .mapNotNull { model -> ModelChoices.recommendedKind(model, role)?.let { kind -> kind to model.id } }
        .distinctBy { it.first }
        .map { it.second }
        .toSet()
}
