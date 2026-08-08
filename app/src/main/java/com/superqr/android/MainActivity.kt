package com.superqr.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superqr.android.ui.v6.V6StaticScreen
import com.superqr.android.ui.v6.theme.SuperQRAndroidTheme
import com.superqr.android.ui.v7_capacity_lab.V7CapacityLabScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val analysisExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SuperQRAndroidTheme {
                var showV7 by remember { mutableStateOf(false) }
                if (showV7) {
                    V7CapacityLabScreen(onBack = { showV7 = false })
                } else {
                    Box(Modifier.fillMaxSize()) {
                        V6StaticScreen(
                            analysisExecutor = analysisExecutor,
                            lifecycleOwner = this@MainActivity,
                            modifier = Modifier.fillMaxSize(),
                            onBack = { finish() }
                        )
                        // V7 Capacity Lab quick-entry button (top-right, debug only)
                        TextButton(
                            onClick = { showV7 = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(top = 52.dp, end = 8.dp)
                        ) {
                            Text(
                                "V7 Lab",
                                color = Color(0xFFA6E3A1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
