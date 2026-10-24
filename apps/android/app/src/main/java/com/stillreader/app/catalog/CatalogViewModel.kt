package com.stillreader.app.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CatalogState(val page: CatalogPage? = null, val loading: Boolean = false, val waiting: Boolean = false, val error: String? = null,
    val query: String = "", val language: String = "", val selected: CatalogBook? = null)
class CatalogViewModel(private val requestTimeoutMillis: Long = CATALOG_REQUEST_TIMEOUT_MILLIS, initialProvider: CatalogProvider = GutendexProvider()) : ViewModel() {
    val provider: CatalogProvider = initialProvider
    private val mutable = MutableStateFlow(CatalogState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var deadline: Job? = null
    private var generation = 0L
    private var lastURL: String? = null
    fun query(value: String) { mutable.update { it.copy(query = value) } }
    fun language(value: String) { mutable.update { it.copy(language = value) }; load() }
    fun select(value: CatalogBook?) { mutable.update { it.copy(selected = value) } }
    fun retry() = load(lastURL)
    fun load(url: String? = null) {
        cancel(); lastURL = url
        val token = generation
        val source = provider; val query = state.value.query; val language = state.value.language
        mutable.update { it.copy(loading = true, error = null) }
        // Independent of socket cleanup: a stalled DNS lookup/read must not own the spinner.
        deadline = viewModelScope.launch {
            val noticeDelay = minOf(8_000, requestTimeoutMillis)
            delay(noticeDelay)
            if (generation == token && state.value.loading && requestTimeoutMillis > noticeDelay) {
                mutable.update { it.copy(waiting = true) }
            }
            delay(requestTimeoutMillis - noticeDelay)
            if (generation == token && state.value.loading) {
                this@CatalogViewModel.cancel()
                mutable.update { it.copy(error = "${source.name} took too long to respond. Please try again.") }
            }
        }
        job = viewModelScope.launch {
            try {
                val result = source.search(query, language, url); ensureActive()
                if (generation == token) mutable.update { it.copy(page = result, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == token) mutable.update { it.copy(error = error.message ?: "Could not load this catalog.") }
            } finally {
                if (generation == token) {
                    deadline?.cancel(); deadline = null
                    mutable.update { it.copy(loading = false, waiting = false) }
                }
            }
        }
    }
    fun cancel() {
        generation++
        deadline?.cancel(); deadline = null
        job?.cancel(); job = null
        mutable.update { it.copy(loading = false, waiting = false) }
    }
    fun stopLoading() {
        cancel()
        if (state.value.page == null) mutable.update { it.copy(error = "Loading stopped. Tap Try again when you’re ready.") }
    }
}
