package net.jbglenn.telltype.di

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Proves the Hilt graph builds and binds the real dispatchers, including the two single-thread ones. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class DispatchersModuleTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var dispatchers: DispatcherProvider

    @Inject
    lateinit var sameDispatchers: DispatcherProvider

    @Before
    fun inject() {
        hilt.inject()
    }

    @Test
    fun bindsRealDispatchers() {
        assertSame(Dispatchers.Main, dispatchers.main)
        assertSame(Dispatchers.IO, dispatchers.io)
        assertSame(Dispatchers.Default, dispatchers.default)
    }

    @Test
    fun providerIsASingleton() {
        assertSame(dispatchers, sameDispatchers)
    }

    @Test
    fun accessibilityWorkRunsOnOneNamedThread() {
        val threads = runBlocking {
            (1..TASKS).map { withContext(dispatchers.accessibility) { Thread.currentThread() } }
        }
        assertEquals(AndroidDispatcherProvider.ACCESSIBILITY_THREAD, threadName(threads.first()))
        assertEquals("one thread for every task", 1, threads.distinct().size)
    }

    @Test
    fun audioWorkRunsOnOneNamedThread() {
        val threads = runBlocking {
            (1..TASKS).map { withContext(dispatchers.audio) { Thread.currentThread() } }
        }
        assertEquals(AndroidDispatcherProvider.AUDIO_THREAD, threadName(threads.first()))
        assertEquals("one thread for every task", 1, threads.distinct().size)
    }

    /**
     * [thread]'s own name. With coroutine debugging on (as under Gradle's test JVM with assertions enabled) a thread
     * running a coroutine carries ` @coroutine#N` after its name until the coroutine leaves it, so the suffix is
     * dropped; everything before it must still be exactly the dispatcher's name.
     */
    private fun threadName(thread: Thread): String = thread.name.substringBefore(COROUTINE_SUFFIX)

    private companion object {
        const val TASKS = 3
        const val COROUTINE_SUFFIX = " @coroutine#"
    }
}
