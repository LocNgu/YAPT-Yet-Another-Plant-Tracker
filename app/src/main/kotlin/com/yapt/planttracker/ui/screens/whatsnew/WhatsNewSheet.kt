package com.yapt.planttracker.ui.screens.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "What's New",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 8.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            items(WhatsNewContent.all) { notes ->
                ReleaseSection(notes)
            }
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 32.dp)
        ) {
            Text("Got it")
        }
    }
}

@Composable
private fun ReleaseSection(notes: ReleaseNotes) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(notes.versionName, style = MaterialTheme.typography.titleMedium)
        if (notes.added.isNotEmpty()) NoteSection("Added", notes.added)
        if (notes.fixed.isNotEmpty()) NoteSection("Fixed", notes.fixed)
        if (notes.changed.isNotEmpty()) NoteSection("Changed", notes.changed)
    }
}

@Composable
private fun NoteSection(heading: String, entries: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(heading, style = MaterialTheme.typography.labelLarge)
        entries.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}
