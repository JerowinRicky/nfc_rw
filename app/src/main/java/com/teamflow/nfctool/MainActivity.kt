@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.teamflow.nfctool

import android.content.Intent
import android.nfc.NdefRecord
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
import com.teamflow.nfctool.domain.*
import com.teamflow.nfctool.nfc.NdefCodec
import com.teamflow.nfctool.presentation.NfcViewModel
import com.teamflow.nfctool.presentation.NfcViewModelFactory

class MainActivity : ComponentActivity() {
    private val viewModel: NfcViewModel by viewModels { NfcViewModelFactory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { NfcToolApp(viewModel, this) { startActivity(Intent(AndroidSettings.ACTION_NFC_SETTINGS)) } } }
    }

    override fun onPause() { super.onPause(); viewModel.stop(this) }

    override fun onResume() { super.onResume(); viewModel.resume(this) }
}

@Composable
private fun NfcToolApp(viewModel: NfcViewModel, activity: MainActivity, openSettings: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    var writerFor by remember { mutableStateOf<TagSnapshot?>(null) }
    var confirmFormat by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showProfiles by remember { mutableStateOf(false) }
    var profileToSave by remember { mutableStateOf<TagSnapshot?>(null) }
    var showClone by remember { mutableStateOf(false) }
    var cloneStage by remember { mutableStateOf(CloneStage.INTRO) }
    var cloneSource by remember { mutableStateOf<TagSnapshot?>(null) }
    var cloneOriginal by remember { mutableStateOf<List<EditableNdefRecord>>(emptyList()) }
    var cloneEdited by remember { mutableStateOf<List<EditableNdefRecord>>(emptyList()) }
    var cloneDestination by remember { mutableStateOf<TagSnapshot?>(null) }

    LaunchedEffect(state, cloneStage) {
        val tag = when (val currentState = state) {
            is ScanState.Success -> currentState.tag
            is ScanState.Partial -> currentState.tag
            else -> null
        }
        if (cloneStage == CloneStage.SOURCE_SCANNING && tag != null) {
            cloneSource = tag
            cloneOriginal = tag.ndefRecords.mapIndexed { index, record -> NdefCodec.editable(record, index.toLong()) }
            cloneEdited = cloneOriginal
            cloneStage = CloneStage.EDIT
        } else if (cloneStage == CloneStage.DESTINATION_SCANNING && tag != null) {
            cloneDestination = tag
            cloneStage = CloneStage.PREVIEW
        } else if (cloneStage == CloneStage.WRITING && tag != null) {
            val expected = runCatching { android.nfc.NdefMessage(cloneEdited.map(NdefCodec::build).toTypedArray()).toByteArray() }.getOrNull()
            cloneStage = if (expected != null && tag.rawNdef?.contentEquals(expected) == true) CloneStage.SUCCESS else CloneStage.VERIFY_FAILED
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Column { Text("NFC Tool"); Text("Reader, analyzer & NDEF writer", style = MaterialTheme.typography.labelSmall) } }) }
    ) { padding ->
        val contentModifier = Modifier.padding(padding).consumeWindowInsets(padding)
        when (val current = state) {
            is ScanState.Success -> if (showClone) CloneEditScreen(contentModifier, cloneStage, cloneSource, cloneOriginal, cloneEdited, cloneDestination, activity, viewModel, { cloneStage = CloneStage.SOURCE_SCANNING; vmScan(viewModel, activity) }, { cloneStage = CloneStage.DESTINATION_SCANNING; vmScan(viewModel, activity) }, { cloneEdited = it }, { cloneEdited = cloneOriginal }, { cloneStage = it }, { showClone = false; cloneStage = CloneStage.INTRO }) else ResultScreen(contentModifier, current.tag, viewModel, activity, { writerFor = current.tag }, { confirmFormat = true }, { profileToSave = current.tag }, selectedProfile)
            is ScanState.Partial -> if (showClone) CloneEditScreen(contentModifier, cloneStage, cloneSource, cloneOriginal, cloneEdited, cloneDestination, activity, viewModel, { cloneStage = CloneStage.SOURCE_SCANNING; vmScan(viewModel, activity) }, { cloneStage = CloneStage.DESTINATION_SCANNING; vmScan(viewModel, activity) }, { cloneEdited = it }, { cloneEdited = cloneOriginal }, { cloneStage = it }, { showClone = false; cloneStage = CloneStage.INTRO }) else ResultScreen(contentModifier, current.tag, viewModel, activity, { writerFor = current.tag }, { confirmFormat = true }, { profileToSave = current.tag }, selectedProfile)
            else -> if (showHistory) {
                HistoryScreen(contentModifier, history, onBack = { showHistory = false }, onDelete = viewModel::deleteHistory)
            } else if (showClone) {
                CloneEditScreen(contentModifier, cloneStage, cloneSource, cloneOriginal, cloneEdited, cloneDestination, activity, viewModel, { cloneStage = CloneStage.SOURCE_SCANNING; vmScan(viewModel, activity) }, { cloneStage = CloneStage.DESTINATION_SCANNING; vmScan(viewModel, activity) }, { cloneEdited = it }, { cloneEdited = cloneOriginal }, { cloneStage = it }, { showClone = false; cloneStage = CloneStage.INTRO })
            } else if (showProfiles) {
                ProfilesScreen(contentModifier, profiles, selectedProfile, onBack = { showProfiles = false }, onUse = { viewModel.selectProfile(it); showProfiles = false }, onDelete = viewModel::deleteProfile)
            } else {
                HomeScreen(contentModifier, viewModel, current, history, profiles, activity, openSettings, onShowHistory = { showHistory = true }, onShowProfiles = { showProfiles = true }, onClone = { showClone = true })
            }
        }
    }
    writerFor?.let { tag -> WriteDialog(tag, onDismiss = { writerFor = null }, onWrite = { records -> writerFor = null; viewModel.write(records) }) }
    if (confirmFormat) {
        AlertDialog(
            onDismissRequest = { confirmFormat = false },
            title = { Text("Format this NFC tag as NDEF?") },
            text = { Text("Formatting may overwrite existing tag contents. Keep the same tag against the phone until the operation completes.") },
            dismissButton = { TextButton(onClick = { confirmFormat = false }) { Text("Cancel") } },
            confirmButton = { Button(onClick = { confirmFormat = false; viewModel.formatAsNdef() }) { Text("Format") } }
        )
    }
    profileToSave?.let { tag -> ProfileNameDialog(onDismiss = { profileToSave = null }, onSave = { name -> viewModel.saveReadableNdefProfile(tag, name); profileToSave = null }) }
}

