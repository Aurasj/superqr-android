package com.superqr.android.vision.v6.classification

fun interface ChromaPixelReader {
    fun read(imageX: Double, imageY: Double, destination: IntArray): Boolean
}
