package com.stillreader.app

import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
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
import com.stillreader.app.core.*
import com.stillreader.app.data.BookCodec
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

    /** Wall-clock gestures and frame metrics; opt-in, with no private text in test output. */
    @Test fun scrollFrameTimings() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("scrollEvaluation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File(instrumentation.targetContext.filesDir, "passage-evaluation/scroll-book.json")
        val book = BookCodec.json.decodeFromString<Book>(source.readText())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: ReadingSession
        compose.runOnUiThread {
            session = ReadingSession(book, scope) { }
            session.seek(session.tokens.size / 2)
            session.passageMode = true
        }
        compose.setContent {
            val state by session.state.collectAsState()
            StillTheme { PassageView(session, state) { session.seek(it) } }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("passageScroll").fetchSemanticsNode().boundsInRoot
        val origin = IntArray(2)
        compose.runOnUiThread { compose.activity.window.decorView.getLocationOnScreen(origin) }
        val frames = mutableListOf<Double>()
        val layouts = mutableListOf<Double>()
        val cpu = mutableListOf<Double>()
        val draws = mutableListOf<Double>()
        val thread = HandlerThread("scroll-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            synchronized(frames) {
                frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
                layouts += metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1e6
                cpu += listOf(FrameMetrics.UNKNOWN_DELAY_DURATION, FrameMetrics.INPUT_HANDLING_DURATION,
                    FrameMetrics.ANIMATION_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION,
                    FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION).sumOf { metrics.getMetric(it) } / 1e6
                draws += metrics.getMetric(FrameMetrics.DRAW_DURATION) / 1e6
            }
        }
        compose.runOnUiThread { compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        val startWord = session.state.value.index
        try {
            repeat(12) {
                val down = SystemClock.uptimeMillis()
                val x = origin[0] + bounds.center.x
                val bottom = origin[1] + bounds.top + bounds.height * .8f
                fun send(action: Int, y: Float) {
                    MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also {
                        instrumentation.sendPointerSync(it); it.recycle()
                    }
                }
                send(MotionEvent.ACTION_DOWN, bottom)
                repeat(18) { step ->
                    SystemClock.sleep(16)
                    send(MotionEvent.ACTION_MOVE, bottom - bounds.height * .6f * (step + 1) / 18)
                }
                send(MotionEvent.ACTION_UP, bottom - bounds.height * .6f)
                SystemClock.sleep(600)
            }
            compose.waitForIdle()
            val values = synchronized(frames) { frames.sorted() }
            fun percentile(p: Double) = values[((values.size - 1) * p).toInt()]
            check(values.isNotEmpty())
            val cpuValues = synchronized(frames) { cpu.sorted() }
            val drawValues = synchronized(frames) { draws.sorted() }
            fun cpuPercentile(p: Double) = cpuValues[((cpuValues.size - 1) * p).toInt()]
            val drawP99 = drawValues[((drawValues.size - 1) * .99).toInt()]
            Log.i("StillScrollProfile", "frames=${values.size} p50_ms=${percentile(.5)} p90_ms=${percentile(.9)} p95_ms=${percentile(.95)} p99_ms=${percentile(.99)} over16ms=${values.count { it > 16.67 }} over32ms=${values.count { it > 32 }} layout_ms=${layouts.sum()} cpu_p95_ms=${cpuPercentile(.95)} cpu_p99_ms=${cpuPercentile(.99)} draw_p99_ms=$drawP99 words=${session.state.value.index - startWord}")
        } finally {
            compose.runOnUiThread { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            scope.cancel()
        }
    }

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
                if (passage) PassageView(session, state) { session.seek(it) }
                else WordRibbon(session, state.index, focus, false, percent)
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
