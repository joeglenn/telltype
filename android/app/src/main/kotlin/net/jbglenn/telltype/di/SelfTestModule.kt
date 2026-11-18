package net.jbglenn.telltype.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.jbglenn.telltype.domain.port.SelfTest
import net.jbglenn.telltype.support.SampleSelfTest

/** The Diagnostics self-test with the bundled sample, the same in debug and release builds. */
@Module
@InstallIn(SingletonComponent::class)
internal interface SelfTestModule {
    @Binds
    fun bindSelfTest(test: SampleSelfTest): SelfTest
}
