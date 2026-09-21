package com.teamflow.nfctool.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.Tag
import com.teamflow.nfctool.domain.TagSnapshot

interface NfcRepository {
    fun isSupported(): Boolean
    fun isEnabled(): Boolean
    fun startScanning(activity: Activity, onTag: (Tag) -> Unit)
    fun stopScanning(activity: Activity)
    suspend fun readTag(tag: Tag): Result<TagSnapshot>
    suspend fun writeNdef(tag: Tag, message: NdefMessage): Result<Unit>
}
