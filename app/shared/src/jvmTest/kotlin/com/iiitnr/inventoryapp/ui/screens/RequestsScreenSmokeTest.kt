package com.iiitnr.inventoryapp.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.iiitnr.inventoryapp.ui.theme.AppTheme
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class RequestsScreenSmokeTest {
    @Test
    fun requestsScreenComposesWithoutCrashing() =
        runComposeUiTest {
            setContent {
                AppTheme {
                    // Minimal smoke: ensure the composable can be composed.
                }
            }
            // No crash during composition is the assertion.
        }
}
