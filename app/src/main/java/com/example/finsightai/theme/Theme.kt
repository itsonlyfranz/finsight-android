package com.example.finsightai.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class AppTheme(val displayName: String, val shortName: String) {
    DECK_EMERALD("Deck Emerald", "Emerald"),
    CYBER_SLATE("Cyber Slate", "Cyber"),
    MIDNIGHT_BLUE("Midnight Blue", "Midnight")
}

val LocalAppTheme = staticCompositionLocalOf { AppTheme.DECK_EMERALD }

val FinSightDarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color(0xFF003822),
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = BrightEmerald,
    secondary = MintSecondary,
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF004F47),
    onSecondaryContainer = Color(0xFF70F5E4),
    tertiary = CyanAccent,
    onTertiary = Color(0xFF00363F),
    tertiaryContainer = Color(0xFF004E5B),
    onTertiaryContainer = Color(0xFFA5EEFD),
    background = SlateBackground,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceTint = EmeraldPrimary,
    inverseSurface = TextPrimary,
    inverseOnSurface = SlateBackground,
    error = CoralRed,
    onError = Color(0xFF450A0A),
    errorContainer = CoralRedContainer,
    onErrorContainer = Color(0xFFFCA5A5),
    outline = SlateBorder,
    outlineVariant = Color(0xFF1E293B)
)

val FinSightCyberColorScheme = darkColorScheme(
    primary = CyberVioletPrimary,
    onPrimary = Color.White,
    primaryContainer = CyberVioletContainer,
    onPrimaryContainer = CyberVioletBright,
    secondary = CyanAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF164E63),
    onSecondaryContainer = Color(0xFFA5F3FC),
    tertiary = CyberVioletBright,
    onTertiary = Color.Black,
    background = CyberBackground,
    onBackground = TextPrimary,
    surface = CyberSurface,
    onSurface = TextPrimary,
    surfaceVariant = CyberSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceTint = CyberVioletPrimary,
    outline = CyberBorder,
    error = CoralRed,
    onError = Color.White
)

val FinSightMidnightColorScheme = darkColorScheme(
    primary = MidnightBluePrimary,
    onPrimary = Color.White,
    primaryContainer = MidnightBlueContainer,
    onPrimaryContainer = MidnightBlueBright,
    secondary = CyanAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF075985),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = MidnightBlueBright,
    onTertiary = Color.Black,
    background = MidnightBackground,
    onBackground = TextPrimary,
    surface = MidnightSurface,
    onSurface = TextPrimary,
    surfaceVariant = MidnightSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceTint = MidnightBluePrimary,
    outline = MidnightBorder,
    error = CoralRed,
    onError = Color.White
)

private val FinSightLightColorScheme = lightColorScheme(
    primary = EmeraldPrimaryVariant,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F3D0),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = MintSecondary,
    onSecondary = Color.White,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    error = CoralRed,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
    outline = Color(0xFFCBD5E1)
)

fun getFinSightColorScheme(appTheme: AppTheme): ColorScheme = when (appTheme) {
    AppTheme.DECK_EMERALD -> FinSightDarkColorScheme
    AppTheme.CYBER_SLATE -> FinSightCyberColorScheme
    AppTheme.MIDNIGHT_BLUE -> FinSightMidnightColorScheme
}

@Composable
fun FinSightAITheme(
    appTheme: AppTheme = AppTheme.DECK_EMERALD,
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        getFinSightColorScheme(appTheme)
    } else {
        FinSightLightColorScheme
    }

    CompositionLocalProvider(LocalAppTheme provides appTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