@Composable
private fun HomeScreen(modifier: Modifier, vm: NfcViewModel, state: ScanState, history: List<HistoryItem>, profiles: List<NdefProfile>, activity: MainActivity, openSettings: () -> Unit, onShowHistory: () -> Unit, onShowProfiles: () -> Unit, onClone: () -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Advanced NFC Reader & Writer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Read only the technologies and data Android legitimately exposes. Protected credentials are never bypassed.")
                }
            }
        }
        item { ScanPanel(vm, state, activity, openSettings) }
        item { OutlinedButton(onClick = onClone, modifier = Modifier.fillMaxWidth()) { Text("Clone & Edit NDEF data") } }
        if (state is ScanState.Error) item { FailureCard(state.error, { vm.scan(activity) }) }
        item { Text("Scan history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (history.isEmpty()) item { Text("No completed scans yet. NFC payloads are not saved to history.") }
        else items(history.take(3), key = { it.id }) { entry ->
            InfoCard(entry.uid ?: "UID unavailable") {
                Text("${entry.technologies} · ${entry.records} NDEF record(s)")
                Text("Write access: ${entry.writable?.let { if (it) "Writable" else "Read-only" } ?: "Unknown"}")
                TextButton(onClick = { vm.deleteHistory(entry.id) }) { Text("Remove") }
            }
        }
        if (history.isNotEmpty()) item { TextButton(onClick = onShowHistory, modifier = Modifier.fillMaxWidth()) { Text("View full history (${history.size})") } }
        item { Text("Saved NDEF profiles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item { Text("Profiles contain only readable NDEF messages. They do not copy UID, hardware identity, credentials, or protected data.") }
        item { TextButton(onClick = onShowProfiles, modifier = Modifier.fillMaxWidth()) { Text("Manage profiles (${profiles.size})") } }
    }
}

@Composable
private fun HistoryScreen(modifier: Modifier, history: List<HistoryItem>, onBack: () -> Unit, onDelete: (Long) -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
        item {
            TextButton(onClick = onBack) { Text("Back to NFC Tool") }
            Text("Scan history", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Scan metadata only — NDEF payloads are not stored.")
        }
        if (history.isEmpty()) item { Text("No completed scans yet.") }
        else items(history, key = { it.id }) { entry ->
            InfoCard(entry.uid ?: "UID unavailable") {
                Text(entry.technologies)
                Detail("NDEF records", entry.records.toString())
                Detail("Write access", entry.writable?.let { if (it) "Writable" else "Read-only" } ?: "Unknown")
                Detail("Protection", entry.protection.label)
                TextButton(onClick = { onDelete(entry.id) }) { Text("Delete scan") }
            }
        }
    }
}

@Composable
private fun ProfilesScreen(modifier: Modifier, profiles: List<NdefProfile>, selected: NdefProfile?, onBack: () -> Unit, onUse: (NdefProfile) -> Unit, onDelete: (Long) -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
        item {
            TextButton(onClick = onBack) { Text("Back to NFC Tool") }
            Text("Saved NDEF profiles", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("A profile reproduces only the readable NDEF message on a compatible writable NDEF tag. UID, tag hardware identity, protected applications, and generic card emulation are not copied.")
        }
        selected?.let { profile -> item { InfoCard("Selected for next writable tag") { Text(profile.name); TextButton(onClick = onBack) { Text("Scan a target tag") } } } }
        if (profiles.isEmpty()) item { Text("Save a readable NDEF message from a scan result to create a profile.") }
        else items(profiles, key = { it.id }) { profile ->
            InfoCard(profile.name) {
                Detail("Source UID", profile.sourceUid ?: unavailable())
                Detail("Detected technologies", profile.technologies)
                Detail("NDEF records", profile.recordCount.toString())
                Button(onClick = { onUse(profile) }, modifier = Modifier.fillMaxWidth()) { Text("Use on a writable tag") }
                TextButton(onClick = { onDelete(profile.id) }) { Text("Delete profile") }
            }
        }
        item { Text("Phone card emulation is unavailable for arbitrary physical NFC tags. Use an approved access-control mobile credential or a custom HCE protocol designed for a system you administer.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ProfileNameDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save NDEF profile") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("This saves only the NDEF message Android read. It does not save or clone UID, hardware identity, protected credentials, or inaccessible data."); OutlinedTextField(name, { name = it }, label = { Text("Profile name") }, modifier = Modifier.fillMaxWidth()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { Button(onClick = { onSave(name) }) { Text("Save") } }
    )
}

@Composable
private fun ScanPanel(vm: NfcViewModel, state: ScanState, activity: MainActivity, openSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val status = when (state) {
                ScanState.Scanning -> "Scanning for an NFC tag…"
                is ScanState.TagDetected -> "Tag detected. Reading capabilities…"
                is ScanState.Reading -> "Reading tag information…"
                is ScanState.Writing -> "Writing NDEF data. Keep the tag in place…"
                is ScanState.Formatting -> "Formatting NDEF. Keep the tag in place…"
                else -> if (vm.enabled()) "NFC is enabled" else "NFC is disabled"
            }
            Text("Ready to scan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(status)
            if (!vm.supported()) Text("This device does not support NFC.")
            if (!vm.enabled()) TextButton(onClick = openSettings) { Text("Open NFC settings") }
            Button(
                onClick = { vm.scan(activity) },
                enabled = vm.supported() && vm.enabled() && state !is ScanState.Reading && state !is ScanState.Writing && state !is ScanState.Formatting,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state is ScanState.Scanning) "Re-scan" else "Start scanning") }
        }
    }
}

@Composable
private fun ResultScreen(modifier: Modifier, tag: TagSnapshot, vm: NfcViewModel, activity: MainActivity, onWrite: () -> Unit, onFormat: () -> Unit, onSaveProfile: () -> Unit, selectedProfile: NdefProfile?) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
        item {
            Text("NFC Tag Details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("✓ Tag detected", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("Only information exposed through public Android NFC APIs is shown.")
        }
        item {
            Button(onClick = { vm.scan(activity) }, modifier = Modifier.fillMaxWidth()) { Text("Scan Another Tag") }
            TextButton(onClick = { vm.refreshTagCapabilities() }, modifier = Modifier.fillMaxWidth()) { Text("Re-scan current tag information") }
        }
        item { OverviewCard(tag) }
        item { NdefStatusCard(tag, onFormat) }
        item { CapabilityMatrix(tag) }
        item { Text("NDEF data", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (tag.ndefRecords.isEmpty()) item { NdefDiagnosisCard(tag) }
        else items(tag.ndefRecords, key = { it.rawHex }) { record -> NdefRecordCard(record) }
        item { WriteActions(tag, onWrite, onFormat, selectedProfile, vm::writeSelectedProfile, vm::clearSelectedProfile) }
        if (tag.rawNdef != null) item { OutlinedButton(onClick = onSaveProfile, modifier = Modifier.fillMaxWidth()) { Text("Save readable NDEF as profile") } }
        item { TechnologiesCard(tag) }
        item { SecurityCard(tag) }
        item { TechnicalDetailsCard(tag) }
    }
}

@Composable
private fun OverviewCard(tag: TagSnapshot) = InfoCard("Tag overview") {
    Detail("UID", tag.uid ?: unavailable())
    Detail("NDEF", tag.ndefAvailability.label)
    Detail("Write access", tag.writable?.let { if (it) "Writable" else "Read-only" } ?: if (tag.formatable) "Available after NDEF formatting" else "Unknown")
    Detail("Protection", tag.protection.label)
}

@Composable
private fun NdefStatusCard(tag: TagSnapshot, onFormat: () -> Unit) = InfoCard("NDEF status") {
    Detail("Status", tag.ndefAvailability.label)
    Detail("Formatable", if (tag.formatable) "Yes" else "No")
    Detail("Capacity", tag.maxSize?.let { "$it bytes" } ?: unavailable())
    Detail("Current size", tag.ndefSize?.let { "$it bytes" } ?: unavailable())
    Detail("Records", tag.ndefRecords.size.toString())
    Text(tag.ndefDiagnosis, style = MaterialTheme.typography.bodySmall)
    if (tag.formatable && tag.ndefAvailability == NdefAvailability.FORMATABLE) TextButton(onClick = onFormat) { Text("Format as NDEF") }
}

@Composable
private fun NdefDiagnosisCard(tag: TagSnapshot) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("No readable NDEF records", fontWeight = FontWeight.Bold)
        Text(tag.ndefDiagnosis)
        Text("This does not mean the card has no data. It may use another technology, require authentication, or expose no public Android memory interface.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CapabilityMatrix(tag: TagSnapshot) = InfoCard("Capability matrix") {
    Capability("Tag detected", true, "Android delivered this tag to the app.")
    Capability("UID available", tag.uid != null, "Some Android devices do not expose a stable tag ID.")
    Capability("NDEF", tag.ndefAvailability == NdefAvailability.AVAILABLE || tag.ndefAvailability == NdefAvailability.EMPTY, tag.ndefDiagnosis)
    Capability("NDEF formatting", tag.formatable, "Shown only when Android exposes NdefFormatable.")
    Capability("NDEF writing", tag.writable == true, if (tag.formatable) "Available after successful formatting." else "Requires an Android-exposed writable NDEF interface.")
    tag.technologies.forEach { Capability(it.name, true, "Detected by Android.") }
}

@Composable
private fun Capability(name: String, available: Boolean, explanation: String) {
    Text("${if (available) "✓" else "✕"} $name: ${if (available) "Available" else "Not available"}", fontWeight = FontWeight.Medium)
    Text(explanation, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun NdefRecordCard(record: NdefRecordInfo) = InfoCard("${record.kind} record") {
    Detail("Type", record.type)
    record.language?.let { Detail("Language", it) }
    Detail("Payload", record.value)
    Detail("Raw payload", record.rawHex)
}

@Composable
private fun WriteActions(tag: TagSnapshot, onWrite: () -> Unit, onFormat: () -> Unit, selectedProfile: NdefProfile?, writeSelectedProfile: () -> Unit, clearSelectedProfile: () -> Unit) = InfoCard("Actions") {
    when {
        tag.writable == true -> {
            Button(onClick = onWrite, modifier = Modifier.fillMaxWidth()) { Text("Write new NDEF data") }
            selectedProfile?.let { profile ->
                Spacer(Modifier.height(6.dp))
                Button(onClick = writeSelectedProfile, modifier = Modifier.fillMaxWidth()) { Text("Write profile: ${profile.name}") }
                TextButton(onClick = clearSelectedProfile) { Text("Clear selected profile") }
            }
        }
        tag.formatable && tag.ndefAvailability == NdefAvailability.FORMATABLE -> Button(onClick = onFormat, modifier = Modifier.fillMaxWidth()) { Text("Format as NDEF") }
        else -> Text("Writing unavailable: ${if (tag.writable == false) "the exposed NDEF interface is read-only." else "no writable NDEF interface is currently exposed."}")
    }
}

@Composable
private fun TechnologiesCard(tag: TagSnapshot) = InfoCard("Detected technologies") {
    if (tag.technologies.isEmpty()) Text(unavailable())
    tag.technologies.forEach { tech ->
        Text("✓ ${tech.name}", fontWeight = FontWeight.Bold)
        tech.values.forEach { Detail(it.first, it.second) }
    }
}

@Composable
private fun SecurityCard(tag: TagSnapshot) = InfoCard("Security & protection") {
    Detail("Protection", tag.protection.label)
    Detail("Authentication", "Unknown — not tested")
    Detail("Encryption", "Unknown — not inferred")
    Detail("Write access", tag.writable?.let { if (it) "Writable NDEF interface" else "Read-only NDEF interface" } ?: "Unknown")
    Text(tag.protectionReason, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TechnicalDetailsCard(tag: TagSnapshot) = InfoCard("Technical details") {
    Detail("Tag ID", tag.uid ?: unavailable())
    Detail("Raw NDEF", tag.rawNdef?.joinToString(" ") { "%02X".format(it) } ?: unavailable())
    Text("Raw data is displayed read-only. This app does not authenticate, brute-force, or access protected credentials.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun WriteDialog(tag: TagSnapshot, onDismiss: () -> Unit, onWrite: (List<NdefRecord>) -> Unit) {
    var type by remember { mutableStateOf("Text") }
    var value by remember { mutableStateOf("") }
    var metadata by remember { mutableStateOf("en") }
    var confirm by remember { mutableStateOf(false) }
    val record = remember(type, value, metadata) { runCatching { when (type) { "Text" -> NdefCodec.text(value, metadata); "URL" -> NdefCodec.uri(value); "MIME" -> NdefCodec.mime(metadata, value); "External" -> NdefCodec.external(metadata, value); else -> NdefCodec.raw(value) } }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write NDEF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("Text", "URL", "MIME", "External", "Raw").forEach { choice -> FilterChip(selected = type == choice, onClick = { type = choice }, label = { Text(choice) }) } }
                OutlinedTextField(value, { value = it }, label = { Text(if (type == "Raw") "Hex payload" else "Content") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (type == "Text" || type == "MIME" || type == "External") OutlinedTextField(metadata, { metadata = it }, label = { Text(if (type == "Text") "Language" else if (type == "MIME") "MIME type" else "domain:type") }, modifier = Modifier.fillMaxWidth())
                Detail("Capacity", tag.maxSize?.let { "$it bytes" } ?: unavailable())
                Text("Preview: one $type record", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { Button(onClick = { confirm = true }, enabled = value.isNotBlank() && record != null) { Text("Continue") } }
    )
    if (confirm && record != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Write preview") },
        text = { Text("This will replace the current NDEF message with one $type record. Keep the tag against the phone until writing finishes.") },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        confirmButton = { Button(onClick = { onWrite(listOf(record)); confirm = false }) { Text("Hold tag & write") } }
    )
}

@Composable
private fun CloneEditScreen(
    modifier: Modifier,
    stage: CloneStage,
    source: TagSnapshot?,
    original: List<EditableNdefRecord>,
    edited: List<EditableNdefRecord>,
    destination: TagSnapshot?,
    activity: MainActivity,
    viewModel: NfcViewModel,
    scanSource: () -> Unit,
    scanDestination: () -> Unit,
    updateRecords: (List<EditableNdefRecord>) -> Unit,
    resetChanges: () -> Unit,
    setStage: (CloneStage) -> Unit,
    close: () -> Unit
) {
    if (stage == CloneStage.INTRO) {
        CloneIntroScreen(modifier, scanSource, close)
        return
    }
    var editing by remember { mutableStateOf<EditableNdefRecord?>(null) }
    val built = remember(edited) { runCatching { edited.map(NdefCodec::build) } }
    val messageSize = built.getOrNull()?.let { android.nfc.NdefMessage(it.toTypedArray()).toByteArray().size }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
        item {
            TextButton(onClick = close) { Text("Back to NFC Tool") }
            Text("Clone & Edit NDEF data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Replicates only public, Android-exposed NDEF records to a compatible writable NDEF tag. UID, hardware identity, protected applications, and credentials are never copied.")
        }
        when (stage) {
            CloneStage.INTRO -> item { }
            CloneStage.SOURCE_SCANNING -> item { ProgressCard("Scanning source tag", "Hold the source tag near the phone.") }
            CloneStage.EDIT -> {
                item { CloneSourceCard(source, original, edited) }
                item { Text("Edited NDEF records", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                if (edited.isEmpty()) item { Text("No reproducible NDEF records were read. Add a new standard NDEF record or choose another source tag.") }
                items(edited, key = { it.id }) { record ->
                    InfoCard("${record.kind} · ${record.type}") {
                        Detail("Value", record.value)
                        if (record.metadata.isNotBlank()) Detail("Metadata", record.metadata)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = record }) { Text("Edit") }
                            TextButton(onClick = { updateRecords(edited.filterNot { it.id == record.id }) }) { Text("Delete") }
                            TextButton(onClick = { updateRecords(edited + record.copy(id = System.nanoTime())) }) { Text("Copy") }
                        }
                    }
                }
                item { OutlinedButton(onClick = { editing = EditableNdefRecord(System.nanoTime(), "Text", "T", "", "en") }, modifier = Modifier.fillMaxWidth()) { Text("Add record") } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = resetChanges, modifier = Modifier.weight(1f)) { Text("Reset changes") }
                        Button(onClick = scanDestination, enabled = built.isSuccess && edited.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Scan destination") }
                    }
                    built.exceptionOrNull()?.let { Text("Cannot build edited message: ${it.message}", color = MaterialTheme.colorScheme.error) }
                }
            }
            CloneStage.DESTINATION_SCANNING -> item { ProgressCard("Scanning destination tag", "Hold a compatible writable NDEF tag near the phone.") }
            CloneStage.PREVIEW -> item {
                ClonePreview(source, edited, destination, messageSize, onWrite = {
                    built.getOrNull()?.let { records ->
                        setStage(CloneStage.WRITING)
                        viewModel.write(records)
                    }
                }, onRescan = scanDestination)
            }
            CloneStage.WRITING -> item { ProgressCard("Writing and verifying", "Keep the destination tag against the phone. The app will re-read it and compare the NDEF message.") }
            CloneStage.SUCCESS -> item { SuccessCard("Clone & write successful", "The destination NDEF message matches the edited NDEF message. ${edited.size} record(s) written.", close) }
            CloneStage.VERIFY_FAILED -> item { FailureCard(NfcFailure.Simple("Write verification did not pass", "The re-read destination NDEF message did not match the edited message.", "Keep the destination tag in place and try again."), scanDestination) }
        }
    }
    editing?.let { record -> EditNdefRecordDialog(record, onDismiss = { editing = null }, onSave = { changed -> updateRecords(edited.filterNot { it.id == changed.id } + changed); editing = null }) }
}

@Composable
private fun CloneIntroScreen(modifier: Modifier, scanSource: () -> Unit, close: () -> Unit) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 20.dp)
    ) {
        item {
            TextButton(onClick = close) { Text("Back to NFC Tool") }
            Text("Clone & Edit NDEF data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item {
            InfoCard("NDEF data replication") {
                Text("Create an editable copy of public NDEF records and write it to a compatible writable NDEF tag.")
                Text("Not copied: UID, physical tag identity, protected applications, credentials, authentication data, or inaccessible memory.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Button(onClick = scanSource, modifier = Modifier.fillMaxWidth()) { Text("Scan source tag") }
        }
    }
}

@Composable
private fun CloneSourceCard(source: TagSnapshot?, original: List<EditableNdefRecord>, edited: List<EditableNdefRecord>) = InfoCard("Source tag") {
    Detail("UID", source?.uid ?: unavailable())
    Detail("Original records", original.size.toString())
    Detail("Edited records", edited.size.toString())
    Text("Original data remains unchanged in memory; Reset changes restores it.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ClonePreview(source: TagSnapshot?, edited: List<EditableNdefRecord>, destination: TagSnapshot?, required: Int?, onWrite: () -> Unit, onRescan: () -> Unit) = InfoCard("Clone preview") {
    Text("SOURCE", fontWeight = FontWeight.Bold)
    Text(source?.ndefRecords?.joinToString { "${it.kind}: ${it.value}" } ?: unavailable())
    Text("EDITED", fontWeight = FontWeight.Bold)
    Text(edited.joinToString { "${it.kind}: ${it.value}" })
    Text("DESTINATION", fontWeight = FontWeight.Bold)
    Detail("NDEF", destination?.ndefAvailability?.label ?: unavailable())
    Detail("Writable", destination?.writable?.let { if (it) "Yes" else "No" } ?: "No writable interface exposed")
    Detail("Capacity", destination?.maxSize?.let { "$it bytes" } ?: unavailable())
    Detail("Required", required?.let { "$it bytes" } ?: "Unable to calculate")
    val canWrite = destination?.writable == true && required != null && destination.maxSize != null && required <= destination.maxSize
    if (!canWrite) Text("Writing is unavailable until Android exposes a writable NDEF interface with sufficient capacity.", color = MaterialTheme.colorScheme.error)
    Button(onClick = onWrite, enabled = canWrite, modifier = Modifier.fillMaxWidth()) { Text("Write to destination") }
    TextButton(onClick = onRescan) { Text("Scan another destination") }
}

@Composable
private fun EditNdefRecordDialog(record: EditableNdefRecord, onDismiss: () -> Unit, onSave: (EditableNdefRecord) -> Unit) {
    var kind by remember { mutableStateOf(record.kind) }
    var type by remember { mutableStateOf(record.type) }
    var value by remember { mutableStateOf(record.value) }
    var metadata by remember { mutableStateOf(record.metadata) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit NDEF record") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("Text", "URI", "MIME", "External type", "Smart Poster").forEach { choice -> FilterChip(selected = kind == choice, onClick = { kind = choice }, label = { Text(choice) }) } }
            OutlinedTextField(value, { value = it }, label = { Text("Value") }, modifier = Modifier.fillMaxWidth())
            if (kind == "Text" || kind == "MIME" || kind == "External type" || kind == "Smart Poster") OutlinedTextField(metadata, { metadata = it }, label = { Text(if (kind == "Text") "Language" else if (kind == "Smart Poster") "Title" else "Type") }, modifier = Modifier.fillMaxWidth())
            if (kind == "MIME" || kind == "External type") OutlinedTextField(type, { type = it }, label = { Text(if (kind == "MIME") "MIME type" else "domain:type") }, modifier = Modifier.fillMaxWidth())
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { Button(onClick = { onSave(record.copy(kind = kind, type = type, value = value, metadata = metadata)) }, enabled = value.isNotBlank()) { Text("Save changes") } }
    )
}

@Composable
private fun ProgressCard(title: String, detail: String) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(); Text(title, fontWeight = FontWeight.Bold); Text(detail) } }

@Composable
private fun SuccessCard(title: String, detail: String, onClose: () -> Unit) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("✓ $title", fontWeight = FontWeight.Bold); Text(detail); Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Clone another tag") } } }

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium); content() } }
}

@Composable private fun Detail(label: String, value: String) { Text("$label: $value", style = MaterialTheme.typography.bodyMedium) }
@Composable private fun FailureCard(error: NfcFailure, retry: () -> Unit) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(error.message, fontWeight = FontWeight.Bold); Text(error.action); Text(error.technical, style = MaterialTheme.typography.bodySmall); Button(onClick = retry) { Text("Try again") } } }
private fun unavailable() = "Unavailable through Android API"

private enum class CloneStage { INTRO, SOURCE_SCANNING, EDIT, DESTINATION_SCANNING, PREVIEW, WRITING, SUCCESS, VERIFY_FAILED }
private fun vmScan(viewModel: NfcViewModel, activity: MainActivity) = viewModel.scan(activity)
