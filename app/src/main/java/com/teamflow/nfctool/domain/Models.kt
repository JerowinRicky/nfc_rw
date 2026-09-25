package com.teamflow.nfctool.domain

enum class NavTab(val title: String) {
    READ("Read"),
    WRITE("Write"),
    HISTORY("History"),
    AI_CHAT("AI Assistant"),
    SETTINGS("Settings")
}

enum class AiProvider(val displayName: String, val defaultModel: String) {
    GEMINI("Google Gemini", "gemini-1.5-flash"),
    OPENAI("OpenAI GPT", "gpt-4o-mini"),
    CLAUDE("Anthropic Claude", "claude-3-5-haiku-20241022"),
    GROQ("Groq Llama", "llama-3.3-70b-versatile"),
    OLLAMA_CUSTOM("Ollama / Custom API", "llama3")
}

data class AiConfig(
    val provider: AiProvider = AiProvider.GEMINI,
    val apiKey: String = "",
    val modelName: String = "gemini-1.5-flash",
    val customEndpoint: String = "http://localhost:11434/v1/chat/completions"
)

enum class ChatSender { USER, AI }

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: ChatSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class NdefAvailability(val label: String) {
    AVAILABLE("Available"), EMPTY("Available, no records"), FORMATABLE("Formatable"),
    PARTIAL("Partially accessible"), UNAVAILABLE("Not available")
}

enum class ProtectionStatus(val label: String) {
    UNKNOWN("Unknown"), READ_ONLY("Read-only"), PARTIAL("Partially accessible")
}

data class NdefRecordInfo(
    val tnf: Short, val type: String, val kind: String, val value: String,
    val rawHex: String, val language: String? = null
)

data class TechInfo(val name: String, val values: List<Pair<String, String>>)

data class AttendanceCardInfo(
    val wiegand26Facility: Int?,
    val wiegand26Card: Int?,
    val wiegand26Dec10: String?,
    val wiegand34Facility: Int?,
    val wiegand34Card: Int?,
    val uidHexBigEndian: String?,
    val uidHexLittleEndian: String?,
    val attendanceMachineNotes: String
)

data class TagSnapshot(
    val uid: String?,
    val chipModel: String? = null,
    val technologies: List<TechInfo>,
    val ndefRecords: List<NdefRecordInfo>,
    val ndefAvailability: NdefAvailability,
    val ndefSize: Int?,
    val maxSize: Int?,
    val writable: Boolean?,
    val formatable: Boolean,
    val protection: ProtectionStatus,
    val protectionReason: String,
    val ndefDiagnosis: String,
    val rawNdef: ByteArray?,
    val attendanceInfo: AttendanceCardInfo? = null,
    val scannedAt: Long = System.currentTimeMillis()
)

sealed interface NfcFailure {
    val message: String
    val technical: String
    val action: String
    data class Simple(
        override val message: String,
        override val technical: String,
        override val action: String
    ) : NfcFailure
}

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class TagDetected(val uid: String?) : ScanState
    data class Reading(val uid: String?) : ScanState
    data class Success(val tag: TagSnapshot) : ScanState
    data class Partial(val tag: TagSnapshot) : ScanState
    data object Writing : ScanState
    data class WriteSuccess(val message: String) : ScanState
    data object Formatting : ScanState
    data class FormatSuccess(val message: String) : ScanState
    data class Error(val error: NfcFailure) : ScanState
}

data class HistoryItem(
    val id: Long,
    val timestamp: Long,
    val uid: String?,
    val technologies: String,
    val ndef: Boolean,
    val writable: Boolean?,
    val protection: ProtectionStatus,
    val records: Int
)

/** A local copy of an Android-exposed NDEF message, never a hardware-card clone. */
data class NdefProfile(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val sourceUid: String?,
    val technologies: String,
    val recordCount: Int,
    val messageBase64: String
)

/** A user-editable representation of an NDEF record exposed by Android. */
data class EditableNdefRecord(
    val id: Long,
    val kind: String,
    val type: String,
    val value: String,
    val metadata: String = "",
    val rawHex: String = ""
)

enum class MultiCloneStage {
    IDLE,
    SOURCE_SCANNING,
    SOURCE_READY,
    DEST_SCANNING,
    DEST_READY,
    WRITING,
    SUCCESS,
    VERIFY_FAILED
}
