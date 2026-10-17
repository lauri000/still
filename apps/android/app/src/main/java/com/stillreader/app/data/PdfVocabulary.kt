package com.stillreader.app.data

import android.content.Context

internal object PdfVocabulary {
    @Volatile private var cached: Set<String>? = null
    fun words(context: Context): Set<String> = cached ?: synchronized(this) {
        cached ?: context.assets.open("pdf-english-words.txt").bufferedReader().use { it.readLines().toHashSet() }
            .also { cached = it }
    }
}
