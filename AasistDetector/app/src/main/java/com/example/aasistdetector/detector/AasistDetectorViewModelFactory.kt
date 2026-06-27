package com.example.aasistdetector.detector

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class AasistDetectorViewModelFactory(
    private val assetManager: AssetManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AasistDetectorViewModel(assetManager) as T
    }
}
