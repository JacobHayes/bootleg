package dev.jacobhayes.bootleg.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette (pre-Android 12): a deep teal seed.
private val lightFallback =
    lightColorScheme(
        primary = Color(0xFF006A60),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF9EF2E4),
        onPrimaryContainer = Color(0xFF00201C),
        secondary = Color(0xFF4A635F),
        tertiary = Color(0xFF456179),
    )

private val darkFallback =
    darkColorScheme(
        primary = Color(0xFF82D5C8),
        onPrimary = Color(0xFF003731),
        primaryContainer = Color(0xFF005048),
        onPrimaryContainer = Color(0xFF9EF2E4),
        secondary = Color(0xFFB1CCC6),
        tertiary = Color(0xFFADCAE6),
    )

@Composable
fun BootlegTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
            darkTheme -> darkFallback
            else -> lightFallback
        }
    MaterialTheme(colorScheme = colors, content = content)
}
