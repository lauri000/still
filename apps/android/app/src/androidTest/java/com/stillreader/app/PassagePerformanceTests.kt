package com.stillreader.app

import android.os.Debug
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stillreader.app.core.ReadingSession
import com.stillreader.app.data.PdfImporter
import com.stillreader.app.ui.PassageView
import com.stillreader.app.ui.StillTheme
import com.stillreader.app.ui.WordRibbon
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in diagnosis using a private local PDF; the PDF is never bundled in the APK. */
@RunWith(AndroidJUnit4::class)
class PassagePerformanceTests {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun switchesLargeBookAtDifferentSizesAndOptions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("passageEvaluation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "passage-evaluation/1984.pdf")
        check(source.isFile)
        val began = System.nanoTime()
        val book = PdfImporter(context).extract(source, "1984.pdf")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: ReadingSession
        compose.runOnUiThread {
            val preparedAt = System.nanoTime()
            session = ReadingSession(book, scope) { }
            Log.i("StillPassageProbe", "reader_prepare_ms=${(System.nanoTime()-preparedAt)/1e6} mapped_headings=${session.structure.headings.size}")
            session.seek(session.tokens.size / 2)
            session.passageMode = true
        }
        Log.i("StillPassageProbe", "import_and_tokens_ms=${(System.nanoTime()-began)/1e6} words=${session.tokens.size} unique=${session.tokens.map { it.word.text }.toSet().size}")
        var passage by mutableStateOf(false)
        var percent by mutableIntStateOf(100)
        var focus by mutableStateOf(false)
        compose.setContent {
            val state by session.state.collectAsState()
            StillTheme { Box(Modifier.fillMaxSize()) {
                if (passage) PassageView(session, state)
                else WordRibbon(session, state.index, focus, false, false, percent)
            } }
        }
        try {
            for ((size, focused, natural) in listOf(Triple(100, false, false), Triple(125, false, false),
                Triple(125, true, false), Triple(125, false, true), Triple(125, true, true), Triple(100, true, true))) {
                compose.runOnUiThread { passage = false; percent = size; focus = focused; session.naturalPauses = natural }
                compose.waitForIdle()
                val started = System.nanoTime()
                Log.i("StillPassageProbe", "begin size=$size focus=$focused natural=$natural")
                compose.runOnUiThread { passage = true }
                compose.waitForIdle()
                compose.onNodeWithTag("currentPassageRow").assertIsDisplayed()
                val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                val runtime = Runtime.getRuntime()
                Log.i("StillPassageProbe", "done size=$size focus=$focused natural=$natural switch_ms=${(System.nanoTime()-started)/1e6} heap_mb=${(runtime.totalMemory()-runtime.freeMemory())/1048576} native_mb=${Debug.getNativeHeapAllocatedSize()/1048576} pss_mb=${memory.totalPss/1024}")
            }
        } finally { scope.cancel() }
    }
}
