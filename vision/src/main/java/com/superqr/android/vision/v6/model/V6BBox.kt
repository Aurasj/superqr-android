package com.superqr.android.vision.v6.model

data class V6BBox(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    val width: Double get() = x2 - x1
    val height: Double get() = y2 - y1
    val centerX: Double get() = (x1 + x2) / 2.0
    val centerY: Double get() = (y1 + y2) / 2.0
}
