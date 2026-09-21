package com.teamflow.nfctool

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.Tag
import com.teamflow.nfctool.domain.TagSnapshot
import com.teamflow.nfctool.nfc.NfcRepository

/** Test double for state/UI tests; it never talks to NFC hardware. */
class MockNfcRepository(var snapshot: Result<TagSnapshot> = Result.failure(IllegalStateException("No mock scan configured"))) : NfcRepository {
    override fun isSupported() = true
    override fun isEnabled() = true
    override fun startScanning(activity: Activity, onTag: (Tag) -> Unit) = Unit
    override fun stopScanning(activity: Activity) = Unit
    override suspend fun readTag(tag: Tag): Result<TagSnapshot> = snapshot
    override suspend fun writeNdef(tag: Tag, message: NdefMessage): Result<Unit> = Result.success(Unit)
}
