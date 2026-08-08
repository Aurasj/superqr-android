package com.superqr.android.ui.scanner

/**
 * Numeric formatting helper for scanner UI.
 *
 * Kotlin can infer mixed Long/Double Elvis expressions as Number. Java's
 * Formatter rejects a boxed Long for %f, so normalize floating conversions
 * explicitly while preserving integer formatting such as %08X.
 */
internal fun String.format(value: Number): String =
    if (contains("f")) {
        java.lang.String.format(java.util.Locale.US, this, value.toDouble())
    } else {
        java.lang.String.format(java.util.Locale.US, this, value)
    }
