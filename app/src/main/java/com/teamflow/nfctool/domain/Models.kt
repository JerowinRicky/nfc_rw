package com.teamflow.nfctool.domain

enum class ProtectionStatus(val label: String) { UNPROTECTED("Unprotected"), WRITABLE("Writable"), READ_ONLY("Read-only"), PERMANENTLY_LOCKED("Permanently locked"), PASSWORD_PROTECTED("Password protected"), AUTH_REQUIRED("Authentication required"), ENCRYPTED_OPAQUE("Encrypted/opaque"), PARTIAL("Partially accessible"), UNKNOWN("Unknown"), UNSUPPORTED("Unsupported") }
data class Capability(val available: Boolean?, val explanation: String)
data class NdefRecordInfo(val tnf: Short, val type: String, val kind: String, val value: String, val rawHex: String, val language: String? = null)
data class TechInfo(val name: String, val values: List<Pair<String, String>>)
data class TagSnapshot(val uid: String?, val technologies: List<TechInfo>, val ndefRecords: List<NdefRecordInfo>, val ndefSupported: Boolean, val ndefSize: Int?, val maxSize: Int?, val writable: Boolean?, val protection: ProtectionStatus, val protectionReason: String, val rawNdef: ByteArray?, val scannedAt: Long = System.currentTimeMillis())
sealed interface NfcFailure { val message: String; val technical: String; val action: String
    data class Simple(override val message: String, override val technical: String, override val action: String): NfcFailure }
sealed interface ScanState { data object Idle : ScanState; data object Scanning : ScanState; data object Reading : ScanState; data class Success(val tag: TagSnapshot) : ScanState; data class Error(val error: NfcFailure) : ScanState; data object Writing : ScanState; data class WriteSuccess(val message: String) : ScanState }
data class HistoryItem(val id: Long, val timestamp: Long, val uid: String?, val technologies: String, val ndef: Boolean, val writable: Boolean?, val protection: ProtectionStatus, val records: Int)
