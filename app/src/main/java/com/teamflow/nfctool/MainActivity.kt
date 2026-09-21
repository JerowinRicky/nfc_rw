@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.teamflow.nfctool

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teamflow.nfctool.domain.HistoryItem
import com.teamflow.nfctool.domain.NfcFailure
import com.teamflow.nfctool.domain.ScanState
import com.teamflow.nfctool.domain.TagSnapshot
import com.teamflow.nfctool.nfc.NdefCodec
import com.teamflow.nfctool.presentation.NfcViewModel
import com.teamflow.nfctool.presentation.NfcViewModelFactory

class MainActivity : ComponentActivity() {
    private val viewModel: NfcViewModel by viewModels { NfcViewModelFactory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                NfcToolApp(viewModel, this) {
                    startActivity(Intent(AndroidSettings.ACTION_NFC_SETTINGS))
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stop(this)
    }
}

@Composable
private fun NfcToolApp(viewModel: NfcViewModel, activity: MainActivity, openSettings: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()
    var showWriter by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("NFC Tool") }) }) { padding ->
        when (val current = state) {
            is ScanState.Success -> TagDetails(current.tag, viewModel::reset) { showWriter = true }
            else -> ScanHome(Modifier.padding(padding), viewModel, current, history, activity, openSettings)
        }
    }

    if (showWriter) {
        WriteDialog(onDismiss = { showWriter = false }) { record -> viewModel.write(listOf(record)) }
    }
}

@Composable
private fun ScanHome(
    modifier: Modifier,
    viewModel: NfcViewModel,
    state: ScanState,
    history: List<HistoryItem>,
    activity: MainActivity,
    openSettings: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("NFC diagnostics", style = MaterialTheme.typography.headlineSmall) }
        item {
            InfoCard("NFC status") {
                Text(when {
                    !viewModel.supported() -> "This device does not support NFC."
                    !viewModel.enabled() -> "NFC is disabled."
                    state is ScanState.Scanning -> "Waiting for an NFC tag…"
                    state is ScanState.Reading -> "Reading NFC tag…"
                    else -> "NFC is ready."
                })
            }
        }
        if (!viewModel.enabled()) item { TextButton(onClick = openSettings) { Text("Open NFC settings") } }
        item {
            Button(
                onClick = { viewModel.scan(activity) },
                enabled = viewModel.supported() && viewModel.enabled() && state !is ScanState.Reading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Scan NFC tag") }
        }
        if (state is ScanState.Error) item { FailureCard(state.error) }
        item { Text("Recent scans", style = MaterialTheme.typography.titleLarge) }
        if (history.isEmpty()) item { Text("No completed scans yet.") }
        else items(history, key = { it.id }) { entry ->
            InfoCard(entry.uid ?: "UID unavailable") {
                Text("${entry.technologies} · ${entry.records} record(s)")
                Text("Protection: ${entry.protection.label}")
                TextButton(onClick = { viewModel.deleteHistory(entry.id) }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun TagDetails(tag: TagSnapshot, onBack: () -> Unit, onWrite: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = onBack) { Text("Back to scanner") }
            Text("Tag details", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            InfoCard("Overview") {
                Detail("UID", tag.uid ?: "Unavailable through Android API")
                Detail("NDEF", if (tag.ndefSupported) "Supported" else "Not exposed")
                Detail("Writable", tag.writable?.let { if (it) "Yes" else "No" } ?: "Unknown")
                Detail("Protection", tag.protection.label)
            }
        }
        item { Button(onClick = onWrite, modifier = Modifier.fillMaxWidth()) { Text("Write NDEF record") } }
        item { Text("NDEF records", style = MaterialTheme.typography.titleLarge) }
        if (tag.ndefRecords.isEmpty()) item { Text("No readable NDEF records are exposed by this tag.") }
        else items(tag.ndefRecords, key = { it.rawHex }) { record ->
            InfoCard(record.kind) {
                Detail("Type", record.type)
                record.language?.let { Detail("Language", it) }
                Detail("Payload", record.value)
            }
        }
    }
}

@Composable
private fun WriteDialog(onDismiss: () -> Unit, onWrite: (android.nfc.NdefRecord) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write text record") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Text") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(
                onClick = { onWrite(NdefCodec.text(value, "en")); onDismiss() },
                enabled = value.isNotBlank()
            ) { Text("Write") }
        }
    )
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun FailureCard(error: NfcFailure) {
    InfoCard(error.message) {
        Text(error.action)
        Text(error.technical, style = MaterialTheme.typography.bodySmall)
    }
}
