package com.superqr.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.superqr.android.ui.scanner.SuperQRScannerScreen
import com.superqr.android.ui.v6.theme.SuperQRAndroidTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SuperQRAndroidTheme {
                SuperQRScannerScreen(
                    analysisExecutor = analysisExecutor,
                    lifecycleOwner = this,
                    modifier = Modifier.fillMaxSize(),
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
