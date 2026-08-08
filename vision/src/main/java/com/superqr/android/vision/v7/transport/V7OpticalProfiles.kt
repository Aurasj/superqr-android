package com.superqr.android.vision.v7.transport

/** Shared V7 optical profiles. Must match superqr-protocol/contracts/v7_transport_contract.json. */
data class V7OpticalProfile(
    val id: Int,
    val key: String,
    val label: String,
    val grid: Int,
    val paletteName: String,
) {
    val bitsPerCell: Int get() = if (paletteName == "v6_reference_4") 2 else 3
    val colorCount: Int get() = 1 shl bitsPerCell
    val cellCount: Int get() = grid * grid
    val frameSize: Int get() = cellCount * bitsPerCell / 8
    val payloadSize: Int get() = frameSize - 20
}

object V7OpticalProfiles {
    val payloadBbox = doubleArrayOf(100.0, 190.0, 900.0, 810.0)

    val all = listOf(
        V7OpticalProfile(0, "safe_40_4", "40×40 • 4 colors • Safe", 40, "v6_reference_4"),
        V7OpticalProfile(1, "balanced_48_4", "48×48 • 4 colors • Balanced", 48, "v6_reference_4"),
        V7OpticalProfile(2, "fast_56_4", "56×56 • 4 colors • Fast", 56, "v6_reference_4"),
        V7OpticalProfile(3, "turbo_64_4", "64×64 • 4 colors • Turbo", 64, "v6_reference_4"),
        V7OpticalProfile(4, "stress_72_4", "72×72 • 4 colors • Stress", 72, "v6_reference_4"),
        V7OpticalProfile(5, "stress_80_4", "80×80 • 4 colors • Stress+", 80, "v6_reference_4"),
        V7OpticalProfile(6, "color_40_8", "40×40 • 8 colors • Color", 40, "candidate_8_a"),
        V7OpticalProfile(7, "color_48_8", "48×48 • 8 colors • Color Fast", 48, "candidate_8_a"),
        V7OpticalProfile(8, "color_56_8", "56×56 • 8 colors • Color Turbo", 56, "candidate_8_a"),
        V7OpticalProfile(9, "color_64_8", "64×64 • 8 colors • Color Stress", 64, "candidate_8_a"),
    )

    private val byId = all.associateBy { it.id }
    fun byId(id: Int): V7OpticalProfile? = byId[id]
    val default: V7OpticalProfile = all[1]

    // MSB-first four monochrome profile cells.
    val profileCodeCenters = arrayOf(
        doubleArrayOf(442.5, 160.0),
        doubleArrayOf(477.5, 160.0),
        doubleArrayOf(512.5, 160.0),
        doubleArrayOf(547.5, 160.0),
    )

    val extraPilotCenters = mapOf(
        "GREEN" to doubleArrayOf(215.0, 160.0),
        "YELLOW" to doubleArrayOf(255.0, 160.0),
        "CYAN" to doubleArrayOf(745.0, 160.0),
        "MAGENTA" to doubleArrayOf(785.0, 160.0),
    )
}
