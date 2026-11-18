package net.jbglenn.telltype.buildlogic

/**
 * The layer rule from docs/PLAN.md section 6.2, as data. Every module must be listed in [layerOf];
 * a new module that is not classified fails the build until it is added here.
 */
internal object ModuleDependencyRules {
    private const val TESTING = ":testing"
    private const val DESIGN_SYSTEM = ":ui:designsystem"
    private const val CORE_MODEL = ":core:model"
    private const val CORE_COMMON = ":core:common"
    private val adapters = setOf(":data", ":network", ":audio", ":platform")

    enum class Layer { CORE, DOMAIN, ADAPTER, UI, TESTING, APP, TEST_HOST }

    fun layerOf(path: String): Layer? = when {
        path.startsWith(":core:") -> Layer.CORE
        path == ":domain" -> Layer.DOMAIN
        path in adapters -> Layer.ADAPTER
        path.startsWith(":ui:") -> Layer.UI
        path == TESTING -> Layer.TESTING
        path == ":app" -> Layer.APP
        path == ":testhost" -> Layer.TEST_HOST
        else -> null
    }

    /** Returns why [from] may not declare [to] in a production configuration, or null if it may. */
    fun productionViolation(from: String, to: String): String? {
        val fromLayer = layerOf(from) ?: return "$from is not classified in ModuleDependencyRules.layerOf"
        val toLayer = layerOf(to) ?: return "$to is not classified in ModuleDependencyRules.layerOf"
        val allowed = when (fromLayer) {
            Layer.CORE -> from == CORE_COMMON && to == CORE_MODEL
            Layer.DOMAIN -> toLayer == Layer.CORE
            Layer.ADAPTER -> toLayer == Layer.CORE || toLayer == Layer.DOMAIN
            Layer.UI -> toLayer == Layer.CORE || toLayer == Layer.DOMAIN || to == DESIGN_SYSTEM
            Layer.TESTING -> toLayer == Layer.CORE || toLayer == Layer.DOMAIN
            Layer.APP -> toLayer != Layer.APP && toLayer != Layer.TEST_HOST && toLayer != Layer.TESTING
            Layer.TEST_HOST -> toLayer == Layer.CORE || to == DESIGN_SYSTEM
        }
        return if (allowed) null else ruleText(fromLayer)
    }

    /** Test configurations may additionally use the shared fakes in :testing. */
    fun testViolation(from: String, to: String): String? = if (to == TESTING && from != TESTING) {
        null
    } else {
        productionViolation(from, to)
    }

    private fun ruleText(layer: Layer): String = when (layer) {
        Layer.CORE -> ":core:* depend on nothing in the project, except :core:common -> :core:model"

        Layer.DOMAIN -> ":domain depends only on :core:*"

        Layer.ADAPTER ->
            ":data, :network, :audio and :platform depend only on :domain and :core:*, " +
                "never on each other or on :ui:*"

        Layer.UI -> ":ui:* depend only on :domain, :core:* and :ui:designsystem"

        Layer.TESTING -> ":testing depends only on :domain and :core:*"

        Layer.APP -> ":app may depend on every library module, but not on :testing or on another application"

        Layer.TEST_HOST -> ":testhost depends only on :core:* and :ui:designsystem"
    }
}
