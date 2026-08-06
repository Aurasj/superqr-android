package com.superqr.android.ui.v6

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticLogEntry(
    val level: String,
    val category: String,
    val signature: String,
    val message: String,
    val timestamp: String,
    var count: Int = 1
)

class V6DiagnosticLogger {
    private val logs = mutableListOf<DiagnosticLogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(level: String, category: String, signature: String, message: String) {
        val now = dateFormat.format(Date())
        val tag = "V6_$category"
        when (level) {
            "INFO" -> Log.i(tag, message)
            "WARN" -> Log.w(tag, message)
            "ERROR" -> Log.e(tag, message)
            else -> Log.d(tag, message)
        }

        synchronized(logs) {
            val last = logs.lastOrNull()
            if (last != null && last.signature == signature) {
                last.count++
            } else {
                logs.add(
                    DiagnosticLogEntry(
                        level = level,
                        category = category,
                        signature = signature,
                        message = message,
                        timestamp = now
                    )
                )
            }
        }
    }

    fun getLogs(): List<DiagnosticLogEntry> {
        synchronized(logs) {
            return logs.toList()
        }
    }

    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
    }
}
