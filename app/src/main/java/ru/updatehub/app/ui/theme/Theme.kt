package ru.updatehub.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import ru.updatehub.app.data.Palette
import ru.updatehub.app.data.SeasonalEffect
import ru.updatehub.app.data.ThemeMode

private data class AccentColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color
)

private data class SeasonalTone(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val glassAlpha: Float
)

private fun seasonalAccents(effect: SeasonalEffect): AccentColors? = when (effect) {
    SeasonalEffect.HALLOWEEN -> AccentColors(
        primary = Color(0xFFEA580C),
        secondary = Color(0xFF7C3AED),
        tertiary = Color(0xFFE11D48)
    )

    SeasonalEffect.NEW_YEAR -> AccentColors(
        primary = Color(0xFF2563EB),
        secondary = Color(0xFF0891B2),
        tertiary = Color(0xFF7C3AED)
    )

    SeasonalEffect.AUTUMN -> AccentColors(
        primary = Color(0xFFB45309),
        secondary = Color(0xFF9A3412),
        tertiary = Color(0xFFCA8A04)
    )

    SeasonalEffect.WINTER -> AccentColors(
        primary = Color(0xFF0E7490),
        secondary = Color(0xFF0369A1),
        tertiary = Color(0xFF64748B)
    )

    SeasonalEffect.NONE -> null
}

private fun seasonalTone(
    effect: SeasonalEffect,
    dark: Boolean,
    black: Boolean
): SeasonalTone? = when (effect) {
    SeasonalEffect.NONE -> null

    SeasonalEffect.HALLOWEEN -> if (dark) {
        SeasonalTone(
            background = if (black) Color.Black else Color(0xFF0B070C),
            surface = if (black) Color(0xFF07070A) else Color(0xFF141017),
            surfaceVariant = Color(0xFF241A23),
            onBackground = Color(0xFFF6EBF3),
            onSurface = Color(0xFFF1E6EE),
            onSurfaceVariant = Color(0xFFC3B1BF),
            outline = Color(0xFF8F7A8A),
            outlineVariant = Color(0xFF352733),
            glassAlpha = 1f
        )
    } else {
        SeasonalTone(
            background = Color(0xFFFFF5E8),
            surface = Color(0xFFFFFAF3),
            surfaceVariant = Color(0xFFF6E3D1),
            onBackground = Color(0xFF24150B),
            onSurface = Color(0xFF2A180C),
            onSurfaceVariant = Color(0xFF6B5140),
            outline = Color(0xFF9A7B64),
            outlineVariant = Color(0xFFE8D3C1),
            glassAlpha = 1f
        )
    }

    SeasonalEffect.NEW_YEAR -> if (dark) {
        SeasonalTone(
            background = if (black) Color.Black else Color(0xFF050C18),
            surface = if (black) Color(0xFF07070A) else Color(0xFF0A1526),
            surfaceVariant = Color(0xFF122238),
            onBackground = Color(0xFFEAF5FF),
            onSurface = Color(0xFFE6F2FF),
            onSurfaceVariant = Color(0xFFA9BDD4),
            outline = Color(0xFF718AA3),
            outlineVariant = Color(0xFF22344A),
            glassAlpha = if (black) 1f else 0.82f
        )
    } else {
        SeasonalTone(
            background = Color(0xFFF3F9FF),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE4F1FC),
            onBackground = Color(0xFF0A1B2C),
            onSurface = Color(0xFF0C2033),
            onSurfaceVariant = Color(0xFF51697F),
            outline = Color(0xFF7C97AD),
            outlineVariant = Color(0xFFD3E5F3),
            glassAlpha = 0.84f
        )
    }

    SeasonalEffect.AUTUMN -> if (dark) {
        SeasonalTone(
            background = if (black) Color.Black else Color(0xFF120E09),
            surface = if (black) Color(0xFF07070A) else Color(0xFF1B1510),
            surfaceVariant = Color(0xFF2A2118),
            onBackground = Color(0xFFF7EFE4),
            onSurface = Color(0xFFF3EADF),
            onSurfaceVariant = Color(0xFFC7B8A5),
            outline = Color(0xFF927F69),
            outlineVariant = Color(0xFF3A2F24),
            glassAlpha = 1f
        )
    } else {
        SeasonalTone(
            background = Color(0xFFFFF8EC),
            surface = Color(0xFFFFFCF5),
            surfaceVariant = Color(0xFFF7EAD5),
            onBackground = Color(0xFF24180A),
            onSurface = Color(0xFF2A1C0D),
            onSurfaceVariant = Color(0xFF6E5A42),
            outline = Color(0xFF9C8467),
            outlineVariant = Color(0xFFE8D9C3),
            glassAlpha = 1f
        )
    }

    SeasonalEffect.WINTER -> if (dark) {
        SeasonalTone(
            background = if (black) Color.Black else Color(0xFF071116),
            surface = if (black) Color(0xFF07070A) else Color(0xFF0C1A22),
            surfaceVariant = Color(0xFF142A34),
            onBackground = Color(0xFFE8F5FA),
            onSurface = Color(0xFFE2F1F7),
            onSurfaceVariant = Color(0xFFA6BDC7),
            outline = Color(0xFF6F8B95),
            outlineVariant = Color(0xFF243C46),
            glassAlpha = if (black) 1f else 0.92f
        )
    } else {
        SeasonalTone(
            background = Color(0xFFF2F9FC),
            surface = Color(0xFFFBFDFF),
            surfaceVariant = Color(0xFFE3F0F5),
            onBackground = Color(0xFF0E2028),
            onSurface = Color(0xFF10242D),
            onSurfaceVariant = Color(0xFF516871),
            outline = Color(0xFF7A949E),
            outlineVariant = Color(0xFFD0E3EA),
            glassAlpha = 0.92f
        )
    }
}

