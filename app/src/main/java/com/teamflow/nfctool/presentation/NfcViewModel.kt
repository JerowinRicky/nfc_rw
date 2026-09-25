package com.teamflow.nfctool.presentation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamflow.nfctool.ai.AiService
import com.teamflow.nfctool.domain.AiConfig
import com.teamflow.nfctool.domain.AiProvider
import com.teamflow.nfctool.domain.ChatMessage
import com.teamflow.nfctool.domain.ChatSender
import com.teamflow.nfctool.domain.HistoryItem
import com.teamflow.nfctool.domain.NavTab
import com.teamflow.nfctool.domain.NdefAvailability
import com.teamflow.nfctool.domain.NdefProfile
import com.teamflow.nfctool.domain.NfcFailure
import com.teamflow.nfctool.domain.ProtectionStatus
import com.teamflow.nfctool.domain.ScanState
import com.teamflow.nfctool.domain.TagSnapshot
import com.teamflow.nfctool.nfc.NfcRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class NfcViewModel(private val repository: NfcRepository, private val context: Context) : ViewModel() {
    private val aiService = AiService()

    private val _selectedTab = MutableStateFlow(NavTab.READ)
    val selectedTab: StateFlow<NavTab> = _selectedTab.asStateFlow()

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<NdefProfile>> = _profiles.asStateFlow()

    private val _selectedProfile = MutableStateFlow<NdefProfile?>(null)
    val selectedProfile: StateFlow<NdefProfile?> = _selectedProfile.asStateFlow()

    private val _aiConfig = MutableStateFlow(loadAiConfig())
    val aiConfig: StateFlow<AiConfig> = _aiConfig.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                sender = ChatSender.AI,
                text = "Hello! I am your NFC & RFID Technical Assistant. Ask me anything about your scanned tag, chip protocols (NTAG213/215/216, MIFARE Classic/DESFire/Plus, FeliCa, ICODE SLIX, ST25), or Wiegand attendance cards!"
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _chatInput = MutableStateFlow("")
    val chatInput: StateFlow<String> = _chatInput.asStateFlow()

    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading: StateFlow<Boolean> = _isChatLoading.asStateFlow()

    private var currentTag: Tag? = null

    fun supported() = repository.isSupported()
    fun enabled() = repository.isEnabled()

    fun selectTab(tab: NavTab) {
        _selectedTab.value = tab
    }

    fun scan(activity: Activity) {
        currentTag = null
        if (!supported()) return fail("NFC unavailable", "NfcAdapter.getDefaultAdapter returned null.", "This device does not support NFC.")
        if (!enabled()) return fail("NFC is disabled", "Android reports that NFC is disabled.", "Enable NFC in Android settings and try again.")
        repository.stopScanning(activity)
        _state.value = ScanState.Scanning
        beginReaderSession(activity)
    }

    fun resume(activity: Activity) {
        if (supported() && enabled()) {
            beginReaderSession(activity)
        }
    }

    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            action == NfcAdapter.ACTION_NDEF_DISCOVERED
        ) {
            val tag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            if (tag != null) {
                currentTag = tag
                val uid = tag.id?.joinToString(" ") { "%02X".format(it) }
                _state.value = ScanState.TagDetected(uid)
                _state.value = ScanState.Reading(uid)
                readCurrentTag()
            }
        }
    }

    private fun beginReaderSession(activity: Activity) {
        repository.startScanning(activity) { tag ->
            currentTag = tag
            val uid = tag.id?.joinToString(" ") { "%02X".format(it) }
            _state.value = ScanState.TagDetected(uid)
            _state.value = ScanState.Reading(uid)
            readCurrentTag()
        }
    }

    fun stop(activity: Activity) = repository.stopScanning(activity)

    fun reset() {
        currentTag = null
        _state.value = ScanState.Idle
    }

    fun refreshTagCapabilities() {
        if (currentTag == null) return fail("No active NFC tag", "No Tag object is retained.", "Scan the tag again and keep it near the phone.")
        _state.value = ScanState.Reading(currentTag?.id?.joinToString(" ") { "%02X".format(it) })
        readCurrentTag()
    }

    fun write(records: List<NdefRecord>) {
        val tag = currentTag ?: return fail("No active NFC tag", "No Tag object is retained.", "Scan the target tag again and keep it near the phone.")
        viewModelScope.launch {
            _state.value = ScanState.Writing
            repository.writeNdef(tag, NdefMessage(records.toTypedArray())).fold(
                onSuccess = { _state.value = ScanState.WriteSuccess("NDEF data was written. Keep the tag in place while its capabilities refresh."); refreshTagCapabilities() },
                onFailure = ::mapError
            )
        }
    }

    fun saveReadableNdefProfile(tag: TagSnapshot, name: String) {
        val raw = tag.rawNdef ?: return fail("No readable NDEF data", "The tag did not expose an NDEF message.", "Only Android-exposed NDEF data can be saved as a profile.")
        val profile = NdefProfile(System.currentTimeMillis(), name.ifBlank { "NDEF profile" }, System.currentTimeMillis(), tag.uid, tag.technologies.joinToString { it.name }, tag.ndefRecords.size, Base64.encodeToString(raw, Base64.NO_WRAP))
        _profiles.value = (listOf(profile) + _profiles.value).take(30)
        persistProfiles()
    }

    fun selectProfile(profile: NdefProfile) { _selectedProfile.value = profile }
    fun clearSelectedProfile() { _selectedProfile.value = null }
    fun deleteProfile(id: Long) { _profiles.value = _profiles.value.filterNot { it.id == id }; if (_selectedProfile.value?.id == id) clearSelectedProfile(); persistProfiles() }

    fun writeSelectedProfile() {
        val profile = _selectedProfile.value ?: return fail("No profile selected", "No local NDEF profile is active.", "Select a saved NDEF profile first.")
        val message = runCatching { NdefMessage(Base64.decode(profile.messageBase64, Base64.NO_WRAP)) }.getOrElse {
            return fail("Invalid profile", "${it.javaClass.simpleName}: ${it.message}", "Delete this profile and create it again from a readable NDEF tag.")
        }
        val tag = currentTag ?: return fail("No active NFC tag", "No target Tag object is retained.", "Scan a compatible writable target tag and keep it near the phone.")
        viewModelScope.launch {
            _state.value = ScanState.Writing
            repository.writeNdef(tag, message).fold(
                onSuccess = { _selectedProfile.value = null; refreshTagCapabilities() },
                onFailure = ::mapError
            )
        }
    }

    fun formatAsNdef() {
        val tag = currentTag ?: return fail("No active NFC tag", "No Tag object is retained.", "Scan the tag again and keep it near the phone.")
        viewModelScope.launch {
            _state.value = ScanState.Formatting
            val initial = NdefMessage(arrayOf(NdefRecord.createTextRecord("en", "")))
            repository.formatNdef(tag, initial).fold(
                onSuccess = { _state.value = ScanState.FormatSuccess("NDEF formatting completed. Refreshing tag information."); refreshTagCapabilities() },
                onFailure = ::mapError
            )
        }
    }

    private fun readCurrentTag() {
        val tag = currentTag ?: return
        viewModelScope.launch {
            repository.readTag(tag).fold(
                onSuccess = ::publishRead,
                onFailure = ::mapError
            )
        }
    }

    private fun publishRead(snapshot: TagSnapshot) {
        _history.value = (listOf(snapshot.toHistory()) + _history.value).distinctBy { it.id }.take(50)
        persistHistory()
        _state.value = if (snapshot.ndefAvailability == NdefAvailability.PARTIAL) ScanState.Partial(snapshot) else ScanState.Success(snapshot)
    }

    fun deleteHistory(id: Long) { _history.value = _history.value.filterNot { it.id == id }; persistHistory() }

    // AI Config & Chatbot methods
    fun updateAiConfig(provider: AiProvider, apiKey: String, modelName: String, customEndpoint: String) {
        val config = AiConfig(provider, apiKey.trim(), modelName.trim().ifBlank { provider.defaultModel }, customEndpoint.trim())
        _aiConfig.value = config
        persistAiConfig(config)
    }

    fun setChatInput(text: String) {
        _chatInput.value = text
    }

    fun askAiAboutTag(tag: TagSnapshot) {
        _selectedTab.value = NavTab.AI_CHAT
        val prompt = "Explain the technical details, memory layout, and security status of this ${tag.chipModel ?: "NFC tag"}."
        sendChatMessage(prompt)
    }

    fun sendChatMessage(promptText: String = _chatInput.value) {
        val text = promptText.trim()
        if (text.isBlank()) return
        val userMsg = ChatMessage(sender = ChatSender.USER, text = text)
        _chatMessages.value = _chatMessages.value + userMsg
        _chatInput.value = ""
        _isChatLoading.value = true

        val currentTagSnapshot = when (val s = _state.value) {
            is ScanState.Success -> s.tag
            is ScanState.Partial -> s.tag
            else -> null
        }

        viewModelScope.launch {
            val response = aiService.generateResponse(text, _aiConfig.value, currentTagSnapshot)
            val aiMsg = ChatMessage(sender = ChatSender.AI, text = response)
            _chatMessages.value = _chatMessages.value + aiMsg
            _isChatLoading.value = false
        }
    }

    fun testAiConnection(onResult: (String) -> Unit) {
        viewModelScope.launch {
            _isChatLoading.value = true
            val response = aiService.generateResponse("Ping test. Respond with OK if active.", _aiConfig.value, null)
            _isChatLoading.value = false
            onResult(response)
        }
    }

    private fun TagSnapshot.toHistory() = HistoryItem(scannedAt, scannedAt, uid, technologies.joinToString { it.name }, ndefAvailability == NdefAvailability.AVAILABLE, writable, protection, ndefRecords.size)
    private fun fail(message: String, technical: String, action: String) { _state.value = ScanState.Error(NfcFailure.Simple(message, technical, action)) }
    private fun mapError(error: Throwable) = when (error) {
        is TagLostException -> fail("Tag removed", "TagLostException", "Keep the tag against the phone until the operation completes.")
        else -> fail("NFC operation failed", "${error.javaClass.simpleName}: ${error.message ?: "No detail supplied"}", "Try again with the tag held steadily against the phone.")
    }

    private fun prefs() = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private fun loadHistory(): List<HistoryItem> = runCatching {
        val array = JSONArray(prefs().getString("items", "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            HistoryItem(item.getLong("id"), item.getLong("time"), item.optString("uid").ifBlank { null }, item.getString("tech"), item.getBoolean("ndef"), if (item.isNull("writable")) null else item.getBoolean("writable"), runCatching { ProtectionStatus.valueOf(item.getString("protection")) }.getOrDefault(ProtectionStatus.UNKNOWN), item.getInt("records"))
        }
    }.getOrDefault(emptyList())
    private fun persistHistory() {
        val array = JSONArray()
        _history.value.forEach { item -> array.put(JSONObject().put("id", item.id).put("time", item.timestamp).put("uid", item.uid ?: "").put("tech", item.technologies).put("ndef", item.ndef).put("writable", item.writable).put("protection", item.protection.name).put("records", item.records)) }
        prefs().edit().putString("items", array.toString()).apply()
    }

    private fun loadProfiles(): List<NdefProfile> = runCatching {
        val array = JSONArray(prefs().getString("profiles", "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            NdefProfile(item.getLong("id"), item.getString("name"), item.getLong("created"), item.optString("uid").ifBlank { null }, item.getString("tech"), item.getInt("records"), item.getString("message"))
        }
    }.getOrDefault(emptyList())

    private fun persistProfiles() {
        val array = JSONArray()
        _profiles.value.forEach { profile -> array.put(JSONObject().put("id", profile.id).put("name", profile.name).put("created", profile.createdAt).put("uid", profile.sourceUid ?: "").put("tech", profile.technologies).put("records", profile.recordCount).put("message", profile.messageBase64)) }
        prefs().edit().putString("profiles", array.toString()).apply()
    }

    private fun loadAiConfig(): AiConfig {
        val p = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
        val providerStr = p.getString("provider", AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name
        val provider = runCatching { AiProvider.valueOf(providerStr) }.getOrDefault(AiProvider.GEMINI)
        val apiKey = p.getString("api_key", "") ?: ""
        val modelName = p.getString("model_name", provider.defaultModel) ?: provider.defaultModel
        val customEndpoint = p.getString("custom_endpoint", "http://localhost:11434/v1/chat/completions") ?: "http://localhost:11434/v1/chat/completions"
        return AiConfig(provider, apiKey, modelName, customEndpoint)
    }

    private fun persistAiConfig(config: AiConfig) {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit()
            .putString("provider", config.provider.name)
            .putString("api_key", config.apiKey)
            .putString("model_name", config.modelName)
            .putString("custom_endpoint", config.customEndpoint)
            .apply()
    }
}
