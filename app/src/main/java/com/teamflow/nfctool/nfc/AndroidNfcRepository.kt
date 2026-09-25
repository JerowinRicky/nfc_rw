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
import com.teamflow.nfctool.domain.AttendanceCardInfo
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

            val chipModel = identifyChipModel(tag)
            val attendanceInfo = computeAttendanceInfo(tag)

            TagSnapshot(
                uid = tag.id?.hex(),
                chipModel = chipModel,
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
                rawNdef = message?.toByteArray(),
                attendanceInfo = attendanceInfo
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

    private fun identifyChipModel(tag: Tag): String {
        // 1. Check ISO 15693 / NfcV (ICODE SLIX, ST25TV, ST25DV)
        val nfcV = NfcV.get(tag)
        if (nfcV != null) {
            val sysInfo = runCatching {
                nfcV.connect()
                val resp = nfcV.transceive(byteArrayOf(0x20.toByte(), 0x2B.toByte()))
                nfcV.close()
                resp
            }.getOrNull()

            if (sysInfo != null && sysInfo.size >= 10 && (sysInfo[0].toInt() and 0xFF) == 0x00) {
                val uid = tag.id
                if (uid != null && uid.size >= 8) {
                    val mfgId = uid[6].toInt() and 0xFF
                    val icRef = if (sysInfo.size >= 11) sysInfo[sysInfo.size - 1].toInt() and 0xFF else 0
                    when (mfgId) {
                        0x04 -> { // NXP
                            return when (icRef) {
                                0x01 -> "ICODE SLI (NXP ISO 15693 - SL2S2002)"
                                0x02 -> "ICODE SLIX (NXP ISO 15693 - SL2S2002/2102)"
                                0x03 -> "ICODE SLIX2 (NXP ISO 15693 - 2560 bits)"
                                0x04 -> "ICODE DNA (NXP ISO 15693 - AES Cryptographic)"
                                else -> "ICODE Series (NXP ISO 15693)"
                            }
                        }
                        0x02 -> { // STMicroelectronics
                            return when (icRef) {
                                0x20, 0x24, 0x26 -> "ST25TV Series (STMicroelectronics ISO 15693)"
                                0x34 -> "ST25DV04K (STMicro Dual Interface 4-Kbit)"
                                0x35 -> "ST25DV16K (STMicro Dual Interface 16-Kbit)"
                                0x3E -> "ST25DV64K (STMicro Dual Interface 64-Kbit)"
                                else -> "ST25TV/DV Series (STMicroelectronics ISO 15693)"
                            }
                        }
                    }
                }
                return "ISO 15693 High Frequency Tag (NFC-V)"
            }
        }

        // 2. Check Topaz 512 / Topaz 1024 (Type 1 Tag / Broadcom / Innovision Topaz)
        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            val atqa = nfcA.atqa
            if (atqa != null && atqa.size >= 2) {
                val b0 = atqa[0].toInt() and 0xFF
                val b1 = atqa[1].toInt() and 0xFF
                if ((b0 == 0x0C && b1 == 0x00) || (b0 == 0x00 && b1 == 0x0C)) {
                    val ridResp = runCatching {
                        nfcA.connect()
                        val res = nfcA.transceive(byteArrayOf(0x78.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                        nfcA.close()
                        res
                    }.getOrNull()
                    if (ridResp != null && ridResp.isNotEmpty()) {
                        val hr0 = ridResp[0].toInt() and 0xFF
                        return when (hr0) {
                            0x11 -> "Topaz 96 (Innovision Type 1 Tag)"
                            0x12 -> "Topaz 512 (Innovision Type 1 Tag - 512 Bytes)"
                            0x13 -> "Topaz 1024 / Jewel (Innovision Type 1 Tag - 1024 Bytes)"
                            else -> "Topaz / Type 1 Tag"
                        }
                    }
                    return "Topaz 512 / Topaz 1024 (Innovision Type 1 Tag)"
                }
            }
        }

        // 3. Check NTAG, MIFARE Ultralight, and ST25TN (GET_VERSION 0x60 over NfcA)
        val mfu = MifareUltralight.get(tag)
        if (mfu != null || nfcA != null) {
            val versionResp = runCatching {
                val a = nfcA ?: return@runCatching null
                if (!a.isConnected) a.connect()
                val v = a.transceive(byteArrayOf(0x60.toByte()))
                v
            }.getOrNull()

            if (versionResp != null && versionResp.size >= 8) {
                val vendor = versionResp[1].toInt() and 0xFF
                val prodType = versionResp[2].toInt() and 0xFF
                val sizeByte = versionResp[6].toInt() and 0xFF

                if (vendor == 0x04) { // NXP
                    if (prodType == 0x04) { // NTAG
                        return when (sizeByte) {
                            0x0F -> "NTAG213 (NXP 144 Bytes User Memory)"
                            0x11 -> "NTAG215 (NXP 504 Bytes User Memory)"
                            0x13 -> "NTAG216 (NXP 888 Bytes User Memory)"
                            0x0B -> "NTAG210 (NXP 48 Bytes Memory)"
                            0x0E -> "NTAG212 (NXP 128 Bytes Memory)"
                            0x1B -> "NTAG213 TagTamper (NXP Tamper Detection)"
                            else -> "NTAG21x Series (NXP ISO 14443-A)"
                        }
                    } else if (prodType == 0x03) { // MIFARE Ultralight
                        return when (sizeByte) {
                            0x0B -> "MIFARE Ultralight C (NXP 192 Bytes, 3DES Crypto)"
                            0x0E -> "MIFARE Ultralight EV1 (NXP 128 Bytes)"
                            else -> "MIFARE Ultralight (NXP ISO 14443-A)"
                        }
                    }
                } else if (vendor == 0x02) { // STMicroelectronics
                    return "ST25TN Series (STMicroelectronics ISO 14443-A Type 2)"
                }
            }

            // Fallback by 7-byte UID vendor byte
            val uid = tag.id
            if (uid != null && uid.size == 7) {
                if ((uid[0].toInt() and 0xFF) == 0x02) {
                    return "ST25TN Series (STMicroelectronics 7-Byte UID Type 2)"
                }
            }

            if (mfu != null) {
                return when (mfu.type) {
                    MifareUltralight.TYPE_ULTRALIGHT_C -> "MIFARE Ultralight C (192 Bytes)"
                    MifareUltralight.TYPE_ULTRALIGHT -> "MIFARE Ultralight (64 Bytes)"
                    else -> "MIFARE Ultralight Family"
                }
            }
        }

        // 4. Check ISO-DEP (DESFire, MIFARE Plus SL2/SL3, SmartCards)
        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            val desfireVersion = runCatching {
                isoDep.connect()
                val resp = isoDep.transceive(byteArrayOf(0x90.toByte(), 0x60.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()))
                isoDep.close()
                resp
            }.getOrNull()

            if (desfireVersion != null && desfireVersion.size >= 7 && (desfireVersion[desfireVersion.size - 2].toInt() and 0xFF) == 0x91) {
                val hwSubtype = desfireVersion[3].toInt() and 0xFF
                val storage = desfireVersion[5].toInt() and 0xFF
                val sizeStr = when (storage) {
                    0x18 -> "2KB"
                    0x1A -> "4KB"
                    0x1C -> "8KB"
                    0x1E -> "16KB"
                    0x20 -> "32KB"
                    else -> ""
                }
                val model = when (hwSubtype) {
                    0x01 -> "MIFARE DESFire EV1 $sizeStr"
                    0x02 -> "MIFARE DESFire EV2 $sizeStr"
                    0x03 -> "MIFARE DESFire EV3 $sizeStr"
                    0x04 -> "MIFARE DESFire Light"
                    else -> "MIFARE DESFire Series $sizeStr"
                }
                return model
            }
            return "ISO-DEP Smart Card / MIFARE DESFire / Plus"
        }

        // 5. Check MIFARE Classic / MIFARE Plus SL1
        val mfc = MifareClassic.get(tag)
        if (mfc != null) {
            return when (mfc.type) {
                MifareClassic.TYPE_CLASSIC -> when (mfc.size) {
                    MifareClassic.SIZE_1K -> "MIFARE Classic 1K (NXP 1024 Bytes)"
                    MifareClassic.SIZE_4K -> "MIFARE Classic 4K (NXP 4096 Bytes)"
                    MifareClassic.SIZE_MINI -> "MIFARE Classic Mini (NXP 320 Bytes)"
                    else -> "MIFARE Classic (${mfc.size} Bytes)"
                }
                MifareClassic.TYPE_PLUS -> "MIFARE Plus (Security Level 1)"
                MifareClassic.TYPE_PRO -> "MIFARE Pro"
                else -> "MIFARE Classic Family"
            }
        }

        // Fallback MIFARE Classic detection via SAK on non-NXP NFC controllers
        if (nfcA != null) {
            val sak = nfcA.sak.toInt() and 0xFF
            return when (sak) {
                0x08 -> "MIFARE Classic 1K (Identified by SAK 0x08)"
                0x18 -> "MIFARE Classic 4K (Identified by SAK 0x18)"
                0x09 -> "MIFARE Classic Mini (Identified by SAK 0x09)"
                0x10, 0x11 -> "MIFARE Plus 2K/4K (Identified by SAK 0x%02X)".format(sak)
                0x01 -> "TNAP / MIFARE 1K Compatible"
                else -> "NFC-A / ISO 14443-3A Tag (SAK=0x%02X)".format(sak)
            }
        }

        // 6. FeliCa
        val nfcF = NfcF.get(tag)
        if (nfcF != null) {
            val sysCodeHex = nfcF.systemCode.hex()
            return if (sysCodeHex.contains("12 FC", ignoreCase = true)) {
                "FeliCa Lite-S (Sony 13.56MHz)"
            } else if (sysCodeHex.contains("00 03", ignoreCase = true)) {
                "FeliCa Transportation Card (Suica/Pasmo)"
            } else {
                "Sony FeliCa Tag (NFC-F SystemCode: $sysCodeHex)"
            }
        }

        return "Standard NFC Tag (${tag.techList.joinToString { it.substringAfterLast('.') }})"
    }

    private fun computeAttendanceInfo(tag: Tag): AttendanceCardInfo {
        val uid = tag.id
        if (uid == null || uid.isEmpty()) {
            return AttendanceCardInfo(
                wiegand26Facility = null,
                wiegand26Card = null,
                wiegand26Dec10 = null,
                wiegand34Facility = null,
                wiegand34Card = null,
                uidHexBigEndian = null,
                uidHexLittleEndian = null,
                attendanceMachineNotes = "No hardware UID exposed by Android for this tag."
            )
        }

        val bigEndianHex = uid.joinToString(" ") { "%02X".format(it) }
        val littleEndianHex = uid.reversedArray().joinToString(" ") { "%02X".format(it) }

        // Convert UID to 32-bit integer (taking last 4 bytes)
        val lower4Bytes = if (uid.size >= 4) uid.copyOfRange(uid.size - 4, uid.size) else uid
        var num32: Long = 0
        for (b in lower4Bytes) {
            num32 = (num32 shl 8) or (b.toLong() and 0xFF)
        }

        // Wiegand 26-bit: Lower 24 bits -> Facility Code (bits 16-23) & Card Number (bits 0-15)
        val num24 = num32 and 0xFFFFFF
        val w26Facility = ((num24 shr 16) and 0xFF).toInt()
        val w26Card = (num24 and 0xFFFF).toInt()
        val w26Dec10 = "%010d".format(num24) // 10-digit decimal like 0009248751

        // Wiegand 34-bit: 32 bits -> Facility Code (bits 16-31) & Card Number (bits 0-15)
        val w34Facility = ((num32 shr 16) and 0xFFFF).toInt()
        val w34Card = (num32 and 0xFFFF).toInt()

        val notes = buildString {
            append("Biometric Attendance Card Details:\n")
            append("• Wiegand 26-bit: Facility $w26Facility, Card %05d (10-Digit Decimal: $w26Dec10)\n".format(w26Card))
            append("• Wiegand 34-bit: Facility $w34Facility, Card $w34Card\n")
            append("• Big-Endian Hex: $bigEndianHex | Little-Endian Hex: $littleEndianHex")
        }

        return AttendanceCardInfo(
            wiegand26Facility = w26Facility,
            wiegand26Card = w26Card,
            wiegand26Dec10 = w26Dec10,
            wiegand34Facility = w34Facility,
            wiegand34Card = w34Card,
            uidHexBigEndian = bigEndianHex,
            uidHexLittleEndian = littleEndianHex,
            attendanceMachineNotes = notes
        )
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
}

private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }
