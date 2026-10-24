package com.stillreader.app

import com.stillreader.app.catalog.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTests {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun coldSearchCanFinishAfterFortySecondsWithoutRestartingTheRequest() = runTest(dispatcher) {
        var requests = 0
        val expected = CatalogPage(listOf(CatalogBook("gutenberg:4212", "Culture and Anarchy")))
        val catalog = CatalogViewModel(initialProvider = SearchProvider { _, _, _ ->
            requests++; delay(40_000); expected
        })
        catalog.query("anarchy"); catalog.load(); runCurrent()
        advanceTimeBy(8_000); runCurrent()
        assertTrue(catalog.state.value.waiting)
        advanceTimeBy(22_000); runCurrent()
        assertTrue(catalog.state.value.loading); assertNull(catalog.state.value.error)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, requests); assertEquals(expected, catalog.state.value.page)
        assertFalse(catalog.state.value.loading); assertFalse(catalog.state.value.waiting)
        advanceTimeBy(20_000); runCurrent()
        assertNull(catalog.state.value.error)
        catalog.cancel()
    }
    @Test fun defaultDeadlineStillStopsAnUnresponsiveSearchAndCancelClearsSlowState() = runTest(dispatcher) {
        var requests = 0
        val catalog = CatalogViewModel(initialProvider = SearchProvider { _, _, _ -> requests++; awaitCancellation() })
        catalog.load(); runCurrent(); advanceTimeBy(59_999); runCurrent()
        assertTrue(catalog.state.value.loading); assertTrue(catalog.state.value.waiting)
        advanceTimeBy(1); runCurrent()
        assertFalse(catalog.state.value.loading); assertFalse(catalog.state.value.waiting)
        assertTrue(catalog.state.value.error!!.contains("took too long")); assertEquals(1, requests)
        catalog.retry(); runCurrent(); advanceTimeBy(8_000); runCurrent()
        assertTrue(catalog.state.value.waiting)
        catalog.stopLoading(); runCurrent()
        assertFalse(catalog.state.value.waiting); assertFalse(catalog.state.value.loading)
        assertEquals(2, requests)
    }

    @Test fun timeoutEndsLoadingEvenWhenCancelledRequestIsStillBlockedAndRetryWins() = runTest(dispatcher) {
        val blocked = CompletableDeferred<CatalogPage>()
        val requests = mutableListOf<String?>()
        val expected = CatalogPage(listOf(CatalogBook("gutenberg:2", "New result")))
        val provider = SearchProvider { _, _, page ->
            requests += page
            if (requests.size == 1) withContext(NonCancellable) { blocked.await() } else expected
        }
        val catalog = CatalogViewModel(requestTimeoutMillis = 20_000, initialProvider = provider)
        val next = "https://gutendex.com/books/?page=2"
        try {
            catalog.load(next); runCurrent()
            assertTrue(catalog.state.value.loading)
            advanceTimeBy(20_000); runCurrent()
            assertFalse(catalog.state.value.loading)
            assertTrue(catalog.state.value.error!!.contains("took too long"))
            assertNull(catalog.state.value.page)
            catalog.retry(); runCurrent()
            assertEquals(listOf(next, next), requests)
            assertEquals(expected, catalog.state.value.page); assertNull(catalog.state.value.error)
            blocked.complete(CatalogPage(listOf(CatalogBook("gutenberg:1", "Obsolete"))))
            runCurrent(); advanceTimeBy(20_000); runCurrent()
            assertEquals(expected, catalog.state.value.page)
            assertFalse(catalog.state.value.loading); assertNull(catalog.state.value.error)
        } finally { blocked.complete(CatalogPage(emptyList())); catalog.cancel(); runCurrent() }
    }
    @Test fun cancelEndsSpinnerAndLateFailureCannotReplaceTheNextSearch() = runTest(dispatcher) {
        val blocked = CompletableDeferred<CatalogPage>()
        val queries = mutableListOf<String>()
        val catalog = CatalogViewModel(initialProvider = SearchProvider { query, _, _ ->
            queries += query
            if (queries.size == 1) withContext(NonCancellable) { blocked.await() } else CatalogPage(emptyList())
        })
        try {
            catalog.query("old"); catalog.load(); runCurrent()
            catalog.stopLoading()
            assertFalse(catalog.state.value.loading); assertTrue(catalog.state.value.error!!.contains("Loading stopped"))
            catalog.query("Austen"); catalog.load(); runCurrent()
            blocked.completeExceptionally(java.io.IOException("Obsolete error")); runCurrent()
            assertEquals(listOf("old", "Austen"), queries)
            assertTrue(catalog.state.value.page!!.books.isEmpty()); assertNull(catalog.state.value.error)
        } finally { blocked.complete(CatalogPage(emptyList())); catalog.cancel(); runCurrent() }
    }
    @Test fun failureEndsLoadingAndLanguageFilterCanRetrySuccessfully() = runTest(dispatcher) {
        val requests = mutableListOf<Pair<String, String>>()
        val catalog = CatalogViewModel(initialProvider = SearchProvider { query, language, _ ->
            requests += query to language
            if (requests.size == 1) error("Offline") else CatalogPage(emptyList())
        })
        catalog.query("Austen"); catalog.load(); runCurrent()
        assertEquals("Offline", catalog.state.value.error); assertFalse(catalog.state.value.loading)
        catalog.language("en"); runCurrent()
        assertEquals(listOf("Austen" to "", "Austen" to "en"), requests)
        assertNull(catalog.state.value.error); assertFalse(catalog.state.value.loading)
        advanceTimeBy(20_000); runCurrent()
        assertNull(catalog.state.value.error)
        catalog.cancel()
    }
}
private class SearchProvider(val search: suspend (String, String, String?) -> CatalogPage) : CatalogProvider {
    override val id = "test"
    override val name = "Project Gutenberg"
    override val client = CatalogClient()
    override suspend fun search(query: String, language: String, page: String?) = search.invoke(query, language, page)
}
