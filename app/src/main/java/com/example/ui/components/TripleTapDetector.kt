package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
fun TripleTapDetector(
    onTripleTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var clickCount by remember { mutableIntStateOf(0) }
    var lastClickTime by remember { mutableStateOf(0L) }

    Box(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) {
            val now = System.currentTimeMillis()
            if (now - lastClickTime > 2000) {
                clickCount = 1
            } else {
                clickCount++
            }
            lastClickTime = now
            if (clickCount >= 3) {
                onTripleTap()
                clickCount = 0
            }
        },
        content = content
    )
}
