package com.smartscan.app

import android.app.Application
import android.util.Log
import com.smartscan.app.data.DocumentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.opencv.android.OpenCVLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("SmartScan", "OpenCV failed to load")
        DocumentRepository.init(this)
    }

    companion object {
        /** Survives screen changes, used for work that must not be cancelled midway. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
