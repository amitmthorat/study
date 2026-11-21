package com.airline.mrz.ui.theme

import androidx.compose.ui.graphics.Color

/** Colours of the scanning screen. Integrators pass their own instance to the screen. */
public data class MrzTheme(
    val scrim: Color = Color(0xAA000000),
    val guide: Color = Color.White,
    val guideActive: Color = Color(0xFF4CAF50),
    val bandSearching: Color = Color(0x80FFFFFF),
    val bandReading: Color = Color(0xFF4CAF50),
    val bandRejected: Color = Color(0xFFFFC107),
    val hintBackground: Color = Color(0xCC000000),
    val hintText: Color = Color.White,
    val progress: Color = Color(0xFF4CAF50),
) {
    public companion object {
        public val Default: MrzTheme = MrzTheme()
    }
}
