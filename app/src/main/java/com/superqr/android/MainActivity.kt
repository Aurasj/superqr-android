package com.superqr.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.superqr.android.ui.AppRoot
import com.superqr.android.colorgrid8.ColorGrid8DiagnosticCapture
import com.superqr.android.ui.theme.SuperQRAndroidTheme
import com.superqr.android.vision.lab.colorgrid8.MacrochromaNativeBenchRunner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val benchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            analysisExecutor.execute {
                MacrochromaNativeBenchRunner.runOnDeviceBenchmark()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleBenchIntent(intent)
        val filter = IntentFilter("com.superqr.android.RUN_MACROCHROMA_NATIVE_BENCH")
        ContextCompat.registerReceiver(this, benchReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        enableEdgeToEdge()
        setContent {
            SuperQRAndroidTheme {
                AppRoot(analysisExecutor = analysisExecutor)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleBenchIntent(intent)
    }

    private fun handleBenchIntent(intent: Intent?) {
        if (BuildConfig.BUILD_TYPE == "benchmark" && intent?.getBooleanExtra("colorgrid_capture", false) == true) {
            getExternalFilesDir(null)?.let { ColorGrid8DiagnosticCapture.request(it) }
        }
        if (intent?.getStringExtra("bench") == "macrochroma" || intent?.getStringExtra("action") == "bench_macrochroma") {
            analysisExecutor.execute {
                MacrochromaNativeBenchRunner.runOnDeviceBenchmark()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(benchReceiver)
        } catch (_: Throwable) {}
        analysisExecutor.shutdown()
    }
}
