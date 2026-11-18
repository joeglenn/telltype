package net.jbglenn.telltype.ui.screens.common

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Instant
import net.jbglenn.telltype.core.common.Clock

/** The time and time zone the screens show dates in. Tests use a fixed one. */
interface LocalTime {
    /** Wall-clock time now. */
    fun now(): Instant

    /** The zone dates are shown in. */
    fun zone(): ZoneId
}

/** The device clock (the injected [Clock]) and the device's time zone. */
internal class SystemLocalTime @Inject constructor(private val clock: Clock) : LocalTime {
    override fun now(): Instant = clock.now()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** Binds the screens' own implementations. */
@Module
@InstallIn(SingletonComponent::class)
internal interface ScreensModule {
    @Binds
    fun bindLocalTime(time: SystemLocalTime): LocalTime
}
