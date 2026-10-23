package com.stillreader.app.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CatalogState(val page: CatalogPage? = null, val loading: Boolean = false, val error: String? = null,
    val query: String = "", val language: String = "", val selected: CatalogBook? = null)
class CatalogViewModel : ViewModel() {
    var provider: CatalogProvider = GutendexProvider(); private set
    private val mutable = MutableStateFlow(CatalogState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var lastURL: String? = null
    fun query(value: String) { mutable.update { it.copy(query = value) } }
    fun language(value: String) { mutable.update { it.copy(language = value) }; load() }
    fun select(value: CatalogBook?) { mutable.update { it.copy(selected = value) } }
    fun configure(value: CatalogProvider) { provider = value; mutable.value = CatalogState(); load() }
    fun retry() = load(lastURL)
    fun load(url: String? = null) {
        job?.cancel(); lastURL = url
        val source = provider; val query = state.value.query; val language = state.value.language
        mutable.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val result = source.search(query, language, url); ensureActive()
                mutable.update { it.copy(page = result, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(loading = false, error = error.message ?: "Could not load this catalog.") } }
        }
    }
    fun cancel() { job?.cancel(); mutable.update { it.copy(loading = false) } }
}
