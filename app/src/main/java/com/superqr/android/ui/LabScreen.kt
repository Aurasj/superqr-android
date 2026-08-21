package com.superqr.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ExecutorService

/** Explicit experimental surface; no LAB mode can enter production transfer state. */
@Composable
fun LabScreen(analysisExecutor: ExecutorService) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "PROMISING · COLORGRID8",
            color = Color(0xFF8AB4F8),
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        ColorGrid8LabScreen(analysisExecutor = analysisExecutor, modifier = Modifier.weight(1f))
    }
}
