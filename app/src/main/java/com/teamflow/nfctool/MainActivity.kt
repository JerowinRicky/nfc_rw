@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.teamflow.nfctool

import android.content.Intent
import android.nfc.NdefRecord
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.teamflow.nfctool.domain.*
import com.teamflow.nfctool.nfc.NdefCodec
import com.teamflow.nfctool.presentation.NfcViewModel
import com.teamflow.nfctool.presentation.NfcViewModelFactory

class MainActivity : ComponentActivity() {
    private val viewModel: NfcViewModel by viewModels { NfcViewModelFactory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.handleIntent(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NfcToolApp(viewModel, this) { startActivity(Intent(AndroidSettings.ACTION_NFC_SETTINGS)) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIntent(intent)
    }

    override fun onPause() { super.onPause(); viewModel.stop(this) }
    override fun onResume() { super.onResume(); viewModel.resume(this) }
}

@Composable
private fun NfcToolApp(viewModel: NfcViewModel, activity: MainActivity, openSettings: () -> Unit) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val aiConfig by viewModel.aiConfig.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val chatInput by viewModel.chatInput.collectAsState()
    val isChatLoading by viewModel.isChatLoading.collectAsState()

    var writerFor by remember { mutableStateOf<TagSnapshot?>(null) }
    var confirmFormat by remember { mutableStateOf(false) }
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NFC Tool & AI Assistant", fontWeight = FontWeight.Bold)
                        Text(
                            when (selectedTab) {
                                NavTab.READ -> "Scan & Analyze NFC / RFID Tags"
                                NavTab.WRITE -> "Write NDEF & Clone Tags"
                                NavTab.HISTORY -> "Scan History & Saved Profiles"
                                NavTab.AI_CHAT -> "AI Assistant (${aiConfig.provider.displayName})"
                                NavTab.SETTINGS -> "AI Provider & App Configuration"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                NavigationBarItem(
                    selected = selectedTab == NavTab.READ,
                    onClick = { viewModel.selectTab(NavTab.READ) },
                    icon = { Icon(Icons.Default.Sensors, contentDescription = "Read") },
                    label = { Text("Read") }
                )
                NavigationBarItem(
                    selected = selectedTab == NavTab.WRITE,
                    onClick = { viewModel.selectTab(NavTab.WRITE) },
                    icon = { Icon(Icons.Default.Edit, contentDescription = "Write") },
                    label = { Text("Write") }
                )
                NavigationBarItem(
                    selected = selectedTab == NavTab.HISTORY,
                    onClick = { viewModel.selectTab(NavTab.HISTORY) },
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("History") }
                )
                NavigationBarItem(
                    selected = selectedTab == NavTab.AI_CHAT,
                    onClick = { viewModel.selectTab(NavTab.AI_CHAT) },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "AI Assistant") },
                    label = { Text("AI Chat") }
                )
                NavigationBarItem(
                    selected = selectedTab == NavTab.SETTINGS,
                    onClick = { viewModel.selectTab(NavTab.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        val cm = Modifier
            .padding(padding)
            .consumeWindowInsets(padding)

        when (selectedTab) {
            NavTab.READ -> ReadTabScreen(
                modifier = cm,
                viewModel = viewModel,
                state = state,
                activity = activity,
                openSettings = openSettings,
                onWrite = { writerFor = it },
                onFormat = { confirmFormat = true },
                onSaveProfile = { profileToSave = it },
                selectedProfile = selectedProfile
            )
            NavTab.WRITE -> WriteTabScreen(
                modifier = cm,
                viewModel = viewModel,
                state = state,
                activity = activity,
                profiles = profiles,
                selectedProfile = selectedProfile,
                showClone = showClone,
                multiCloneStage = multiCloneStage,
                cloneSource = cloneSource,
                cloneOriginal = cloneOriginal,
                cloneEdited = cloneEdited,
                cloneDestination = cloneDestination,
                onOpenWriter = {
                    val currentTag = when (val s = state) {
                        is ScanState.Success -> s.tag
                        is ScanState.Partial -> s.tag
                        else -> null
                    }
                    if (currentTag != null) writerFor = currentTag
                    else viewModel.scan(activity)
                },
                onFormat = { confirmFormat = true },
                onToggleClone = { showClone = it; if (!it) resetClone() },
                onSetStage = { multiCloneStage = it },
                onUpdateCloneRecords = { cloneEdited = it },
                onResetClone = resetClone
            )
            NavTab.HISTORY -> HistoryTabScreen(
                modifier = cm,
                history = history,
                profiles = profiles,
                selectedProfile = selectedProfile,
                onSelectProfile = viewModel::selectProfile,
                onDeleteProfile = viewModel::deleteProfile,
                onDeleteHistory = viewModel::deleteHistory,
                onGoToReader = { viewModel.selectTab(NavTab.READ) }
            )
            NavTab.AI_CHAT -> AiChatTabScreen(
                modifier = cm,
                viewModel = viewModel,
                aiConfig = aiConfig,
                chatMessages = chatMessages,
                chatInput = chatInput,
                isChatLoading = isChatLoading,
                currentTag = when (val s = state) {
                    is ScanState.Success -> s.tag
                    is ScanState.Partial -> s.tag
                    else -> null
                }
            )
            NavTab.SETTINGS -> SettingsTabScreen(
                modifier = cm,
                viewModel = viewModel,
                aiConfig = aiConfig
            )
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
//  TAB 1: READ SCREEN
// ─────────────────────────────────────────────────────────────

@Composable
private fun ReadTabScreen(
    modifier: Modifier,
    viewModel: NfcViewModel,
    state: ScanState,
    activity: MainActivity,
    openSettings: () -> Unit,
    onWrite: (TagSnapshot) -> Unit,
    onFormat: () -> Unit,
    onSaveProfile: (TagSnapshot) -> Unit,
    selectedProfile: NdefProfile?
) {
    val tag = when (state) {
        is ScanState.Success -> state.tag
        is ScanState.Partial -> state.tag
        else -> null
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item { ScanPanel(viewModel, state, activity, openSettings) }

        if (state is ScanState.Error) {
            item { FailureCard(state.error, { viewModel.scan(activity) }) }
        }

        if (tag != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tag Detected & Analyzed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { viewModel.askAiAboutTag(tag) }) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Ask AI Assistant")
                            }
                        }
                        Text("Only data exposed through public Android APIs is read.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item { OverviewCard(tag) }
            item { AttendanceCardSection(tag) }
            item { NdefStatusCard(tag, onFormat) }
            item { CapabilityMatrix(tag) }
            item { Text("NDEF Message Payload", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (tag.ndefRecords.isEmpty()) item { NdefDiagnosisCard(tag) }
            else items(tag.ndefRecords, key = { it.rawHex }) { record -> NdefRecordCard(record) }
            item { WriteActions(tag, { onWrite(tag) }, onFormat, selectedProfile, viewModel::writeSelectedProfile, viewModel::clearSelectedProfile) }
            if (tag.rawNdef != null) item { OutlinedButton(onClick = { onSaveProfile(tag) }, modifier = Modifier.fillMaxWidth()) { Text("Save readable NDEF as profile") } }
            item { TechnologiesCard(tag) }
            item { SecurityCard(tag) }
            item { TechnicalDetailsCard(tag) }
        } else {
            item { AttendanceMachineToolCard() }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  TAB 2: WRITE & CLONE SCREEN
// ─────────────────────────────────────────────────────────────

@Composable
private fun WriteTabScreen(
    modifier: Modifier,
    viewModel: NfcViewModel,
    state: ScanState,
    activity: MainActivity,
    profiles: List<NdefProfile>,
    selectedProfile: NdefProfile?,
    showClone: Boolean,
    multiCloneStage: MultiCloneStage,
    cloneSource: TagSnapshot?,
    cloneOriginal: List<EditableNdefRecord>,
    cloneEdited: List<EditableNdefRecord>,
    cloneDestination: TagSnapshot?,
    onOpenWriter: () -> Unit,
    onFormat: () -> Unit,
    onToggleClone: (Boolean) -> Unit,
    onSetStage: (MultiCloneStage) -> Unit,
    onUpdateCloneRecords: (List<EditableNdefRecord>) -> Unit,
    onResetClone: () -> Unit
) {
    if (showClone) {
        MultiFormatCloneScreen(
            modifier = modifier,
            stage = multiCloneStage,
            source = cloneSource,
            original = cloneOriginal,
            edited = cloneEdited,
            destination = cloneDestination,
            viewModel = viewModel,
            onScanSource = { onSetStage(MultiCloneStage.SOURCE_SCANNING); viewModel.scan(activity) },
            onScanDest = { onSetStage(MultiCloneStage.DEST_SCANNING); viewModel.scan(activity) },
            onUpdateRecords = onUpdateCloneRecords,
            onReset = onResetClone,
            onSetStage = onSetStage,
            onCloneAnother = onResetClone,
            onClose = { onToggleClone(false) }
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("NFC Writer & Payload Tools", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Write custom NDEF messages, format unformatted tags, or clone & edit payloads between tags.")
                }
            }
        }

        item {
            Button(onClick = onOpenWriter, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Write New NDEF Record")
            }
        }

        item {
            OutlinedButton(onClick = { onToggleClone(true) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Difference, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Clone & Edit Tag Payload (Multi-Format)")
            }
        }

        item {
            OutlinedButton(onClick = onFormat, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CleaningServices, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Format Target Tag as NDEF")
            }
        }

        item { Text("Saved NDEF Profiles for Writing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        selectedProfile?.let { profile ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Active Profile Selected: ${profile.name}", fontWeight = FontWeight.Bold)
                        Text("Hold a compatible writable NDEF tag against the phone and tap Write.")
                        Button(onClick = { viewModel.writeSelectedProfile() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Write Selected Profile Now")
                        }
                        TextButton(onClick = { viewModel.clearSelectedProfile() }) { Text("Clear Selection") }
                    }
                }
            }
        }

        if (profiles.isEmpty()) {
            item { Text("No saved NDEF profiles yet. Scan a tag in the Read tab and tap 'Save readable NDEF as profile'.") }
        } else {
            items(profiles, key = { it.id }) { profile ->
                InfoCard(profile.name) {
                    Detail("Technologies", profile.technologies)
                    Detail("NDEF Records", profile.recordCount.toString())
                    Button(onClick = { viewModel.selectProfile(profile) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Select for Writing")
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  TAB 3: HISTORY SCREEN
// ─────────────────────────────────────────────────────────────

@Composable
private fun HistoryTabScreen(
    modifier: Modifier,
    history: List<HistoryItem>,
    profiles: List<NdefProfile>,
    selectedProfile: NdefProfile?,
    onSelectProfile: (NdefProfile) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onGoToReader: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("Scan History & Profiles", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Metadata of past scans and local NDEF profiles.")
        }

        item { Text("Scan History (${history.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (history.isEmpty()) {
            item { Text("No past tag scans recorded yet.") }
        } else {
            items(history, key = { it.id }) { entry ->
                InfoCard(entry.uid ?: "UID Unavailable") {
                    Text(entry.technologies, fontWeight = FontWeight.Medium)
                    Detail("NDEF Records", entry.records.toString())
                    Detail("Write access", entry.writable?.let { if (it) "Writable" else "Read-only" } ?: "Unknown")
                    Detail("Protection", entry.protection.label)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onDeleteHistory(entry.id) }) { Text("Delete") }
                    }
                }
            }
        }

        item { HorizontalDivider() }
        item { Text("Saved Profiles (${profiles.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (profiles.isEmpty()) {
            item { Text("No saved profiles.") }
        } else {
            items(profiles, key = { it.id }) { profile ->
                InfoCard(profile.name) {
                    Detail("Source UID", profile.sourceUid ?: unavailable())
                    Detail("Records", profile.recordCount.toString())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { onSelectProfile(profile) }, modifier = Modifier.weight(1f)) { Text("Use Profile") }
                        TextButton(onClick = { onDeleteProfile(profile.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  TAB 4: AI CHATBOT SCREEN
// ─────────────────────────────────────────────────────────────

@Composable
private fun AiChatTabScreen(
    modifier: Modifier,
    viewModel: NfcViewModel,
    aiConfig: AiConfig,
    chatMessages: List<ChatMessage>,
    chatInput: String,
    isChatLoading: Boolean,
    currentTag: TagSnapshot?
) {
    val listState = rememberLazyListState()

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        // Active context badge
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("AI Provider: ${aiConfig.provider.displayName}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (currentTag != null) "Active Tag: ${currentTag.chipModel ?: "Detected Tag"} (${currentTag.uid ?: "No UID"})"
                        else "No tag currently scanned. Ask general NFC questions!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Chat messages list
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(chatMessages, key = { it.id }) { msg ->
                ChatBubble(msg)
            }
            if (isChatLoading) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("AI is thinking…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Quick prompt chips
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val prompts = listOf(
                "Explain memory structure",
                "Attendance Wiegand format",
                "Is this tag cloneable?",
                "How to format tag"
            )
            prompts.forEach { p ->
                FilterChip(
                    selected = false,
                    onClick = { viewModel.sendChatMessage(p) },
                    label = { Text(p, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = viewModel::setChatInput,
                placeholder = { Text("Ask AI Assistant about NFC/RFID…") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { viewModel.sendChatMessage() },
                enabled = chatInput.isNotBlank() && !isChatLoading,
                modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.sender == ChatSender.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (isUser) "You" else "AI Assistant",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(4.dp))
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  TAB 5: SETTINGS & AI CONFIG SCREEN
// ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsTabScreen(
    modifier: Modifier,
    viewModel: NfcViewModel,
    aiConfig: AiConfig
) {
    var selectedProvider by remember(aiConfig) { mutableStateOf(aiConfig.provider) }
    var apiKey by remember(aiConfig) { mutableStateOf(aiConfig.apiKey) }
    var modelName by remember(aiConfig) { mutableStateOf(aiConfig.modelName) }
    var customEndpoint by remember(aiConfig) { mutableStateOf(aiConfig.customEndpoint) }

    var testStatus by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("Settings & AI Configuration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Configure AI providers (Gemini, OpenAI, Claude, Groq, Ollama) and app preferences.")
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("AI Provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        AiProvider.values().forEach { provider ->
                            FilterChip(
                                selected = selectedProvider == provider,
                                onClick = {
                                    selectedProvider = provider
                                    modelName = provider.defaultModel
                                },
                                label = { Text(provider.displayName) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key (${selectedProvider.displayName})") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                            }
                        }
                    )

                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        label = { Text("Model Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (selectedProvider == AiProvider.OLLAMA_CUSTOM) {
                        OutlinedTextField(
                            value = customEndpoint,
                            onValueChange = { customEndpoint = it },
                            label = { Text("Custom API Endpoint URL") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.updateAiConfig(selectedProvider, apiKey, modelName, customEndpoint)
                            testStatus = "Configuration Saved!"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Configuration")
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.updateAiConfig(selectedProvider, apiKey, modelName, customEndpoint)
                            testStatus = "Testing connection to ${selectedProvider.displayName}…"
                            viewModel.testAiConnection { res ->
                                testStatus = res
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Test AI Connection")
                    }

                    testStatus?.let { status ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(status, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item {
            InfoCard("Device NFC Capabilities") {
                Detail("Android Version", android.os.Build.VERSION.RELEASE)
                Detail("NFC Support", if (viewModel.supported()) "Hardware Available" else "Not Supported")
                Detail("NFC Status", if (viewModel.enabled()) "Enabled" else "Disabled")
                Detail("Supported Tech Standards", "NFC-A, NFC-B, NFC-F, NFC-V, ISO-DEP, MIFARE, NDEF")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  HELPER CARDS & PANELS
// ─────────────────────────────────────────────────────────────

@Composable
private fun ScanPanel(vm: NfcViewModel, state: ScanState, activity: MainActivity, openSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val status = when (state) {
                ScanState.Scanning -> "Scanning for an NFC tag…"
                is ScanState.TagDetected -> "Tag detected. Reading capabilities…"
                is ScanState.Reading -> "Reading tag information…"
                is ScanState.Writing -> "Writing NDEF data. Keep tag in place…"
                is ScanState.Formatting -> "Formatting NDEF. Keep tag in place…"
                else -> if (vm.enabled()) "NFC is enabled and scanning" else "NFC is disabled"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("NFC Scanner Active", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(status)
            if (!vm.supported()) Text("This device does not support NFC.")
            if (!vm.enabled()) TextButton(onClick = openSettings) { Text("Open NFC settings") }
            Button(
                onClick = { vm.scan(activity) },
                enabled = vm.supported() && vm.enabled() && state !is ScanState.Reading && state !is ScanState.Writing && state !is ScanState.Formatting,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state is ScanState.Scanning) "Re-scan" else "Trigger Manual Scan") }
        }
    }
}

@Composable
private fun AttendanceMachineToolCard() {
    var input by remember { mutableStateOf("0009248751") }
    val cleaned = input.trim()

    var facilityCode: Int? = null
    var cardCode: Int? = null
    var dec10Str: String? = null
    var hexStr: String? = null

    if (cleaned.contains(",")) {
        val parts = cleaned.split(",")
        facilityCode = parts.getOrNull(0)?.trim()?.toIntOrNull()
        cardCode = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (facilityCode != null && cardCode != null) {
            val num24 = ((facilityCode and 0xFF) shl 16) or (cardCode and 0xFFFF)
            dec10Str = "%010d".format(num24)
            hexStr = "%06X".format(num24)
        }
    } else if (cleaned.length >= 8 && cleaned.all { it.isDigit() }) {
        val num = cleaned.toLongOrNull()
        if (num != null) {
            val num24 = (num and 0xFFFFFF).toInt()
            facilityCode = (num24 shr 16) and 0xFF
            cardCode = num24 and 0xFFFF
            dec10Str = "%010d".format(num24)
            hexStr = "%06X".format(num24)
        }
    } else if (cleaned.length >= 4 && cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
        val num = cleaned.toLongOrNull(16)
        if (num != null) {
            val num24 = (num and 0xFFFFFF).toInt()
            facilityCode = (num24 shr 16) and 0xFF
            cardCode = num24 and 0xFFFF
            dec10Str = "%010d".format(num24)
            hexStr = "%06X".format(num24)
        }
    }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Biometric Attendance Card Converter & Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Enter printed card numbers (e.g. 0009248751 or 141,08175) or hex UID to convert Wiegand 26-bit facility & card codes.")
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Printed Card Number / Wiegand Code / Hex") },
                modifier = Modifier.fillMaxWidth()
            )
            if (facilityCode != null && cardCode != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Decoded Wiegand 26-Bit Values:", fontWeight = FontWeight.Bold)
                        Detail("Facility Code", facilityCode.toString())
                        Detail("Card Code", "%05d".format(cardCode))
                        dec10Str?.let { Detail("10-Digit Attendance ID", it) }
                        hexStr?.let { Detail("Hex Representation", "0x$it") }
                    }
                }
            }
            HorizontalDivider()
            Text("Why Attendance Cards May Not Be Detected by Phone NFC:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(
                "• 125 kHz Proximity Cards (EM4100 / TK4100 / T5577): Many biometric attendance machines use 125 kHz Low Frequency (LF) cards (printed with numbers like 0009248751 141,08175). Smartphone NFC hardware is strictly 13.56 MHz High Frequency (HF) and physically cannot energize or detect 125 kHz LF cards.\n" +
                "• Supported 13.56 MHz Attendance Tags: MIFARE Classic 1K/4K, MIFARE DESFire, NTAG213/215/216, MIFARE Ultralight, FeliCa, ICODE SLIX, ST25TV/DV/TN, and Topaz 512/1024 tags are all fully supported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable private fun OverviewCard(tag: TagSnapshot) = InfoCard("Tag overview") {
    tag.chipModel?.let { Detail("Chip / Model", it) }
    Detail("UID", tag.uid ?: unavailable())
    Detail("NDEF", tag.ndefAvailability.label)
    Detail("Write access", tag.writable?.let { if (it) "Writable" else "Read-only" } ?: if (tag.formatable) "Available after NDEF formatting" else "Unknown")
    Detail("Protection", tag.protection.label)
}

@Composable private fun AttendanceCardSection(tag: TagSnapshot) = InfoCard("Biometric Attendance Card Info") {
    val info = tag.attendanceInfo
    if (info != null) {
        info.wiegand26Dec10?.let { Detail("10-Digit Attendance ID", it) }
        if (info.wiegand26Facility != null && info.wiegand26Card != null) {
            Detail("Wiegand 26-bit Format", "Facility: ${info.wiegand26Facility}, Card: %05d".format(info.wiegand26Card))
        }
        if (info.wiegand34Facility != null && info.wiegand34Card != null) {
            Detail("Wiegand 34-bit Format", "Facility: ${info.wiegand34Facility}, Card: ${info.wiegand34Card}")
        }
        info.uidHexBigEndian?.let { Detail("UID (Big-Endian Hex)", it) }
        info.uidHexLittleEndian?.let { Detail("UID (Little-Endian Hex)", it) }
        Spacer(Modifier.height(4.dp))
        Text(info.attendanceMachineNotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("No UID available to compute attendance machine card format.")
    }
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) { listOf("Text", "URL", "MIME", "External", "Raw").forEach { choice -> FilterChip(selected = type == choice, onClick = { type = choice }, label = { Text(choice) }) } }
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
private fun FailureCard(error: NfcFailure, onRetry: () -> Unit) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(error.message, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(error.technical, style = MaterialTheme.typography.bodySmall)
        Text(error.action, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Button(onClick = onRetry) { Text("Try Again") }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) = Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Detail(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, fontWeight = FontWeight.Medium)
}

private fun unavailable() = "Unavailable through Android API"

// ─────────────────────────────────────────────────────────────
//  Multi-Format Clone & Edit
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
    val messageSize = built.getOrNull()?.let { records ->
        if (records.isEmpty()) 0
        else runCatching { android.nfc.NdefMessage(records.toTypedArray()).toByteArray().size }.getOrNull()
    }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("← Back to Writer") }
                if (source != null && stage != MultiCloneStage.SOURCE_SCANNING) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onScanSource) { Text("Re-scan source") }
                }
            }
            Text("Clone & Edit Tag Payload", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        item {
            SourceTagCard(
                tag = source,
                isScanning = stage == MultiCloneStage.SOURCE_SCANNING,
                onScan = onScanSource
            )
        }

        when (stage) {
            MultiCloneStage.IDLE -> item {
                DestinationTagCard(
                    tag = null, sourceScanned = false, original = original, edited = edited,
                    isScanning = false, canScan = false, messageSize = null,
                    onScan = onScanDest, onUpdateRecords = onUpdateRecords,
                    onEdit = { editing = it }, onReset = onReset, onWrite = {}
                )
            }
            MultiCloneStage.SOURCE_SCANNING -> {}
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
                ProgressCard("Writing & verifying", "Keep destination tag against phone.")
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
                    NfcFailure.Simple("Write verification failed", "Re-read message did not match.", "Keep tag against phone and try again."),
                    onScanDest
                )
            }
        }
    }

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

@Composable
private fun SourceTagCard(tag: TagSnapshot?, isScanning: Boolean, onScan: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (tag != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SOURCE TAG", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("Hold source tag against phone…")
            } else if (tag == null) {
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan Source Tag") }
            } else {
                Detail("Chip Model", tag.chipModel ?: "Unknown")
                Detail("UID", tag.uid ?: unavailable())
                Detail("NDEF Records", tag.ndefRecords.size.toString())
            }
        }
    }
}

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
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("DESTINATION TAG", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            if (sourceScanned) {
                edited.forEachIndexed { i, record ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Record ${i + 1}: ${record.kind} (${record.value})", modifier = Modifier.weight(1f))
                        IconButton(onClick = { onEdit(record) }) { Icon(Icons.Default.Edit, contentDescription = null) }
                    }
                }
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan & Write Destination Tag") }
            }
        }
    }
}

@Composable
private fun ProgressCard(title: String, message: String) = Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text(title, fontWeight = FontWeight.Bold)
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CloneSuccessCard(recordCount: Int, destination: TagSnapshot?, edited: List<EditableNdefRecord>, onEditAgain: () -> Unit, onCloneAnother: () -> Unit, onViewDetails: () -> Unit) = Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("✓ Clone Success", fontWeight = FontWeight.Bold)
        Text("Wrote $recordCount NDEF record(s) successfully.")
        Button(onClick = onCloneAnother, modifier = Modifier.fillMaxWidth()) { Text("Clone Another Tag") }
    }
}

@Composable
private fun EditNdefRecordDialog(record: EditableNdefRecord, onDismiss: () -> Unit, onSave: (EditableNdefRecord) -> Unit) {
    var valStr by remember { mutableStateOf(record.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Record") },
        text = { OutlinedTextField(valStr, { valStr = it }, label = { Text("Value") }, modifier = Modifier.fillMaxWidth()) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { Button(onClick = { onSave(record.copy(value = valStr)) }) { Text("Save") } }
    )
}
