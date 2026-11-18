package net.jbglenn.telltype.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.getOrNull
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.data.SnippetId
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.data.db.ExclusionSeed
import net.jbglenn.telltype.data.inMemoryDatabase
import net.jbglenn.telltype.data.testDispatchers
import net.jbglenn.telltype.testing.FixedClock
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomSnippetAndAppRuleRepositoryTest {
    private val logger = RecordingLogger()

    @Test
    fun `snippets get ids, are sorted by trigger and follow the update and delete rules`() = runTest {
        val repository = RoomSnippetRepository(inMemoryDatabase().snippets(), FixedClock(), testDispatchers(), logger)
        val address = repository.add("my address", "1 Example Street").getOrNull()!!
        val sign = repository.add("Best wishes", "Best wishes, J.").getOrNull()!!

        assertEquals(Fixtures.Epoch, address.createdAt)
        assertEquals(listOf(sign, address), repository.snippets.first())
        assertEquals(Outcome.Ok(Unit), repository.update(address.copy(text = "2 Example Street")))
        assertEquals(Outcome.Err(StorageFailed), repository.update(address.copy(id = SnippetId(500))))
        assertEquals(Outcome.Ok(Unit), repository.recordUse(address.id))
        assertEquals(Outcome.Ok(Unit), repository.recordUse(SnippetId(500)))
        assertEquals(1, repository.snippets.first().first { it.id == address.id }.uses)
        assertEquals(Outcome.Ok(Unit), repository.delete(SnippetId(500)))
        assertEquals(Outcome.Ok(Unit), repository.delete(sign.id))
        assertEquals(listOf(address.id), repository.snippets.first().map { it.id })
        assertFalse("snippet text never logged", logger.entries.any { "Example Street" in it.message })
    }

    @Test
    fun `a new database is seeded with the Appendix J exclusions`() = runTest {
        val repository = RoomAppRuleRepository(inMemoryDatabase().appRules(), testDispatchers(), logger)

        val rules = repository.rules.first()

        assertEquals(ExclusionSeed.Packages.sorted(), rules.map { it.app.name })
        assertEquals(List(rules.size) { AppRule(rules[it].app, null, null, excluded = true) }, rules)
        assertEquals(
            Outcome.Ok(AppRule(AppPackage("com.x8bit.bitwarden"), null, null, true)),
            repository.find(AppPackage("com.x8bit.bitwarden")),
        )
    }

    @Test
    fun `app rules are found, replaced and deleted by package`() = runTest {
        val repository = RoomAppRuleRepository(inMemoryDatabase().appRules(), testDispatchers(), logger)
        val gmail = AppRule(AppPackage("com.google.android.gm"), AppCategory.WORK_CHAT, Tone.CASUAL, excluded = false)

        assertEquals(Outcome.Ok(null), repository.find(gmail.app))
        repository.upsert(gmail)
        repository.upsert(gmail.copy(toneOverride = null))
        assertEquals(Outcome.Ok(gmail.copy(toneOverride = null)), repository.find(gmail.app))
        assertEquals(Outcome.Ok(Unit), repository.delete(AppPackage("not.there")))
        assertEquals(Outcome.Ok(Unit), repository.delete(gmail.app))
        assertEquals(Outcome.Ok(null), repository.find(gmail.app))
    }
}
