package com.teamflow.nfctool.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import com.teamflow.nfctool.domain.NdefAvailability
import com.teamflow.nfctool.domain.ProtectionStatus
import com.teamflow.nfctool.domain.TagSnapshot
import com.teamflow.nfctool.domain.TechInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidNfcRepository(context: Context) : NfcRepository {
    private val adapter = NfcAdapter.getDefaultAdapter(context)

    override fun isSupported() = adapter != null
    override fun isEnabled() = adapter?.isEnabled == true

    override fun startScanning(activity: Activity, onTag: (Tag) -> Unit) {
        adapter?.enableReaderMode(
            activity,
            { tag -> onTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE,
            null
        )
    }

    override fun stopScanning(activity: Activity) = adapter?.disableReaderMode(activity)

    override suspend fun readTag(tag: Tag): Result<TagSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val ndef = Ndef.get(tag)
            val formatable = NdefFormatable.get(tag)
            var message: NdefMessage? = null
            var size: Int? = null
            var max: Int? = null
            var writable: Boolean? = null
            var readIssue: String? = null

            if (ndef != null) {
                try {
                    ndef.connect()
                    message = ndef.ndefMessage
                    size = message?.toByteArray()?.size ?: 0
                    max = ndef.maxSize
                    writable = ndef.isWritable
                } catch (error: TagLostException) {
                    throw error
                } catch (error: Exception) {
                    readIssue = "Android could not read the exposed NDEF interface: ${error.javaClass.simpleName}"
                } finally {
                    runCatching { ndef.close() }
                }
            }

            val availability = when {
                readIssue != null -> NdefAvailability.PARTIAL
                ndef != null && message == null -> NdefAvailability.EMPTY
                ndef != null -> NdefAvailability.AVAILABLE
                formatable != null -> NdefAvailability.FORMATABLE
                else -> NdefAvailability.UNAVAILABLE
            }
            val protection = when {
                readIssue != null -> ProtectionStatus.PARTIAL
                writable == false -> ProtectionStatus.READ_ONLY
                else -> ProtectionStatus.UNKNOWN
            }
            val diagnosis = when (availability) {
                NdefAvailability.AVAILABLE -> "Android exposed an NDEF message."
                NdefAvailability.EMPTY -> "Android exposed an NDEF interface, but no NDEF message is currently available."
                NdefAvailability.FORMATABLE -> "This tag appears formatable as NDEF, but no NDEF interface is currently exposed."
                NdefAvailability.PARTIAL -> readIssue ?: "Only partial NDEF information is available."
                NdefAvailability.UNAVAILABLE -> "Android detected the tag, but no NDEF or NDEF-formatable interface is exposed. Its application data may be proprietary, protected, or unsupported by public Android APIs."
            }
            val protectionReason = when (protection) {
                ProtectionStatus.READ_ONLY -> "Android reports that the exposed NDEF interface is read-only."
                ProtectionStatus.PARTIAL -> "Some information is unavailable through the public Android NFC interface."
                ProtectionStatus.UNKNOWN -> "Android does not provide enough evidence to determine tag-wide protection or authentication status."
            }
            TagSnapshot(
                uid = tag.id?.hex(),
                technologies = technologyDetails(tag, ndef, formatable),
                ndefRecords = message?.let(NdefCodec::parse).orEmpty(),
                ndefAvailability = availability,
                ndefSize = size,
                maxSize = max,
                writable = writable,
                formatable = formatable != null,
                protection = protection,
                protectionReason = protectionReason,
                ndefDiagnosis = diagnosis,
                rawNdef = message?.toByteArray()
            )
        }
    }

    override suspend fun writeNdef(tag: Tag, message: NdefMessage): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ndef = Ndef.get(tag) ?: error("No writable NDEF interface is exposed by this tag.")
            ndef.connect()
            try {
                check(ndef.isWritable) { "Android reports that this NDEF interface is read-only." }
                check(message.toByteArray().size <= ndef.maxSize) {
                    "Message requires ${message.toByteArray().size} bytes; tag capacity is ${ndef.maxSize} bytes."
                }
                ndef.writeNdefMessage(message)
            } finally {
                runCatching { ndef.close() }
            }
        }
    }

    override suspend fun formatNdef(tag: Tag, initialMessage: NdefMessage): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val formatable = NdefFormatable.get(tag)
                ?: error("Android does not expose an NDEF-formatable interface for this tag.")
            formatable.connect()
            try {
                formatable.format(initialMessage)
            } finally {
                runCatching { formatable.close() }
            }
        }
    }

    private fun technologyDetails(tag: Tag, ndef: Ndef?, formatable: NdefFormatable?): List<TechInfo> =
        tag.techList.map { name -> runCatching {
            when (name) {
                NfcA::class.java.name -> NfcA.get(tag)?.let { TechInfo("NFC-A", listOf("ATQA" to (it.atqa?.hex() ?: unavailable()), "SAK" to "%02X".format(it.sak))) }
                NfcB::class.java.name -> NfcB.get(tag)?.let { TechInfo("NFC-B", listOf("Application data" to it.applicationData.hex(), "Protocol info" to it.protocolInfo.hex())) }
                NfcF::class.java.name -> NfcF.get(tag)?.let { TechInfo("NFC-F", listOf("System code" to it.systemCode.hex(), "Manufacturer" to it.manufacturer.hex())) }
                NfcV::class.java.name -> NfcV.get(tag)?.let { TechInfo("NFC-V", listOf("DSF ID" to "%02X".format(it.dsfId), "Response flags" to "%02X".format(it.responseFlags))) }
                IsoDep::class.java.name -> IsoDep.get(tag)?.let { TechInfo("ISO-DEP", listOf("Historical bytes" to (it.historicalBytes?.hex() ?: unavailable()), "Hi-layer response" to (it.hiLayerResponse?.hex() ?: unavailable()), "Max transceive" to "${it.maxTransceiveLength} bytes")) }
                MifareClassic::class.java.name -> MifareClassic.get(tag)?.let { TechInfo("MIFARE Classic", listOf("Size" to "${it.size} bytes", "Sectors" to it.sectorCount.toString(), "Authentication" to "Not attempted")) }
                MifareUltralight::class.java.name -> MifareUltralight.get(tag)?.let { TechInfo("MIFARE Ultralight", listOf("Type" to it.type.toString(), "Authentication" to "Not attempted")) }
                Ndef::class.java.name -> TechInfo("NDEF", listOf("Format" to (ndef?.type ?: unavailable())))
                NdefFormatable::class.java.name -> TechInfo("NDEF formatable", listOf("Available" to (formatable != null).toString()))
                else -> TechInfo(name.substringAfterLast('.'), listOf("Status" to "Detected by Android"))
            } ?: TechInfo(name.substringAfterLast('.'), listOf("Status" to unavailable()))
        }.getOrElse { TechInfo(name.substringAfterLast('.'), listOf("Status" to unavailable())) } }

    private fun unavailable() = "Unavailable through Android API"
}

private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }
