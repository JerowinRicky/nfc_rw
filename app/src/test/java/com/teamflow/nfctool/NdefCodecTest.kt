package com.teamflow.nfctool

import android.nfc.NdefMessage
import com.teamflow.nfctool.nfc.NdefCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdefCodecTest {
    @Test fun parsesTextRecord() { val result=NdefCodec.parse(NdefMessage(arrayOf(NdefCodec.text("Hello World","en")))).single(); assertEquals("Text",result.kind);assertEquals("Hello World",result.value);assertEquals("en",result.language) }
    @Test fun parsesUriRecord() { val result=NdefCodec.parse(NdefMessage(arrayOf(NdefCodec.uri("https://example.com")))).single();assertEquals("URI",result.kind);assertTrue(result.value.contains("example.com")) }
    @Test fun rejectsOddRawHex(){ val error=runCatching{NdefCodec.raw("A")}.exceptionOrNull();assertTrue(error is IllegalArgumentException) }
}
