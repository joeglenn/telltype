package net.jbglenn.telltype.di

import javax.inject.Qualifier

/**
 * The speech engine and cleaner the session coordinator and the self-test use. Release builds bind the remote ones
 * from `:network`; debug builds bind a switch that uses the fake engines while Settings > Advanced > "Use fake
 * engines" is on. The pending-job worker always uses the remote engine.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class PipelineEngine
