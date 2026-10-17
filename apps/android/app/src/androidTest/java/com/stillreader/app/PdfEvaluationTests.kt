package com.stillreader.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stillreader.app.data.PdfImporter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfiumCore

/** Opt-in evaluation of private PDFs, supplied outside the APK/repository. */
@RunWith(AndroidJUnit4::class)
class PdfEvaluationTests {
    @Test fun evaluateLocalPdfs() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val enabled = InstrumentationRegistry.getArguments().getString("pdfEvaluation") == "true"
        assumeTrue("Supply -e pdfEvaluation true and files/pdf-evaluation/*.pdf", enabled)
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "pdf-evaluation")
        val inputs = directory.listFiles().orEmpty().filter { it.extension == "pdf" }.sortedBy { it.name }
        check(inputs.isNotEmpty()) { "No evaluation PDFs supplied" }
        for (file in inputs) {
            val start = System.nanoTime()
            val book = PdfImporter(context).extract(file, file.name)
            val seconds = (System.nanoTime() - start) / 1e9
            val pages = JSONArray()
            book.pages.forEach { page ->
                pages.put(JSONObject().put("text", page.text).put("original", page.extractedText))
            }
            File(directory, "${file.name}.json").writeText(JSONObject()
                .put("seconds", seconds).put("pages", pages).toString())
            if (InstrumentationRegistry.getArguments().getString("pdfGeometry") == "true") {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfiumCore(context).newDocument(descriptor).use { document ->
                        File(directory, "${file.name}.geometry.json").bufferedWriter().use { output ->
                        output.write("[")
                        for (index in 0 until document.getPageCount()) {
                            document.openPage(index)!!.use { page -> page.openTextPage().use { textPage ->
                                val chars = JSONArray()
                                for (i in 0 until textPage.textPageCountChars()) {
                                    val box = textPage.textPageGetCharBox(i) ?: android.graphics.RectF()
                                    chars.put(JSONArray().put(textPage.textPageGetUnicode(i).toString())
                                        .put(box.left.toDouble()).put(box.bottom.toDouble())
                                        .put(box.right.toDouble()).put(box.top.toDouble()))
                                }
                                if (index > 0) output.write(",")
                                output.write(JSONObject().put("chars", chars).toString())
                            } }
                        }
                        output.write("]")
                        }
                    }
                }
            }
            println("PDF evaluation: ${file.name}, ${book.pages.size} pages, ${seconds}s")
        }
    }
}
