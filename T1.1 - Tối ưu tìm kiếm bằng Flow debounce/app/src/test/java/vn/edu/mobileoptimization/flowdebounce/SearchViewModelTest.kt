package vn.edu.mobileoptimization.flowdebounce

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(private val latencyMs: Long) : SearchRepository {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String): SearchResult {
            queries += query
            delay(latencyMs)
            return SearchResult(listOf(BookDto("Sách $query", "Tác giả", 100_000)), 1.5, latencyMs, null, 0, 200, null)
        }
    }

    private fun TestScope.viewModel(repository: SearchRepository) =
        SearchViewModel(repository, clock = { testScheduler.currentTime }, wallClock = { 0L })

    private fun TestScope.typeAndroid(viewModel: SearchViewModel, gapMs: Long) {
        "android".indices.forEach { end ->
            viewModel.onQueryChanged("android".substring(0, end + 1))
            runCurrent()
            advanceTimeBy(gapMs)
        }
    }

    @Test
    fun tatToiUu_moiKyTuMotRequest() = runTest(dispatcher) {
        val repository = FakeRepository(latencyMs = 10)
        val viewModel = viewModel(repository)
        typeAndroid(viewModel, gapMs = 120)
        advanceUntilIdle()

        assertEquals(listOf("a", "an", "and", "andr", "andro", "androi", "android"), repository.queries)
        assertEquals(7, viewModel.state.value.off.requests)
        assertEquals(0, viewModel.state.value.off.cancelled)
    }

    @Test
    fun batToiUu_goNhanhChiMotRequest() = runTest(dispatcher) {
        val repository = FakeRepository(latencyMs = 10)
        val viewModel = viewModel(repository)
        viewModel.setOptimized(true)
        typeAndroid(viewModel, gapMs = 120)
        advanceUntilIdle()

        assertEquals(listOf("android"), repository.queries)
        val on = viewModel.state.value.on
        assertEquals(1, on.requests)
        assertTrue(on.records.single().displayed)
        assertTrue(viewModel.state.value.results is ResultsState.Books)
    }

    @Test
    fun batToiUu_requestCuBiHuyKhiCoTuKhoaMoi() = runTest(dispatcher) {
        val repository = FakeRepository(latencyMs = 1_000)
        val viewModel = viewModel(repository)
        viewModel.setOptimized(true)

        viewModel.onQueryChanged("kotlin")
        advanceTimeBy(500) // Debounce xong ở 400 ms, request "kotlin" đang chạy.
        viewModel.onQueryChanged("kotlinx")
        advanceUntilIdle()

        val on = viewModel.state.value.on
        assertEquals(listOf("kotlin", "kotlinx"), repository.queries)
        assertEquals(RequestState.CANCELLED, on.records[0].state)
        assertEquals(RequestState.SUCCESS, on.records[1].state)
        assertEquals(1, on.cancelled)
        assertEquals(1, on.totals().httpCount) // Request bị hủy không tính vào Tổng HTTP.
    }

    @Test
    fun lamLai_xoaSoDoVaHuyRequestDangChay() = runTest(dispatcher) {
        val repository = FakeRepository(latencyMs = 1_000)
        val viewModel = viewModel(repository)
        viewModel.setOptimized(true)
        viewModel.onQueryChanged("kotlin")
        advanceTimeBy(500)
        viewModel.reset()
        advanceUntilIdle()

        assertEquals(0, viewModel.state.value.on.requests)
        assertEquals(ResultsState.Idle, viewModel.state.value.results)
    }
}
