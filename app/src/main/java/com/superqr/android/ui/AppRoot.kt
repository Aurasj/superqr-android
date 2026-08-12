package com.superqr.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ExecutorService

private enum class AppSection { RECEIVE, LAB }

/** Production transfer is the default app surface; the physical campaign lab is isolated behind LAB. */
@Composable
fun AppRoot(analysisExecutor: ExecutorService) {
    var section by rememberSaveable { mutableStateOf(AppSection.RECEIVE) }
    Column(Modifier.fillMaxSize().background(Color(0xFF090B10))) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppSection.entries.forEach { candidate ->
                val active = candidate == section
                Button(
                    onClick = { section = candidate },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) Color(0xFF1976D2) else Color.White.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (candidate == AppSection.RECEIVE) "RECEIVE" else "LAB", fontSize = 12.sp)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (section == AppSection.RECEIVE) {
                TransferScreen(analysisExecutor = analysisExecutor)
            } else {
                MainScreen(analysisExecutor = analysisExecutor)
            }
        }
    }
}
