package com.teamflow.nfctool.presentation
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.teamflow.nfctool.nfc.AndroidNfcRepository
class NfcViewModelFactory(private val context:Context):ViewModelProvider.Factory { override fun <T:ViewModel> create(modelClass:Class<T>):T { @Suppress("UNCHECKED_CAST") return NfcViewModel(AndroidNfcRepository(context.applicationContext),context.applicationContext) as T } }
