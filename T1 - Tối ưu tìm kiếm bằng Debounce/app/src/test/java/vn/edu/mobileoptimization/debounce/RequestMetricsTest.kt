package vn.edu.mobileoptimization.debounce

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestMetricsTest {
    @Test fun totalsIncludeAllSevenCompletedRequestsEvenWhenOnlyTheLastWasDisplayed() {
        val metrics = ModeMetrics()
        val httpTimes = listOf(6L, 6L, 7L, 10L, 6L, 5L, 8L)
        val databaseTimes = listOf(2.11, 1.73, 2.04, 2.67, 1.87, 2.00, 1.58)
        val queries = listOf("a", "an", "and", "andr", "andro", "androi", "android")

        queries.forEachIndexed { index, query ->
            metrics.add(RequestRecord(
                number = 0,
                query = query,
                startedAtMillis = 0,
                state = RequestState.SUCCESS,
                httpMs = httpTimes[index],
                databaseMs = databaseTimes[index],
                displayed = index == queries.lastIndex,
            ))
        }

        val totals = metrics.totals()
        assertEquals(7, metrics.requests)
        assertEquals(7, totals.httpCount)
        assertEquals(48L, totals.httpMs)
        assertEquals(7, totals.databaseCount)
        assertEquals(14.0, totals.databaseMs, 0.000001)
    }

    @Test fun totalsCountAvailableMeasurementsFromFinishedRequestsOnly() {
        val metrics = ModeMetrics()
        metrics.add(record(RequestState.PENDING, 60, 3.0))
        metrics.add(record(RequestState.INTERRUPTED, 80, 4.0))
        metrics.add(record(RequestState.ERROR, 12, -1.0))
        metrics.add(record(RequestState.ERROR, -1, -1.0))
        metrics.add(record(RequestState.SUCCESS, 0, 0.0))
        metrics.add(record(RequestState.SUCCESS, 5, Double.NaN))

        val totals = metrics.totals()
        assertEquals(6, metrics.requests)
        assertEquals(3, totals.httpCount)
        assertEquals(17L, totals.httpMs)
        assertEquals(1, totals.databaseCount)
        assertEquals(0.0, totals.databaseMs, 0.000001)
    }

    @Test fun aLateRequestContributesWhenItFinishesEvenIfNotDisplayed() {
        val metrics = ModeMetrics()
        val pending = record(RequestState.PENDING, -1, -1.0)
        metrics.add(pending)
        assertEquals(0, metrics.totals().httpCount)

        pending.httpMs = 13
        pending.databaseMs = 2.5
        pending.state = RequestState.SUCCESS

        val totals = metrics.totals()
        assertEquals(1, totals.httpCount)
        assertEquals(13L, totals.httpMs)
        assertEquals(1, totals.databaseCount)
        assertEquals(2.5, totals.databaseMs, 0.000001)
    }

    @Test fun modesAreIndependentAndClearResetsOnlyTheChosenMode() {
        val off = ModeMetrics()
        val on = ModeMetrics()
        off.add(record(RequestState.SUCCESS, 8, 2.0))
        off.add(record(RequestState.SUCCESS, 7, 1.0))
        on.add(record(RequestState.SUCCESS, 6, 1.5))

        assertEquals(15L, off.totals().httpMs)
        assertEquals(6L, on.totals().httpMs)
        off.clear()

        assertEquals(0, off.requests)
        assertEquals(RequestTotals(0, 0L, 0, 0.0), off.totals())
        assertEquals(1, on.totals().httpCount)
        assertEquals(6L, on.totals().httpMs)
        assertEquals(1.5, on.totals().databaseMs, 0.000001)
    }

    private fun record(state: RequestState, httpMs: Long, databaseMs: Double) = RequestRecord(
        number = 0,
        query = "android",
        startedAtMillis = 0,
        state = state,
        httpMs = httpMs,
        databaseMs = databaseMs,
    )
}
