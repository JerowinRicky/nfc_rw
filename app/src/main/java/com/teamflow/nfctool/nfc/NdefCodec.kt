package com.teamflow.nfctool.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.teamflow.nfctool.domain.NdefRecordInfo

object NdefCodec {
    fun parse(message: NdefMessage) = message.records.map(::parseRecord)
    fun parseRecord(r: NdefRecord): NdefRecordInfo { val raw = r.toByteArray().hex(); return when {
        r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) -> { if (r.payload.isEmpty()) NdefRecordInfo(r.tnf,"T","Text","Malformed text record",raw) else { val status=r.payload[0].toInt(); val length=status and 0x3f; if(r.payload.size < length+1) NdefRecordInfo(r.tnf,"T","Text","Malformed text record",raw) else { val lang=r.payload.copyOfRange(1,length+1).toString(Charsets.US_ASCII); val text=r.payload.copyOfRange(length+1,r.payload.size).toString(if(status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8); NdefRecordInfo(r.tnf,"T","Text",text,raw,lang) } } }
        r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI) -> NdefRecordInfo(r.tnf,"U","URI",decodeUri(r.payload),raw)
        r.tnf == NdefRecord.TNF_MIME_MEDIA -> NdefRecordInfo(r.tnf,r.type.toString(Charsets.US_ASCII),"MIME",r.payload.safeText(),raw)
        r.tnf == NdefRecord.TNF_EXTERNAL_TYPE -> NdefRecordInfo(r.tnf,r.type.toString(Charsets.US_ASCII),"External type",r.payload.safeText(),raw)
        r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_SMART_POSTER) -> NdefRecordInfo(r.tnf,"Sp","Smart Poster","Nested NDEF message (${r.payload.size} bytes)",raw)
        else -> NdefRecordInfo(r.tnf,r.type.toString(Charsets.US_ASCII),"Raw / unsupported record","Payload shown as hexadecimal",raw) } }
    fun text(value:String, language:String)=NdefRecord.createTextRecord(language,value)
    fun uri(value:String)=NdefRecord.createUri(value)
    fun mime(type:String,value:String)=NdefRecord.createMime(type,value.toByteArray())
    fun external(type:String,value:String)=NdefRecord.createExternal(type,value.toByteArray())
    fun raw(hex:String):NdefRecord { val clean=hex.replace(Regex("[^0-9A-Fa-f]"),""); require(clean.length%2==0){"Raw data must contain full hexadecimal bytes"}; return NdefRecord(NdefRecord.TNF_UNKNOWN,ByteArray(0),ByteArray(0),ByteArray(clean.length/2){clean.substring(it*2,it*2+2).toInt(16).toByte()}) }
    private fun decodeUri(b:ByteArray):String { if(b.isEmpty()) return ""; val p=arrayOf("","http://www.","https://www.","http://","https://","tel:","mailto:","ftp://anonymous:anonymous@","ftp://ftp.","ftps://","sftp://","smb://","nfs://","ftp://","dav://","news:","telnet://","imap:","rtsp://","urn:","pop:","sip:","sips:","tftp:","btspp://","btl2cap://","btgoep://","tcpobex://","irdaobex://","file://","urn:epc:id:","urn:epc:tag:","urn:epc:pat:","urn:epc:raw:","urn:epc:","urn:nfc:"); return p.getOrElse(b[0].toInt() and 255){""}+b.drop(1).toByteArray().toString(Charsets.UTF_8) }
    private fun ByteArray.safeText()=runCatching{toString(Charsets.UTF_8)}.getOrElse{hex()}
    fun ByteArray.hex()=joinToString(" "){"%02X".format(it)}
}
