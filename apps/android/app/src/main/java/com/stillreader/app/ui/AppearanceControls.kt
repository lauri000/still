package com.stillreader.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppearanceMenu() {
    val settings = LocalAppearance.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("appearanceMenu")
            .semantics { stateDescription = settings.appearance.label }) {
            Icon(Icons.Default.Contrast, "Appearance")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            Appearance.entries.forEach { appearance ->
                DropdownMenuItem(text = { Text(appearance.label) },
                    trailingIcon = { if (appearance == settings.appearance) Icon(Icons.Default.Check, null) },
                    modifier = Modifier.testTag("appearance-${appearance.preference}")
                        .semantics { selected = appearance == settings.appearance },
                    onClick = { settings.select(appearance); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceOptions() {
    val settings = LocalAppearance.current
    Column {
        Text("Appearance", fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Appearance.entries.forEach { appearance ->
                FilterChip(selected = appearance == settings.appearance,
                    onClick = { settings.select(appearance) }, label = { Text(appearance.label) },
                    modifier = Modifier.testTag("appearance-${appearance.preference}"))
            }
        }
    }
}