private fun accentsFor(palette: Palette, seasonal: SeasonalEffect): AccentColors =
    if (palette == Palette.ADAPTIVE) {
        seasonalAccents(seasonal) ?: AccentColors(
            primary = Color(0xFF5B5BD6),
            secondary = Color(0xFF00A896),
            tertiary = Color(0xFFE86A5E)
        )
    } else {
        accentsFor(palette)
    }

private fun accentsFor(palette: Palette): AccentColors = when (palette) {
    // ADAPTIVE normally uses the system wallpaper scheme. On Android 11 and
    // older the standard accent is used as a graceful fallback.
    Palette.ADAPTIVE -> AccentColors(
        primary = Color(0xFF5B5BD6),
        secondary = Color(0xFF00A896),
        tertiary = Color(0xFFE86A5E)
    )

    Palette.DEFAULT -> AccentColors(
        primary = Color(0xFF5B5BD6),
        secondary = Color(0xFF00A896),
        tertiary = Color(0xFFE86A5E)
    )

    Palette.OCEAN -> AccentColors(
        primary = Color(0xFF0077B6),
        secondary = Color(0xFF0096C7),
        tertiary = Color(0xFF48CAE4)
    )

    Palette.FOREST -> AccentColors(
        primary = Color(0xFF2D6A4F),
        secondary = Color(0xFF40916C),
        tertiary = Color(0xFF74C69D)
    )

    Palette.SUNSET -> AccentColors(
        primary = Color(0xFFD95D39),
        secondary = Color(0xFFF4A261),
        tertiary = Color(0xFFE9C46A)
    )

    Palette.LAVENDER -> AccentColors(
        primary = Color(0xFF7B61FF),
        secondary = Color(0xFFB388FF),
        tertiary = Color(0xFFFF8FAB)
    )

    Palette.ROSE -> AccentColors(
        primary = Color(0xFFC9184A),
        secondary = Color(0xFFFF4D6D),
        tertiary = Color(0xFFFF8FA3)
    )
}

