package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.Transition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** One row of the transition table (android/docs/contracts.md page 3). */
data class Row(val name: String, val state: SessionState, val event: SessionEvent, val expected: Transition)

/** The transition table of android/docs/contracts.md page 3, one row per meaningful transition (TransitionRows.kt). */
class ReducerTransitionTableTest {
    @TestFactory
    fun transitions(): List<DynamicTest> {
        val rows = IdleRows + ArmingRows + ListeningRows + WorkingRows + DoneRows + ProblemRows
        return rows.map { row ->
            DynamicTest.dynamicTest(row.name) { assertEquals(row.expected, reduce(row.state, row.event)) }
        }
    }
}
