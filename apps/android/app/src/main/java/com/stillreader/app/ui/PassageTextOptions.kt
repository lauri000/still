package com.stillreader.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PassageTextOptions(size: Int, change: (Int) -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, containerColor = Paper, titleContentColor = Ink, textContentColor = Ink, title = { Text("Text size") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("A quiet moment with a good book.", fontFamily = FontFamily.Serif,
                fontSize = size.sp, lineHeight = (size * 1.6f).sp, letterSpacing = 0.sp, color = Ink,
                modifier = Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp)).padding(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { change((size - 2).coerceAtLeast(16)) }, enabled = size > 16,
                    modifier = Modifier.testTag("smallerPassageText").semantics { contentDescription = "Smaller text" }) { Text("A−", fontSize = 18.sp) }
                Text("$size", fontSize = 18.sp, modifier = Modifier.testTag("passageTextSizeValue"))
                OutlinedButton(onClick = { change((size + 2).coerceAtMost(32)) }, enabled = size < 32,
                    modifier = Modifier.testTag("largerPassageText").semantics { contentDescription = "Larger text" }) { Text("A+", fontSize = 24.sp) }
            }
            Text("Saved for scrolling view.", color = Muted, fontSize = 13.sp)
        }
    }, dismissButton = { TextButton(onClick = { change(22) }, enabled = size != 22) { Text("Reset") } },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}