private fun contrastOn(color: Color): Color {
    // Pick whichever foreground actually has the higher contrast ratio. A
    // fixed luminance threshold made white text unreadable on light orange
    // Halloween accents and on other pastel dark-theme accents.
    val luminance = color.luminance()
    val againstWhite = 1.05f / (luminance + 0.05f)
    val againstDark = (luminance + 0.05f) / 0.05f
    return if (againstDark >= againstWhite) Color(0xFF151318) else Color.White
}

private fun glassed(color: Color, alpha: Float): Color =
    if (alpha >= 0.999f) color else color.copy(alpha = alpha)

private fun buildScheme(
    accents: AccentColors,
    dark: Boolean,
    black: Boolean,
    seasonal: SeasonalEffect
): ColorScheme {
    val accent = accents.primary
    val secondary = accents.secondary
    val tertiary = accents.tertiary

    if (!dark) {
        val tone = seasonalTone(seasonal, dark = false, black = false)
        val background = tone?.background ?: lerp(Color(0xFFF8F8FC), accent, 0.025f)
        val surface = tone?.surface ?: lerp(Color.White, accent, 0.012f)
        val surfaceVariant = tone?.surfaceVariant ?: lerp(background, accent, 0.10f)
        val primaryContainer = lerp(accent, Color.White, 0.82f)
        val secondaryContainer = lerp(secondary, Color.White, 0.84f)
        val tertiaryContainer = lerp(tertiary, Color.White, 0.82f)
        val glass = tone?.glassAlpha ?: 1f

        return lightColorScheme(
            primary = accent,
            onPrimary = contrastOn(accent),
            primaryContainer = primaryContainer,
            onPrimaryContainer = lerp(accent, Color.Black, 0.52f),
            inversePrimary = lerp(accent, Color.White, 0.35f),
            secondary = secondary,
            onSecondary = contrastOn(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = lerp(secondary, Color.Black, 0.55f),
            tertiary = tertiary,
            onTertiary = contrastOn(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = lerp(tertiary, Color.Black, 0.55f),
            background = background,
            onBackground = tone?.onBackground ?: Color(0xFF141318),
            surface = surface,
            onSurface = tone?.onSurface ?: Color(0xFF17161C),
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = tone?.onSurfaceVariant ?: Color(0xFF5A5866),
            surfaceTint = accent,
            inverseSurface = Color(0xFF302F37),
            inverseOnSurface = Color(0xFFF4F1F8),
            error = Color(0xFFBA1A1A),
            onError = Color.White,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            outline = tone?.outline ?: Color(0xFF8C8996),
            outlineVariant = tone?.outlineVariant ?: Color(0xFFDCD9E4),
            scrim = Color.Black,
            surfaceBright = Color.White,
            surfaceDim = lerp(background, Color.Black, 0.10f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = glassed(lerp(surface, accent, 0.035f), glass),
            surfaceContainer = glassed(lerp(surface, accent, 0.055f), glass),
            surfaceContainerHigh = glassed(lerp(surface, accent, 0.08f), glass),
            surfaceContainerHighest = glassed(lerp(surface, accent, 0.11f), glass)
        )
    }

    val tone = seasonalTone(seasonal, dark = true, black = black)
    val baseBackground = if (black) Color.Black else Color(0xFF0E0E13)
    val baseSurface = if (black) Color(0xFF07070A) else Color(0xFF15151C)
    val background = tone?.background ?: lerp(
        baseBackground,
        accent,
        if (black) 0.02f else 0.045f
    )
    val surface = tone?.surface ?: lerp(baseSurface, accent, if (black) 0.03f else 0.05f)
    // Seasonal accents keep more of their original saturation. The previous
    // uniform 0.42 white mix turned Halloween orange into a pale peach, while
    // New Year blue became almost grey-blue. Other palettes keep the calmer
    // Material-style lightening so text and icons remain comfortable.
    val primary = when (seasonal) {
        SeasonalEffect.HALLOWEEN -> lerp(accent, Color.White, if (black) 0.20f else 0.10f)
        SeasonalEffect.NEW_YEAR -> lerp(accent, Color.White, if (black) 0.48f else 0.36f)
        SeasonalEffect.AUTUMN -> lerp(accent, Color.White, 0.30f)
        else -> lerp(accent, Color.White, if (black) 0.50f else 0.42f)
    }
    val secondaryLight = when (seasonal) {
        SeasonalEffect.HALLOWEEN -> lerp(secondary, Color.White, 0.22f)
        SeasonalEffect.NEW_YEAR -> lerp(secondary, Color.White, 0.30f)
        else -> lerp(secondary, Color.White, 0.40f)
    }
    val tertiaryLight = when (seasonal) {
        SeasonalEffect.HALLOWEEN -> lerp(tertiary, Color.White, 0.18f)
        else -> lerp(tertiary, Color.White, 0.35f)
    }
    val primaryContainer = lerp(accent, Color.Black, 0.42f)
    val secondaryContainer = lerp(secondary, Color.Black, 0.46f)
    val tertiaryContainer = lerp(tertiary, Color.Black, 0.48f)
    val glass = tone?.glassAlpha ?: 1f

    return darkColorScheme(
        primary = primary,
        onPrimary = contrastOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = lerp(accent, Color.White, 0.82f),
        inversePrimary = accent,
        secondary = secondaryLight,
        onSecondary = contrastOn(secondaryLight),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = lerp(secondary, Color.White, 0.84f),
        tertiary = tertiaryLight,
        onTertiary = contrastOn(tertiaryLight),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = lerp(tertiary, Color.White, 0.82f),
        background = background,
        onBackground = tone?.onBackground ?: Color(0xFFF3F1F8),
        surface = surface,
        onSurface = tone?.onSurface ?: Color(0xFFECEAF2),
        surfaceVariant = tone?.surfaceVariant ?: if (black) {
            Color(0xFF1C1C22)
        } else {
            Color(0xFF2A2933)
        },
        onSurfaceVariant = tone?.onSurfaceVariant ?: Color(0xFFB7B3C2),
        surfaceTint = primary,
        inverseSurface = Color(0xFFECEAF2),
        inverseOnSurface = Color(0xFF1B1A20),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = tone?.outline ?: Color(0xFF8D8999),
        outlineVariant = tone?.outlineVariant ?: if (black) {
            Color(0xFF2D2C35)
        } else {
            Color(0xFF3D3B48)
        },
        scrim = Color.Black,
        surfaceBright = lerp(surface, Color.White, 0.12f),
        surfaceDim = if (black) Color.Black else lerp(surface, Color.Black, 0.22f),
        surfaceContainerLowest = if (black) Color.Black else Color(0xFF0A0A0E),
        surfaceContainerLow = glassed(lerp(surface, accent, 0.035f), glass),
        surfaceContainer = glassed(lerp(surface, accent, 0.055f), glass),
        surfaceContainerHigh = glassed(lerp(surface, accent, 0.075f), glass),
        surfaceContainerHighest = glassed(lerp(surface, accent, 0.095f), glass)
    )
}

@Composable
private fun animatedColor(target: Color, label: String): Color {
    if (LocalReduceMotion.current) return target
    return animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 460),
        label = label
    ).value
}

@Composable
private fun rememberAnimatedScheme(target: ColorScheme): ColorScheme {
    return target.copy(
        primary = animatedColor(target.primary, "primary"),
        onPrimary = animatedColor(target.onPrimary, "onPrimary"),
        primaryContainer = animatedColor(target.primaryContainer, "primaryContainer"),
        onPrimaryContainer = animatedColor(target.onPrimaryContainer, "onPrimaryContainer"),
        inversePrimary = animatedColor(target.inversePrimary, "inversePrimary"),
        secondary = animatedColor(target.secondary, "secondary"),
        onSecondary = animatedColor(target.onSecondary, "onSecondary"),
        secondaryContainer = animatedColor(target.secondaryContainer, "secondaryContainer"),
        onSecondaryContainer = animatedColor(target.onSecondaryContainer, "onSecondaryContainer"),
        tertiary = animatedColor(target.tertiary, "tertiary"),
        onTertiary = animatedColor(target.onTertiary, "onTertiary"),
        tertiaryContainer = animatedColor(target.tertiaryContainer, "tertiaryContainer"),
        onTertiaryContainer = animatedColor(target.onTertiaryContainer, "onTertiaryContainer"),
        background = animatedColor(target.background, "background"),
        onBackground = animatedColor(target.onBackground, "onBackground"),
        surface = animatedColor(target.surface, "surface"),
        onSurface = animatedColor(target.onSurface, "onSurface"),
        surfaceVariant = animatedColor(target.surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = animatedColor(target.onSurfaceVariant, "onSurfaceVariant"),
        surfaceTint = animatedColor(target.surfaceTint, "surfaceTint"),
        inverseSurface = animatedColor(target.inverseSurface, "inverseSurface"),
        inverseOnSurface = animatedColor(target.inverseOnSurface, "inverseOnSurface"),
        error = animatedColor(target.error, "error"),
        onError = animatedColor(target.onError, "onError"),
        errorContainer = animatedColor(target.errorContainer, "errorContainer"),
        onErrorContainer = animatedColor(target.onErrorContainer, "onErrorContainer"),
        outline = animatedColor(target.outline, "outline"),
        outlineVariant = animatedColor(target.outlineVariant, "outlineVariant"),
        surfaceBright = animatedColor(target.surfaceBright, "surfaceBright"),
        surfaceDim = animatedColor(target.surfaceDim, "surfaceDim"),
        surfaceContainerLowest = animatedColor(target.surfaceContainerLowest, "surfaceContainerLowest"),
        surfaceContainerLow = animatedColor(target.surfaceContainerLow, "surfaceContainerLow"),
        surfaceContainer = animatedColor(target.surfaceContainer, "surfaceContainer"),
        surfaceContainerHigh = animatedColor(target.surfaceContainerHigh, "surfaceContainerHigh"),
        surfaceContainerHighest = animatedColor(target.surfaceContainerHighest, "surfaceContainerHighest")
    )
}

/** Keeps the wallpaper accents but makes the background truly AMOLED black. */
private fun withBlackBackground(scheme: ColorScheme): ColorScheme = scheme.copy(
    background = Color.Black,
    surface = Color(0xFF07070A),
    surfaceVariant = Color(0xFF1C1C22),
    surfaceDim = Color.Black,
    surfaceBright = lerp(scheme.surface, Color.White, 0.12f),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = lerp(Color(0xFF07070A), scheme.primary, 0.03f),
    surfaceContainer = lerp(Color(0xFF07070A), scheme.primary, 0.05f),
    surfaceContainerHigh = lerp(Color(0xFF07070A), scheme.primary, 0.07f),
    surfaceContainerHighest = lerp(Color(0xFF07070A), scheme.primary, 0.09f),
    outlineVariant = Color(0xFF2D2C35)
)

@Composable
fun UpdateHubTheme(
    mode: ThemeMode,
    palette: Palette,
    seasonal: SeasonalEffect = SeasonalEffect.NONE,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }
    // Adaptive mode follows the wallpaper when no seasonal effect is active.
    // During a season it switches to a matching palette instead, so Halloween
    // is warm orange and graphite, New Year is icy blue and snow-white, and so
    // on. Fixed palettes always keep the accent the user explicitly chose.
    val activeSeasonal = if (palette == Palette.ADAPTIVE) {
        seasonal
    } else {
        SeasonalEffect.NONE
    }
    val useDynamic = palette == Palette.ADAPTIVE &&
        activeSeasonal == SeasonalEffect.NONE &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val targetScheme = when {
        useDynamic && dark -> dynamicDarkColorScheme(context).let {
            if (mode == ThemeMode.BLACK) withBlackBackground(it) else it
        }

        useDynamic -> dynamicLightColorScheme(context)
        else -> buildScheme(
            accents = accentsFor(palette, activeSeasonal),
            dark = dark,
            black = mode == ThemeMode.BLACK,
            seasonal = activeSeasonal
        )
    }
    val scheme = rememberAnimatedScheme(targetScheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = UpdateHubTypography,
        content = content
    )
}
