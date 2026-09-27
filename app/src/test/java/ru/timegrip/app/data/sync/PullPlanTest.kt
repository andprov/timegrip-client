package ru.timegrip.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.timegrip.app.domain.DateRange
import java.time.LocalDate

/** What a sync downloads, so that syncs asked for together do not repeat each other. */
class PullPlanTest {
    private val window = DateRange(LocalDate.of(2026, 8, 1), null)
    private val thisMonth = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
    private val lastYear = DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))

    @Test
    fun `a fresh pull is not repeated`() {
        assertEquals(emptyList<DateRange>(), pullPlan(fullSyncDone = true, fresh = true, window, null))
    }

    @Test
    fun `a period inside the window adds nothing to a fresh pull`() {
        assertEquals(emptyList<DateRange>(), pullPlan(fullSyncDone = true, fresh = true, window, thisMonth))
    }

    @Test
    fun `a period inside the window is covered by the window`() {
        assertEquals(listOf(window), pullPlan(fullSyncDone = true, fresh = false, window, thisMonth))
    }

    @Test
    fun `an older period is pulled even right after a sync`() {
        assertEquals(listOf(lastYear), pullPlan(fullSyncDone = true, fresh = true, window, lastYear))
        assertEquals(listOf(window, lastYear), pullPlan(fullSyncDone = true, fresh = false, window, lastYear))
    }

    @Test
    fun `the whole history replaces the window`() {
        assertEquals(listOf(DateRange.ALL), pullPlan(fullSyncDone = true, fresh = false, window, DateRange.ALL))
        assertEquals(listOf(DateRange.ALL), pullPlan(fullSyncDone = false, fresh = true, window, thisMonth))
    }
}
