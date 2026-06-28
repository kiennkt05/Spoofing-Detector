package com.example.aasistdetector.detector

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class AasistDetectorViewModelFactory(
    private val assetManager: AssetManager,
    private val preferredDefaultModel: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AasistDetectorViewModel(assetManager, preferredDefaultModel) as T
    }
}
