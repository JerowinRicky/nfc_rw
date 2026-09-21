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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
    var multiCloneStage by remember { mutableStateOf(MultiCloneStage.IDLE) }
    var cloneSource by remember { mutableStateOf<TagSnapshot?>(null) }
    var cloneOriginal by remember { mutableStateOf<List<EditableNdefRecord>>(emptyList()) }
    var cloneEdited by remember { mutableStateOf<List<EditableNdefRecord>>(emptyList()) }
    var cloneDestination by remember { mutableStateOf<TagSnapshot?>(null) }

    LaunchedEffect(state, multiCloneStage) {
        val tag = when (val s = state) {
            is ScanState.Success -> s.tag
            is ScanState.Partial -> s.tag
            else -> null
        }
        when {
            multiCloneStage == MultiCloneStage.SOURCE_SCANNING && tag != null -> {
                cloneSource = tag
                cloneOriginal = tag.ndefRecords.mapIndexed { i, r -> NdefCodec.editable(r, i.toLong()) }
                cloneEdited = cloneOriginal
                multiCloneStage = MultiCloneStage.SOURCE_READY
            }
            multiCloneStage == MultiCloneStage.DEST_SCANNING && tag != null -> {
                cloneDestination = tag
                multiCloneStage = MultiCloneStage.DEST_READY
            }
            multiCloneStage == MultiCloneStage.WRITING && tag != null -> {
                val expected = runCatching {
                    android.nfc.NdefMessage(cloneEdited.map(NdefCodec::build).toTypedArray()).toByteArray()
                }.getOrNull()
                multiCloneStage = if (expected != null && tag.rawNdef?.contentEquals(expected) == true)
                    MultiCloneStage.SUCCESS else MultiCloneStage.VERIFY_FAILED
            }
        }
    }

    val resetClone: () -> Unit = {
        cloneSource = null; cloneOriginal = emptyList()
        cloneEdited = emptyList(); cloneDestination = null
        multiCloneStage = MultiCloneStage.IDLE
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Column { Text("NFC Tool"); Text("Reader, analyzer & NDEF writer", style = MaterialTheme.typography.labelSmall) } }) }
    ) { padding ->
        val cm = Modifier.padding(padding).consumeWindowInsets(padding)
        val onScanSource: () -> Unit = { multiCloneStage = MultiCloneStage.SOURCE_SCANNING; viewModel.scan(activity) }
        val onScanDest: () -> Unit  = { multiCloneStage = MultiCloneStage.DEST_SCANNING; viewModel.scan(activity) }

        when (val current = state) {
            is ScanState.Success -> if (showClone) MultiFormatCloneScreen(cm, multiCloneStage, cloneSource, cloneOriginal, cloneEdited, cloneDestination, viewModel, onScanSource, onScanDest, { cloneEdited = it }, { cloneEdited = cloneOriginal }, { multiCloneStage = it }, resetClone) { showClone = false; resetClone() }
                                   else ResultScreen(cm, current.tag, viewModel, activity, { writerFor = current.tag }, { confirmFormat = true }, { profileToSave = current.tag }, selectedProfile)
            is ScanState.Partial -> if (showClone) MultiFormatCloneScreen(cm, multiCloneStage, cloneSource, cloneOriginal, cloneEdited, cloneDestination, viewModel, onScanSource, onScanDest, { cloneEdited = it }, { cloneEdited = cloneOriginal }, { multiCloneStage = it }, resetClone) { showClone = false; resetClone() }
                                   else ResultScreen(cm, current.tag, viewModel, activity, { writerFor = current.tag }, { confirmFormat = true }, { profileToSave = current.tag }, selectedProfile)
            else -> when {
                showHistory  -> HistoryScreen(cm, history, onBack = { showHistory = false }, onDelete = viewModel::deleteHistory)
                showClone    -> MultiFormatCloneScreen(cm, multiCloneStage, cloneSource, cloneOriginal, cloneEdited, cloneDestination, viewModel, onScanSource, onScanDest, { cloneEdited = it }, { cloneEdited = cloneOriginal }, { multiCloneStage = it }, resetClone) { showClone = false; resetClone() }
                showProfiles -> ProfilesScreen(cm, profiles, selectedProfile, onBack = { showProfiles = false }, onUse = { viewModel.selectProfile(it); showProfiles = false }, onDelete = viewModel::deleteProfile)
                else         -> HomeScreen(cm, viewModel, current, history, profiles, activity, openSettings, onShowHistory = { showHistory = true }, onShowProfiles = { showProfiles = true }, onClone = { showClone = true })
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

// ─────────────────────────────────────────────────────────────
//  Home / History / Profiles / Scan screens  (unchanged)
// ─────────────────────────────────────────────────────────────

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
        item { OutlinedButton(onClick = onClone, modifier = Modifier.fillMaxWidth()) { Text("Clone & Edit Tag (Multi-Format)") } }
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

@Composable private fun OverviewCard(tag: TagSnapshot) = InfoCard("Tag overview") {
    Detail("UID", tag.uid ?: unavailable())
    Detail("NDEF", tag.ndefAvailability.label)
    Detail("Write access", tag.writable?.let { if (it) "Writable" else "Read-only" } ?: if (tag.formatable) "Available after NDEF formatting" else "Unknown")
    Detail("Protection", tag.protection.label)
}

@Composable private fun NdefStatusCard(tag: TagSnapshot, onFormat: () -> Unit) = InfoCard("NDEF status") {
    Detail("Status", tag.ndefAvailability.label)
    Detail("Formatable", if (tag.formatable) "Yes" else "No")
    Detail("Capacity", tag.maxSize?.let { "$it bytes" } ?: unavailable())
    Detail("Current size", tag.ndefSize?.let { "$it bytes" } ?: unavailable())
    Detail("Records", tag.ndefRecords.size.toString())
    Text(tag.ndefDiagnosis, style = MaterialTheme.typography.bodySmall)
    if (tag.formatable && tag.ndefAvailability == NdefAvailability.FORMATABLE) TextButton(onClick = onFormat) { Text("Format as NDEF") }
}

@Composable private fun NdefDiagnosisCard(tag: TagSnapshot) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("No readable NDEF records", fontWeight = FontWeight.Bold)
        Text(tag.ndefDiagnosis)
        Text("This does not mean the card has no data. It may use another technology, require authentication, or expose no public Android memory interface.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun CapabilityMatrix(tag: TagSnapshot) = InfoCard("Capability matrix") {
    Capability("Tag detected", true, "Android delivered this tag to the app.")
    Capability("UID available", tag.uid != null, "Some Android devices do not expose a stable tag ID.")
    Capability("NDEF", tag.ndefAvailability == NdefAvailability.AVAILABLE || tag.ndefAvailability == NdefAvailability.EMPTY, tag.ndefDiagnosis)
    Capability("NDEF formatting", tag.formatable, "Shown only when Android exposes NdefFormatable.")
    Capability("NDEF writing", tag.writable == true, if (tag.formatable) "Available after successful formatting." else "Requires an Android-exposed writable NDEF interface.")
    tag.technologies.forEach { Capability(it.name, true, "Detected by Android.") }
}

@Composable private fun Capability(name: String, available: Boolean, explanation: String) {
    Text("${if (available) "✓" else "✕"} $name: ${if (available) "Available" else "Not available"}", fontWeight = FontWeight.Medium)
    Text(explanation, style = MaterialTheme.typography.bodySmall)
}

@Composable private fun NdefRecordCard(record: NdefRecordInfo) = InfoCard("${record.kind} record") {
    Detail("Type", record.type)
    record.language?.let { Detail("Language", it) }
    Detail("Payload", record.value)
    Detail("Raw payload", record.rawHex)
}

@Composable private fun WriteActions(tag: TagSnapshot, onWrite: () -> Unit, onFormat: () -> Unit, selectedProfile: NdefProfile?, writeSelectedProfile: () -> Unit, clearSelectedProfile: () -> Unit) = InfoCard("Actions") {
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

@Composable private fun TechnologiesCard(tag: TagSnapshot) = InfoCard("Detected technologies") {
    if (tag.technologies.isEmpty()) Text(unavailable())
    tag.technologies.forEach { tech ->
        Text("✓ ${tech.name}", fontWeight = FontWeight.Bold)
        tech.values.forEach { Detail(it.first, it.second) }
    }
}

@Composable private fun SecurityCard(tag: TagSnapshot) = InfoCard("Security & protection") {
    Detail("Protection", tag.protection.label)
    Detail("Authentication", "Unknown — not tested")
    Detail("Encryption", "Unknown — not inferred")
    Detail("Write access", tag.writable?.let { if (it) "Writable NDEF interface" else "Read-only NDEF interface" } ?: "Unknown")
    Text(tag.protectionReason, style = MaterialTheme.typography.bodySmall)
}

@Composable private fun TechnicalDetailsCard(tag: TagSnapshot) = InfoCard("Technical details") {
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

// ─────────────────────────────────────────────────────────────
//  Multi-Format Clone & Edit  (new)
// ─────────────────────────────────────────────────────────────

@Composable
private fun MultiFormatCloneScreen(
    modifier: Modifier,
    stage: MultiCloneStage,
    source: TagSnapshot?,
    original: List<EditableNdefRecord>,
    edited: List<EditableNdefRecord>,
    destination: TagSnapshot?,
    viewModel: NfcViewModel,
    onScanSource: () -> Unit,
    onScanDest: () -> Unit,
    onUpdateRecords: (List<EditableNdefRecord>) -> Unit,
    onReset: () -> Unit,
    onSetStage: (MultiCloneStage) -> Unit,
    onCloneAnother: () -> Unit,
    onClose: () -> Unit
) {
    var editing by remember { mutableStateOf<EditableNdefRecord?>(null) }
    val built = remember(edited) { runCatching { edited.map(NdefCodec::build) } }
    val messageSize = built.getOrNull()?.let { android.nfc.NdefMessage(it.toTypedArray()).toByteArray().size }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // ── Header ──────────────────────────────────────────
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("← Back") }
                if (source != null && stage != MultiCloneStage.SOURCE_SCANNING) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onScanSource) { Text("Re-scan source") }
                }
            }
            Text("Clone & Edit Tag", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "All Android-exposed formats are shown (NFC-A/B/F/V, MIFARE, ISO-DEP, NDEF). " +
                        "Only NDEF records can be written to a destination tag via Android's public API.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── Flow indicator ───────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowStep("SOURCE", stage >= MultiCloneStage.SOURCE_READY)
                FlowArrow()
                FlowStep("READ", stage >= MultiCloneStage.SOURCE_READY)
                FlowArrow()
                FlowStep("EDIT", stage >= MultiCloneStage.DEST_READY)
                FlowArrow()
                FlowStep("WRITE", stage >= MultiCloneStage.SUCCESS)
                FlowArrow()
                FlowStep("VERIFY", stage == MultiCloneStage.SUCCESS)
            }
        }

        // ── SOURCE card ─────────────────────────────────────
        item {
            SourceTagCard(
                tag = source,
                isScanning = stage == MultiCloneStage.SOURCE_SCANNING,
                onScan = onScanSource
            )
        }

        // ── DESTINATION / result area ────────────────────────
        when (stage) {
            MultiCloneStage.IDLE -> item {
                DestinationTagCard(
                    tag = null, sourceScanned = false, original = original, edited = edited,
                    isScanning = false, canScan = false, messageSize = null,
                    onScan = onScanDest, onUpdateRecords = onUpdateRecords,
                    onEdit = { editing = it }, onReset = onReset, onWrite = {}
                )
            }

            MultiCloneStage.SOURCE_SCANNING -> { /* source card already shows progress spinner */ }

            MultiCloneStage.SOURCE_READY -> item {
                DestinationTagCard(
                    tag = null, sourceScanned = true, original = original, edited = edited,
                    isScanning = false, canScan = true, messageSize = messageSize,
                    onScan = onScanDest, onUpdateRecords = onUpdateRecords,
                    onEdit = { editing = it }, onReset = onReset,
                    onWrite = {
                        built.getOrNull()?.let { records ->
                            onSetStage(MultiCloneStage.WRITING)
                            viewModel.write(records)
                        }
                    }
                )
            }

            MultiCloneStage.DEST_SCANNING -> item {
                DestinationTagCard(
                    tag = null, sourceScanned = true, original = original, edited = edited,
                    isScanning = true, canScan = true, messageSize = messageSize,
                    onScan = onScanDest, onUpdateRecords = onUpdateRecords,
                    onEdit = { editing = it }, onReset = onReset, onWrite = {}
                )
            }

            MultiCloneStage.DEST_READY -> item {
                DestinationTagCard(
                    tag = destination, sourceScanned = true, original = original, edited = edited,
                    isScanning = false, canScan = true, messageSize = messageSize,
                    onScan = onScanDest, onUpdateRecords = onUpdateRecords,
                    onEdit = { editing = it }, onReset = onReset,
                    onWrite = {
                        built.getOrNull()?.let { records ->
                            onSetStage(MultiCloneStage.WRITING)
                            viewModel.write(records)
                        }
                    }
                )
            }

            MultiCloneStage.WRITING -> item {
                ProgressCard("Writing & verifying", "Keep the destination tag against the phone. The app will re-read it and compare the NDEF message byte-for-byte.")
            }

            MultiCloneStage.SUCCESS -> item {
                CloneSuccessCard(
                    recordCount = edited.size,
                    destination = destination,
                    edited = edited,
                    onEditAgain = { onSetStage(MultiCloneStage.DEST_READY) },
                    onCloneAnother = onCloneAnother,
                    onViewDetails = { onSetStage(MultiCloneStage.DEST_READY) }
                )
            }

            MultiCloneStage.VERIFY_FAILED -> item {
                FailureCard(
                    NfcFailure.Simple(
                        "Write verification failed",
                        "The re-read destination NDEF message did not match the edited message byte-for-byte.",
                        "Keep the destination tag in place and tap 'Try again'."
                    ),
                    onScanDest
                )
            }
        }

        // NDEF build errors
        built.exceptionOrNull()?.let { err ->
            item {
                Text(
                    "Cannot build NDEF message: ${err.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    // Edit-record dialog (hoisted here so it overlays the LazyColumn)
    editing?.let { record ->
        EditNdefRecordDialog(
            record = record,
            onDismiss = { editing = null },
            onSave = { changed ->
                onUpdateRecords(edited.filterNot { it.id == changed.id } + changed)
                editing = null
            }
        )
    }
}

// ── SOURCE TAG CARD ────────────────────────────────────────────

@Composable
private fun SourceTagCard(tag: TagSnapshot?, isScanning: Boolean, onScan: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (tag != null) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Card header badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        "  SOURCE TAG  ",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (tag != null) {
                    Text("✓ Read", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }

            when {
                isScanning -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Hold source tag against the phone…")
                    }
                }

                tag == null -> {
                    Text("Tap to scan your source NFC tag.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "All Android-exposed data is captured: UID, technology stack (NFC-A/B/F/V, MIFARE, ISO-DEP), " +
                                "NDEF records, and the raw NDEF byte sequence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan Source Tag")
                    }
                }

                else -> {
                    // ── Overview ──
                    TagSectionHeader("Overview")
                    Detail("UID", tag.uid ?: unavailable())
                    Detail("NDEF", tag.ndefAvailability.label)
                    Detail("Write access", tag.writable?.let { if (it) "Writable" else "Read-only" } ?: "Unknown")
                    Detail("Protection", tag.protection.label)

                    HorizontalDivider()

                    // ── Technology stack ──
                    TagSectionHeader("Technology Stack (${tag.technologies.size} detected)")
                    if (tag.technologies.isEmpty()) {
                        Text(unavailable(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        tag.technologies.forEach { tech ->
                            Text("✓ ${tech.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            tech.values.forEach { (key, value) ->
                                Row(
                                    Modifier.padding(start = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("$key: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // ── NDEF Records ──
                    TagSectionHeader("NDEF Records (${tag.ndefRecords.size})")
                    if (tag.ndefRecords.isEmpty()) {
                        Text(tag.ndefDiagnosis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        tag.ndefRecords.forEachIndexed { i, record ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Record ${i + 1} · ${record.kind}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    Detail("Type", record.type)
                                    record.language?.let { Detail("Language", it) }
                                    Detail("Value", record.value)
                                    val hexPrev = if (record.rawHex.length > 90) record.rawHex.take(90) + "…" else record.rawHex
                                    Detail("Raw hex", hexPrev)
                                }
                            }
                        }
                    }

                    // ── Raw NDEF bytes ──
                    tag.rawNdef?.let { raw ->
                        HorizontalDivider()
                        TagSectionHeader("Raw NDEF Message (${raw.size} bytes)")
                        val hex = raw.joinToString(" ") { "%02X".format(it) }
                        Text(
                            if (hex.length > 260) hex.take(260) + " …" else hex,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Displayed read-only. Original source data is never modified.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ── DESTINATION TAG CARD ───────────────────────────────────────

@Composable
private fun DestinationTagCard(
    tag: TagSnapshot?,
    sourceScanned: Boolean,
    original: List<EditableNdefRecord>,
    edited: List<EditableNdefRecord>,
    isScanning: Boolean,
    canScan: Boolean,
    messageSize: Int?,
    onScan: () -> Unit,
    onUpdateRecords: (List<EditableNdefRecord>) -> Unit,
    onEdit: (EditableNdefRecord) -> Unit,
    onReset: () -> Unit,
    onWrite: () -> Unit
) {
    val canWrite = tag?.writable == true &&
            edited.isNotEmpty() &&
            messageSize != null && messageSize > 0 &&
            tag.maxSize != null && messageSize <= tag.maxSize

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                canWrite -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Card header badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.secondary,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        "  DESTINATION TAG  ",
                        color = MaterialTheme.colorScheme.onSecondary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (tag != null) {
                    Text(
                        if (canWrite) "✓ Writable" else "Not writable",
                        color = if (canWrite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ── Editable NDEF records (shown once source is scanned) ──
            if (sourceScanned) {
                TagSectionHeader("NDEF Records to Write")
                Text(
                    "Edit records below. Original source data is never changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Change summary chips
                if (original.isNotEmpty()) {
                    val modifiedCount = edited.count { e -> original.any { o -> o.id == e.id && o != e } }
                    val addedCount = edited.count { e -> original.none { o -> o.id == e.id } }
                    val deletedCount = original.count { o -> edited.none { e -> e.id == o.id } }
                    if (modifiedCount > 0 || addedCount > 0 || deletedCount > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (modifiedCount > 0) AssistChip(onClick = {}, label = { Text("$modifiedCount modified") })
                            if (addedCount > 0) AssistChip(onClick = {}, label = { Text("$addedCount added") })
                            if (deletedCount > 0) AssistChip(onClick = {}, label = { Text("$deletedCount deleted") })
                        }
                    }
                }

                if (edited.isEmpty()) {
                    Text(
                        "No records. Tap '+ Add Record' to create one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    edited.forEachIndexed { i, record ->
                        val isNew = original.none { o -> o.id == record.id }
                        val isModified = !isNew && original.any { o -> o.id == record.id && o != record }
                        EditableRecordItem(
                            index = i,
                            record = record,
                            isNew = isNew,
                            isModified = isModified,
                            onEdit = { onEdit(record) },
                            onDelete = { onUpdateRecords(edited.filterNot { it.id == record.id }) },
                            onCopy = { onUpdateRecords(edited + record.copy(id = System.nanoTime())) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onUpdateRecords(edited + EditableNdefRecord(System.nanoTime(), "Text", "T", "", "en")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("+ Add Record") }
                    OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("Reset Changes") }
                }

                messageSize?.let {
                    Text("Message size: $it bytes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider()
            }

            // ── Scanning state ──
            if (isScanning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Hold destination tag against the phone…")
                }
                return@Column
            }

            // ── No destination scanned yet ──
            if (tag == null) {
                Text(
                    if (canScan) "Scan a writable NFC tag to write the records above to it."
                    else "Scan the source tag first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth(), enabled = canScan) {
                    Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Destination Tag")
                }
                return@Column
            }

            // ── Destination tag info (after scan) ──
            TagSectionHeader("Destination Tag Info")
            Detail("UID", tag.uid ?: unavailable())
            Detail("NDEF", tag.ndefAvailability.label)
            Detail("Writable", tag.writable?.let { if (it) "Yes" else "No" } ?: "Unknown")
            Detail("Capacity", tag.maxSize?.let { "$it bytes" } ?: unavailable())
            messageSize?.let { req ->
                Detail("Required", "$req bytes")
                tag.maxSize?.let { cap ->
                    if (req <= cap) Detail("Available after write", "${cap - req} bytes")
                }
            }

            // Destination technology stack (read-only reference)
            if (tag.technologies.isNotEmpty()) {
                Text(
                    "Technologies: ${tag.technologies.joinToString { it.name }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Error if can't write
            if (!canWrite) {
                Text(
                    when {
                        edited.isEmpty() -> "Add at least one NDEF record above before writing."
                        tag.writable == false -> "This tag's NDEF interface is read-only."
                        messageSize != null && tag.maxSize != null && messageSize > tag.maxSize ->
                            "Insufficient capacity: ${messageSize}B needed, ${tag.maxSize}B available."
                        else -> "No writable NDEF interface is exposed by Android for this tag."
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(onClick = onWrite, enabled = canWrite, modifier = Modifier.fillMaxWidth()) {
                Text("Write to Destination Tag")
            }
            TextButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan Different Destination") }
        }
    }
}

// ── EDITABLE RECORD ITEM ───────────────────────────────────────

@Composable
private fun EditableRecordItem(
    index: Int,
    record: EditableNdefRecord,
    isNew: Boolean,
    isModified: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Record ${index + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                    Text("  ${record.kind}  ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
                when {
                    isNew -> Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                        Text("  NEW  ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                    isModified -> Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                        Text("  EDITED  ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                }
            }
            if (record.type.isNotBlank() && record.type !in listOf("T", "U", "Sp")) {
                Text("Type: ${record.type}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Value: ${record.value.ifBlank { "(empty)" }}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (record.metadata.isNotBlank()) {
                Text("Metadata: ${record.metadata}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text("Edit") }
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text("Delete") }
                TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text("Copy") }
            }
        }
    }
}

// ── CLONE SUCCESS CARD ─────────────────────────────────────────

@Composable
private fun CloneSuccessCard(
    recordCount: Int,
    destination: TagSnapshot?,
    edited: List<EditableNdefRecord>,
    onEditAgain: () -> Unit,
    onCloneAnother: () -> Unit,
    onViewDetails: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "✓ Clone & Write Successful",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Detail("Records written", recordCount.toString())
            Detail("Verification", "Passed — destination NDEF matches edited message")
            destination?.maxSize?.let { Detail("Destination capacity", "$it bytes") }

            HorizontalDivider()

            Text("Written Records", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            edited.forEachIndexed { i, r ->
                val preview = if (r.value.length > 72) r.value.take(72) + "…" else r.value
                Text("${i + 1}. ${r.kind}: $preview", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            Button(onClick = onEditAgain, modifier = Modifier.fillMaxWidth()) { Text("Edit Again") }
            OutlinedButton(onClick = onCloneAnother, modifier = Modifier.fillMaxWidth()) { Text("Clone Another Tag") }
            TextButton(onClick = onViewDetails, modifier = Modifier.fillMaxWidth()) { Text("View Details") }
        }
    }
}

// ── EDIT NDEF RECORD DIALOG ────────────────────────────────────

@Composable
private fun EditNdefRecordDialog(record: EditableNdefRecord, onDismiss: () -> Unit, onSave: (EditableNdefRecord) -> Unit) {
    var kind by remember { mutableStateOf(record.kind) }
    var type by remember { mutableStateOf(record.type) }
    var value by remember { mutableStateOf(record.value) }
    var metadata by remember { mutableStateOf(record.metadata) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit NDEF record") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Text", "URI", "MIME", "External type", "Smart Poster").forEach { choice ->
                        FilterChip(selected = kind == choice, onClick = { kind = choice }, label = { Text(choice) })
                    }
                }
                OutlinedTextField(value, { value = it }, label = { Text("Value") }, modifier = Modifier.fillMaxWidth())
                if (kind in listOf("Text", "MIME", "External type", "Smart Poster")) {
                    OutlinedTextField(
                        metadata, { metadata = it },
                        label = {
                            Text(
                                when (kind) {
                                    "Text" -> "Language (e.g. en)"
                                    "Smart Poster" -> "Title"
                                    else -> "Type"
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (kind in listOf("MIME", "External type")) {
                    OutlinedTextField(
                        type, { type = it },
                        label = { Text(if (kind == "MIME") "MIME type" else "domain:type") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(
                onClick = { onSave(record.copy(kind = kind, type = type, value = value, metadata = metadata)) },
                enabled = value.isNotBlank()
            ) { Text("Save Changes") }
        }
    )
}

// ─────────────────────────────────────────────────────────────
//  Shared helper composables
// ─────────────────────────────────────────────────────────────

@Composable
private fun FlowStep(label: String, active: Boolean) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
    )
}

@Composable
private fun FlowArrow() {
    Text(" → ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TagSectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ProgressCard(title: String, detail: String) =
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator()
            Text(title, fontWeight = FontWeight.Bold)
            Text(detail)
        }
    }

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable private fun Detail(label: String, value: String) { Text("$label: $value", style = MaterialTheme.typography.bodyMedium) }

@Composable private fun FailureCard(error: NfcFailure, retry: () -> Unit) =
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(error.message, fontWeight = FontWeight.Bold)
            Text(error.action)
            Text(error.technical, style = MaterialTheme.typography.bodySmall)
            Button(onClick = retry) { Text("Try again") }
        }
    }

private fun unavailable() = "Unavailable through Android API"

// ─────────────────────────────────────────────────────────────
//  Stage enum
// ─────────────────────────────────────────────────────────────

private enum class MultiCloneStage {
    IDLE, SOURCE_SCANNING, SOURCE_READY, DEST_SCANNING, DEST_READY, WRITING, SUCCESS, VERIFY_FAILED
}
