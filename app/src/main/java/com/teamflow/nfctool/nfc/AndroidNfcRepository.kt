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

    override fun stopScanning(activity: Activity) {
        adapter?.disableReaderMode(activity)
    }

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
                NfcA::class.java.name -> NfcA.get(tag)?.let { nfcA ->
                    val details = mutableListOf<Pair<String, String>>()
                    nfcA.atqa?.let { details.add("ATQA" to it.hex()) }
                    details.add("SAK" to "%02X".format(nfcA.sak))
                    TechInfo("NFC-A", details)
                }
                NfcB::class.java.name -> NfcB.get(tag)?.let { nfcB ->
                    val details = mutableListOf<Pair<String, String>>()
                    if (nfcB.applicationData.isNotEmpty()) details.add("Application data" to nfcB.applicationData.hex())
                    if (nfcB.protocolInfo.isNotEmpty()) details.add("Protocol info" to nfcB.protocolInfo.hex())
                    TechInfo("NFC-B", details)
                }
                NfcF::class.java.name -> NfcF.get(tag)?.let { nfcF ->
                    val details = mutableListOf<Pair<String, String>>()
                    if (nfcF.systemCode.isNotEmpty()) details.add("System code" to nfcF.systemCode.hex())
                    if (nfcF.manufacturer.isNotEmpty()) details.add("Manufacturer" to nfcF.manufacturer.hex())
                    TechInfo("NFC-F", details)
                }
                NfcV::class.java.name -> NfcV.get(tag)?.let { nfcV ->
                    TechInfo("NFC-V", listOf("DSF ID" to "%02X".format(nfcV.dsfId), "Response flags" to "%02X".format(nfcV.responseFlags)))
                }
                IsoDep::class.java.name -> IsoDep.get(tag)?.let { iso ->
                    val details = mutableListOf<Pair<String, String>>()
                    iso.historicalBytes?.let { details.add("Historical bytes (Type A ATS)" to it.hex()) }
                    iso.hiLayerResponse?.let { details.add("Hi-layer response (Type B ATTRIB)" to it.hex()) }
                    if (iso.historicalBytes == null && iso.hiLayerResponse == null) {
                        details.add("ISO-DEP type" to "ISO 14443-4 standard")
                    }
                    details.add("Max transceive" to "${iso.maxTransceiveLength} bytes")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        details.add("Extended APDU support" to iso.isExtendedLengthApduSupported.toString())
                    }
                    runCatching {
                        if (!iso.isConnected) iso.connect()
                        val apduResult = probeIsoDepSmartCard(iso)
                        if (apduResult.isNotBlank()) {
                            details.add("Smart Card AID" to apduResult)
                        }
                    }
                    runCatching { iso.close() }
                    TechInfo("ISO-DEP", details)
                }
                MifareClassic::class.java.name -> MifareClassic.get(tag)?.let { TechInfo("MIFARE Classic", listOf("Size" to "${it.size} bytes", "Sectors" to it.sectorCount.toString(), "Blocks" to it.blockCount.toString())) }
                MifareUltralight::class.java.name -> MifareUltralight.get(tag)?.let { TechInfo("MIFARE Ultralight", listOf("Type" to it.type.toString())) }
                Ndef::class.java.name -> TechInfo("NDEF", listOf("Format" to (ndef?.type ?: "Standard NDEF")))
                NdefFormatable::class.java.name -> TechInfo("NDEF formatable", listOf("Status" to "Can be formatted as NDEF"))
                else -> TechInfo(name.substringAfterLast('.'), listOf("Status" to "Detected"))
            } ?: TechInfo(name.substringAfterLast('.'), listOf("Status" to "Detected"))
        }.getOrElse { TechInfo(name.substringAfterLast('.'), listOf("Status" to "Detected")) } }

    private fun probeIsoDepSmartCard(iso: IsoDep): String {
        val probes = listOf(
            byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x07.toByte(), 0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(), 0x85.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte()) to "NFC Type 4 NDEF App",
            byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x0E.toByte(), '2'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte(), 'Y'.code.toByte(), '.'.code.toByte(), 'S'.code.toByte(), 'Y'.code.toByte(), 'S'.code.toByte(), '.'.code.toByte(), 'D'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(), 0x00.toByte()) to "EMV Payment (PPSE)",
            byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x07.toByte(), 0xA0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x47.toByte(), 0x10.toByte(), 0x01.toByte(), 0x00.toByte()) to "ICAO ePassport App",
            byteArrayOf(0x90.toByte(), 0x60.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()) to "MIFARE DESFire Native"
        )
        for ((apdu, name) in probes) {
            val res = runCatching { iso.transceive(apdu) }.getOrNull()
            if (res != null && res.size >= 2) {
                val sw1 = res[res.size - 2].toInt() and 0xFF
                val sw2 = res[res.size - 1].toInt() and 0xFF
                if (sw1 == 0x90 || sw1 == 0x61 || (sw1 == 0x91 && sw2 == 0xAF)) {
                    return "$name (SW=%02X%02X)".format(sw1, sw2)
                }
            }
        }
        return ""
    }

    private fun unavailable() = "Unavailable through Android API"
}

private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }
