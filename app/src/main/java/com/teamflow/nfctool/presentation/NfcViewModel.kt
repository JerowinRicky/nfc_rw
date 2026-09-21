package com.teamflow.nfctool.presentation

import android.app.Activity
import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamflow.nfctool.domain.*
import com.teamflow.nfctool.nfc.NfcRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class NfcViewModel(private val repository:NfcRepository, private val context:Context):ViewModel() {
    private val _state=MutableStateFlow<ScanState>(ScanState.Idle); val state:StateFlow<ScanState> = _state.asStateFlow()
    private val _history=MutableStateFlow(loadHistory()); val history:StateFlow<List<HistoryItem>> = _history.asStateFlow()
    var currentTag:Tag?=null; var savePayloads=false; var detailedLogging=false
    fun supported()=repository.isSupported(); fun enabled()=repository.isEnabled()
    fun scan(activity:Activity) { if(!supported()) { fail("No NFC hardware", "NfcAdapter.getDefaultAdapter returned null.", "This device does not support NFC."); return }; if(!enabled()) { fail("NFC is disabled", "The Android NFC adapter is present but disabled.", "Enable NFC in system settings."); return }; _state.value=ScanState.Scanning; repository.startScanning(activity) { tag -> repository.stopScanning(activity); currentTag=tag; _state.value=ScanState.Reading; viewModelScope.launch { repository.readTag(tag).fold({ snapshot -> _state.value=ScanState.Success(snapshot); addHistory(snapshot) }, { exception -> mapError(exception) }) } } }
    fun stop(activity:Activity) { repository.stopScanning(activity); if(_state.value is ScanState.Scanning) _state.value=ScanState.Idle }
    fun write(records:List<NdefRecord>) { val tag=currentTag ?: run { fail("No tag in range", "No discovered Android Tag instance is retained.", "Scan the target tag again and keep it against the phone."); return }; viewModelScope.launch { _state.value=ScanState.Writing; repository.writeNdef(tag,NdefMessage(records.toTypedArray())).fold({ _state.value=ScanState.WriteSuccess("NDEF message written successfully.") }, ::mapError) } }
    fun reset() { _state.value=ScanState.Idle }
    private fun fail(message:String, tech:String, action:String) { _state.value=ScanState.Error(NfcFailure.Simple(message,tech,action)) }
    private fun mapError(e:Throwable) { val (m,a)=when { e is android.nfc.TagLostException -> "Tag removed too early" to "Keep the tag steady against the phone and try again."; e.message?.contains("read-only",true)==true -> "Tag is not writable" to "Use an unlocked writable tag."; e.message?.contains("capacity",true)==true -> "Insufficient capacity" to "Use a smaller NDEF message or a larger tag."; else -> "NFC operation failed" to "Try scanning again. If this persists, the tag may use an unsupported or protected interface." }; fail(m,"${e.javaClass.simpleName}: ${e.message ?: "No detail supplied"}",a) }
    private fun addHistory(t:TagSnapshot) { val item=HistoryItem(t.scannedAt,t.scannedAt,t.uid,t.technologies.joinToString { it.name },t.ndefSupported,t.writable,t.protection,t.ndefRecords.size); _history.value=(listOf(item)+_history.value).take(50); persistHistory() }
    fun deleteHistory(id:Long) { _history.value=_history.value.filterNot { it.id==id }; persistHistory() }
    private fun prefs()=context.getSharedPreferences("history",Context.MODE_PRIVATE)
    private fun loadHistory():List<HistoryItem> = runCatching { val a=JSONArray(prefs().getString("items","[]")); List(a.length()){ i -> a.getJSONObject(i).let { HistoryItem(it.getLong("id"),it.getLong("time"),it.optString("uid").ifBlank { null },it.getString("tech"),it.getBoolean("ndef"),if(it.isNull("writable")) null else it.getBoolean("writable"),ProtectionStatus.valueOf(it.getString("protection")),it.getInt("records")) } } }.getOrDefault(emptyList())
    private fun persistHistory() { val a=JSONArray(); _history.value.forEach { h -> a.put(JSONObject().put("id",h.id).put("time",h.timestamp).put("uid",h.uid ?: "").put("tech",h.technologies).put("ndef",h.ndef).put("writable",h.writable).put("protection",h.protection.name).put("records",h.records)) }; prefs().edit().putString("items",a.toString()).apply() }
}
