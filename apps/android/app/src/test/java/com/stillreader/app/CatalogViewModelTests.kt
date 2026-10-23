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

    @Test fun timeoutEndsLoadingEvenWhenCancelledRequestIsStillBlockedAndRetryWins() = runTest(dispatcher) {
        val blocked = CompletableDeferred<CatalogPage>()
        val requests = mutableListOf<String?>()
        val expected = CatalogPage(listOf(CatalogBook("gutenberg:2", "New result")))
        val provider = SearchProvider { _, _, page ->
            requests += page
            if (requests.size == 1) withContext(NonCancellable) { blocked.await() } else expected
        }
        val catalog = CatalogViewModel(initialProvider = provider)
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
