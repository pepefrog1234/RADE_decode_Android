package yakumo2683.RADEdecode.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/* ── Palette ────────────────────────────────────────────────── */

val Cyan400       = Color(0xFF26C6DA)
val Cyan600       = Color(0xFF00ACC1)
val Cyan800       = Color(0xFF00838F)
val Green400      = Color(0xFF66BB6A)
val GreenBright   = Color(0xFF00E676)
val Amber400      = Color(0xFFFFCA28)
val Red400        = Color(0xFFEF5350)

val Surface0      = Color(0xFF0F1318)
val Surface1      = Color(0xFF161B22)
val Surface2      = Color(0xFF1C2129)
val Surface3      = Color(0xFF252B35)
val SurfaceCard   = Color(0xFF1A1F28)
val OnSurfaceDim  = Color(0xFF8B949E)

private val DarkScheme = darkColorScheme(
    primary            = Cyan400,
    onPrimary          = Color.Black,
    primaryContainer   = Cyan800,
    onPrimaryContainer = Color.White,
    secondary          = Green400,
    tertiary           = Amber400,
    background         = Surface0,
    surface            = Surface1,
    surfaceVariant     = Surface2,
    surfaceContainer   = SurfaceCard,
    onBackground       = Color(0xFFE6EDF3),
    onSurface          = Color(0xFFE6EDF3),
    onSurfaceVariant   = OnSurfaceDim,
    error              = Red400,
    outline            = Color(0xFF30363D),
    outlineVariant     = Color(0xFF21262D),
)

private val LightScheme = lightColorScheme(
    primary          = Cyan600,
    primaryContainer = Color(0xFFB2EBF2),
    secondary        = Color(0xFF388E3C),
    background       = Color(0xFFF5F5F5),
    surface          = Color.White,
)

@Composable
fun RADEDecodeTheme(
    darkTheme: Boolean = true, // default dark for radio apps
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography(),
        content = content
    )
}
