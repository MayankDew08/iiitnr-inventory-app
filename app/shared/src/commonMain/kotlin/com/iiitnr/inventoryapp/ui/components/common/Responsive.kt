@file:Suppress("MatchingDeclarationName")

package com.iiitnr.inventoryapp.ui.components.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp

/**
 * Width buckets used to drive the responsive layouts. Mirrors the usual
 * Material 3 window size classes without pulling in
 * `material3-window-size-class`.
 */
enum class ScreenWidthClass {
    /** `< 600.dp` — a phone in portrait, or a very narrow desktop window. */
    Compact,

    /** `600.dp..<900.dp` — a phone in landscape / small tablet. */
    Medium,

    /** `>= 900.dp` — tablet, desktop and the default desktop window. */
    Expanded,
}

/** Resolves the current [ScreenWidthClass] from the constraints of this scope. */
@Composable
fun BoxWithConstraintsScope.screenWidthClass(): ScreenWidthClass =
    when {
        maxWidth < 600.dp -> ScreenWidthClass.Compact
        maxWidth < 900.dp -> ScreenWidthClass.Medium
        else -> ScreenWidthClass.Expanded
    }

/**
 * Runs [content] inside a [BoxWithConstraints] and hands it the resolved width
 * class, so screens can switch between single-column and multi-pane layouts.
 */
@Composable
fun ResponsiveContent(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(ScreenWidthClass) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        content(screenWidthClass())
    }
}

/**
 * Points at clickable surfaces on desktop. No-op on touch platforms, which have
 * no hover cursor.
 */
fun Modifier.handCursor(): Modifier = this.pointerHoverIcon(PointerIcon.Hand)
