package org.fossify.calendar.helpers

/**
 * One colour per context, so the key can tell contexts apart. With untitled stripes, colour is
 * a context's only identity, and contexts from one calendar would otherwise all share its colour
 * (seen on the emulator in Phase 16). Each new context title takes the lowest palette slot no
 * other title holds, and keeps it for good, so a context looks the same in every week and month.
 * Pure: the slot map is stored by Config.
 */
object ContextualColors {
    // saturated Material 600s, distinct at the stripes' 15-20% tint in light and dark themes
    val PALETTE = intArrayOf(
        0xFF1E88E5.toInt(), // blue
        0xFFE53935.toInt(), // red
        0xFF43A047.toInt(), // green
        0xFFFB8C00.toInt(), // orange
        0xFF8E24AA.toInt(), // purple
        0xFF00ACC1.toInt(), // cyan
        0xFFD81B60.toInt(), // pink
        0xFFFFB300.toInt(), // amber
        0xFF5E35B1.toInt(), // deep purple
        0xFF7CB342.toInt(), // light green
    )

    fun key(title: String) = title.trim().lowercase()

    /**
     * The slot for [title], and the updated map when it had none (null when unchanged, so the
     * caller only writes prefs for a new context). Past the palette size, slots repeat in order.
     */
    fun assign(slots: Map<String, Int>, title: String, paletteSize: Int = PALETTE.size): Pair<Int, Map<String, Int>?> {
        val key = key(title)
        slots[key]?.let { return it to null }

        val used = slots.values.toSet()
        val slot = (0 until paletteSize).firstOrNull { it !in used } ?: (slots.size % paletteSize)
        return slot to (slots + (key to slot))
    }
}
