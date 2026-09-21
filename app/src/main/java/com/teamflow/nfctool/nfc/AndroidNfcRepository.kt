package com.teamflow.nfctool.nfc

import android.app.Activity
import android.content.Context
import android.nfc.*
import android.nfc.tech.*
import com.teamflow.nfctool.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidNfcRepository(context: Context) : NfcRepository {
    private val adapter = NfcAdapter.getDefaultAdapter(context)
    override fun isSupported() = adapter != null
    override fun isEnabled() = adapter?.isEnabled == true
    override fun startScanning(activity: Activity, onTag: (Tag) -> Unit) {
        adapter?.enableReaderMode(activity, { onTag(it) }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE, null)
    }
    override fun stopScanning(activity: Activity) { adapter?.disableReaderMode(activity) }
    override suspend fun readTag(tag: Tag): Result<TagSnapshot> = withContext(Dispatchers.IO) { runCatching {
        val ndef = Ndef.get(tag); val formattable=NdefFormatable.get(tag)
        var message: NdefMessage? = null; var size:Int?=null; var max:Int?=null; var writable:Boolean?=null; var ndefProblem:String?=null
        if (ndef != null) try { ndef.connect(); message=ndef.ndefMessage; size=ndef.ndefMessage?.toByteArray()?.size ?: 0; max=ndef.maxSize; writable=ndef.isWritable } catch (e: TagLostException) { throw e } catch(e: Exception) { ndefProblem="NDEF interface could not be read: ${e.javaClass.simpleName}" } finally { runCatching { ndef.close() } }
        val techs=technologyDetails(tag, ndef, formattable)
        val protection=when { ndef == null && formattable != null -> ProtectionStatus.WRITABLE
            ndef != null && writable == true -> ProtectionStatus.UNPROTECTED
            ndef != null && writable == false -> ProtectionStatus.READ_ONLY
            ndefProblem != null -> ProtectionStatus.PARTIAL
            else -> ProtectionStatus.UNSUPPORTED }
        val reason=when(protection) { ProtectionStatus.WRITABLE -> "The tag is formatable for NDEF; no existing NDEF content was exposed."
            ProtectionStatus.UNPROTECTED -> "NDEF content was read and Android reports that it is writable."
            ProtectionStatus.READ_ONLY -> "Android reports that the exposed NDEF area is not writable. Lock type is not exposed by this API."
            ProtectionStatus.PARTIAL -> "Some tag metadata was exposed, but the NDEF interface could not be accessed. This does not prove encryption or protection. ${ndefProblem ?: ""}"
            ProtectionStatus.UNSUPPORTED -> "This tag has no Android-exposed NDEF interface. Its memory may be absent, protected, or unsupported; Android cannot distinguish these cases."
            else -> "Unavailable through Android NFC API." }
        TagSnapshot(tag.id?.hex(), techs, message?.let(NdefCodec::parse).orEmpty(), ndef != null, size, max, writable, protection, reason, message?.toByteArray())
    } }
    override suspend fun writeNdef(tag: Tag, message: NdefMessage): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        val ndef=Ndef.get(tag); val formattable=NdefFormatable.get(tag)
        when { ndef != null -> { ndef.connect(); try { check(ndef.isWritable) { "The NDEF area is read-only." }; check(message.toByteArray().size <= ndef.maxSize) { "Message needs ${message.toByteArray().size} bytes; tag capacity is ${ndef.maxSize} bytes." }; ndef.writeNdefMessage(message) } finally { runCatching { ndef.close() } } }
            formattable != null -> { formattable.connect(); try { formattable.format(message) } finally { runCatching { formattable.close() } } }
            else -> error("This tag does not expose a writable NDEF or NDEF-formatable interface.") }
    } }
    private fun technologyDetails(tag:Tag, ndef:Ndef?, formattable:NdefFormatable?):List<TechInfo> = tag.techList.map { name -> when(name) {
        NfcA::class.java.name -> NfcA.get(tag)?.let { TechInfo("NfcA", listOf("ATQA" to (it.atqa?.hex() ?: "Unavailable through Android API"), "SAK" to "%02X".format(it.sak))) }
        IsoDep::class.java.name -> IsoDep.get(tag)?.let { TechInfo("IsoDep", listOf("Historical bytes" to (it.historicalBytes?.hex() ?: "Unavailable through Android API"), "Hi-layer response" to (it.hiLayerResponse?.hex() ?: "Unavailable through Android API"), "Max transceive" to "${it.maxTransceiveLength} bytes")) }
        NfcB::class.java.name -> NfcB.get(tag)?.let { TechInfo("NfcB", listOf("Application data" to it.applicationData.hex(), "Protocol info" to it.protocolInfo.hex())) }
        NfcF::class.java.name -> NfcF.get(tag)?.let { TechInfo("NfcF", listOf("System code" to it.systemCode.hex(), "Manufacturer" to it.manufacturer.hex())) }
        NfcV::class.java.name -> NfcV.get(tag)?.let { TechInfo("NfcV", listOf("DSF ID" to "%02X".format(it.dsfId), "Response flags" to "%02X".format(it.responseFlags))) }
        MifareClassic::class.java.name -> MifareClassic.get(tag)?.let { TechInfo("MifareClassic", listOf("Type" to it.type.toString(), "Size" to "${it.size} bytes", "Sectors" to it.sectorCount.toString(), "Note" to "No authentication is attempted.")) }
        MifareUltralight::class.java.name -> MifareUltralight.get(tag)?.let { TechInfo("MifareUltralight", listOf("Type" to it.type.toString(), "Note" to "No password-authentication attempt is made.")) }
        Ndef::class.java.name -> TechInfo("Ndef", listOf("Supported" to "Yes", "Format" to (ndef?.type ?: "Unavailable")))
        NdefFormatable::class.java.name -> TechInfo("NdefFormatable", listOf("Can format" to (formattable != null).toString()))
        else -> TechInfo(name.substringAfterLast('.'), listOf("Status" to "Detected by Android")) } ?: TechInfo(name.substringAfterLast('.'), listOf("Status" to "Detected; details unavailable")) }
}
private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }
