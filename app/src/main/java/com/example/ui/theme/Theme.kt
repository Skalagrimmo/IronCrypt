package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val IronCryptColorScheme = darkColorScheme(
    primary = MoltenBrass,
    onPrimary = FoundryVoid,
    primaryContainer = MoltenBrassDim,
    onPrimaryContainer = AgedParchment,
    secondary = CrimsonForge,
    onSecondary = AgedParchment,
    secondaryContainer = CrimsonDark,
    onSecondaryContainer = AgedParchment,
    tertiary = ArcaneCobalt,
    onTertiary = AgedParchment,
    background = FoundryVoid,
    onBackground = AgedParchment,
    surface = IronPanel,
    onSurface = AgedParchment,
    surfaceVariant = IronSurfaceHigh,
    onSurfaceVariant = SteelMist,
    outline = IronBorder,
    error = CrimsonForge,
    onError = AgedParchment
)

private val IronCryptShapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp)
)

@Composable
fun IronCryptTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IronCryptColorScheme,
        typography = IronCryptTypography,
        shapes = IronCryptShapes,
        content = content
    )
}
