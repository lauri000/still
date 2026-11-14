package com.stillreader.app

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.stillreader.app.ui.AppearanceMenu
import com.stillreader.app.ui.Paper
import com.stillreader.app.ui.StillTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AppearanceTests {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val preferences get() = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("reading", 0)
    private var previous: String? = null

    @Before fun before() {
        previous = preferences.getString("appearance", null)
        preferences.edit().remove("appearance").commit()
    }
    @After fun after() {
        preferences.edit().apply { previous?.let { putString("appearance", it) } ?: remove("appearance") }.commit()
    }

    @Test fun followsSystemUnlessOverriddenAndRestoresSelectionOnRemount() {
        var systemDark by mutableStateOf(false)
        var generation by mutableIntStateOf(0)
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                key(generation) {
                    StillTheme {
                        Box(Modifier.fillMaxSize().background(Paper).testTag("themeSurface")) { AppearanceMenu() }
                    }
                }
            }
        }
        fun assertDark(expected: Boolean) {
            val pixels = compose.onNodeWithTag("themeSurface").captureToImage().toPixelMap()
            assertEquals(expected, pixels[pixels.width / 2, pixels.height / 2].luminance() < .1f)
        }
        fun select(value: String) {
            compose.onNodeWithTag("appearanceMenu").performClick()
            compose.onNodeWithTag("appearance-$value").performClick()
        }
        assertDark(false)
        compose.runOnIdle { systemDark = true }
        assertDark(true)
        select("light")
        assertDark(false)
        compose.runOnIdle { generation++ }
        assertDark(false)
        compose.onNodeWithTag("appearanceMenu").performClick()
        compose.onNodeWithTag("appearance-light").assertIsSelected().performClick()
        select("dark")
        compose.runOnIdle { systemDark = false; generation++ }
        assertDark(true)
        assertEquals("dark", preferences.getString("appearance", null))
        select("system")
        assertDark(false)
        compose.runOnIdle { systemDark = true }
        assertDark(true)
        compose.runOnIdle { systemDark = false }
        assertDark(false)
    }
}

class MainActivityAppearanceTests {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("reading", 0)
    private var previous: String? = null

    @Before fun before() { previous = preferences.getString("appearance", null) }
    @After fun after() {
        preferences.edit().apply { previous?.let { putString("appearance", it) } ?: remove("appearance") }.commit()
    }

    @Test fun savedAppearanceSurvivesActivityRecreationAndUpdatesSystemBars() {
        compose.waitUntil(10_000) { compose.onNodeWithTag("appearanceMenu").isDisplayed() }
        compose.onNodeWithTag("appearanceMenu").performClick()
        compose.onNodeWithTag("appearance-dark").performClick()
        fun assertBars(light: Boolean) {
            compose.runOnIdle {
                val window = compose.activity.window
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                assertEquals(light, controller.isAppearanceLightStatusBars)
                assertEquals(light, controller.isAppearanceLightNavigationBars)
            }
        }
        assertBars(false)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onNodeWithTag("appearanceMenu").isDisplayed() }
        assertBars(false)
        compose.onNodeWithTag("appearanceMenu").performClick()
        compose.onNodeWithTag("appearance-dark").assertIsSelected()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "dark-mode-appearance.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        compose.onNodeWithTag("appearance-light").performClick()
        assertBars(true)
    }
}
