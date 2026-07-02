package com.example.aasistdetector.detector

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

import android.content.SharedPreferences

class AasistDetectorViewModelFactory(
    private val assetManager: AssetManager,
    private val preferredDefaultModel: String,
    private val sharedPreferences: SharedPreferences
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AasistDetectorViewModel(assetManager, preferredDefaultModel, sharedPreferences) as T
    }
}
