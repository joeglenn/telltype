package net.jbglenn.telltype.ui.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier

/** Horizontal screen margins for [widthClass] (16 dp on phones, 24 dp on tablets) and a readable maximum width. */
fun Modifier.screenContent(widthClass: WidthClass): Modifier = this
    .widthIn(max = Layout.MaxContentWidth)
    .fillMaxWidth()
    .padding(horizontal = widthClass.margin)
