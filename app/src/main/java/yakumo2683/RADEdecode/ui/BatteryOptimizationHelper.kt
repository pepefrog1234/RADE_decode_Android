package yakumo2683.RADEdecode.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yakumo2683.RADEdecode.ui.theme.*

/**
 * First-launch dialog that guides user to disable battery optimization
 * and background restrictions for reliable background decoding.
 */
@Composable
fun BatteryOptimizationDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoring = pm.isIgnoringBatteryOptimizations(context.packageName)
    val brand = Build.MANUFACTURER.lowercase()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BatteryAlert, null, tint = Amber400, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Background Decoding Setup", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "RADE Decode needs to run in the background to continuously receive and decode signals. " +
                    "Some devices restrict background activity which may interrupt decoding.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 20.sp
                )

                // Battery optimization status
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isIgnoring) GreenBright.copy(alpha = 0.1f) else Red400.copy(alpha = 0.1f),
                    modifier = Modifier.border(
                        1.dp,
                        if (isIgnoring) GreenBright.copy(alpha = 0.3f) else Red400.copy(alpha = 0.3f),
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isIgnoring) Icons.Default.CheckCircle else Icons.Default.Warning,
                            null,
                            tint = if (isIgnoring) GreenBright else Red400,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isIgnoring) "Battery optimization is disabled — good!"
                            else "Battery optimization is enabled — may interrupt decoding",
                            fontSize = 13.sp,
                            color = if (isIgnoring) GreenBright else Red400
                        )
                    }
                }

                if (!isIgnoring) {
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan400),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.BatteryChargingFull, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Disable Battery Optimization")
                    }
                }

                // Vendor-specific tips
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                Text(
                    "ADDITIONAL STEPS",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp, color = Cyan400
                )

                val tips = getVendorTips(brand)
                tips.forEach { tip ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("•", color = OnSurfaceDim, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                        Text(tip, fontSize = 13.sp, color = OnSurfaceDim, lineHeight = 18.sp)
                    }
                }

                // Open app settings button
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open App Settings")
                }

                // Warning
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Amber400.copy(alpha = 0.08f),
                    modifier = Modifier.border(1.dp, Amber400.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Icon(Icons.Default.Info, null, tint = Amber400, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Disabling battery optimization will increase power consumption " +
                            "while RADE Decode is running. The app only uses significant power " +
                            "when actively decoding (Start is pressed). You can re-enable " +
                            "optimization anytime in system settings.",
                            fontSize = 12.sp, color = Amber400.copy(alpha = 0.8f), lineHeight = 16.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it", color = Cyan400)
            }
        }
    )
}

private fun getVendorTips(brand: String): List<String> {
    val common = listOf(
        "Ensure \"Allow background activity\" is enabled in App Settings → Battery.",
        "If using \"Battery Saver\" mode, add RADE Decode to the exception list."
    )

    val vendorSpecific = when {
        brand.contains("samsung") -> listOf(
            "Samsung: Settings → Battery → Background usage limits → Never sleeping apps → Add RADE Decode.",
            "Samsung: Disable \"Adaptive battery\" or add app to \"Unmonitored apps\".",
            "Samsung: Settings → Apps → RADE Decode → Battery → Unrestricted."
        )
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> listOf(
            "Xiaomi/POCO: Settings → Apps → RADE Decode → Battery saver → No restrictions.",
            "Xiaomi/POCO: Settings → Battery → App battery saver → RADE Decode → No restrictions.",
            "Xiaomi/POCO: Security app → Battery → App battery saver → RADE Decode → No restrictions.",
            "Xiaomi/POCO: Enable \"Autostart\" for RADE Decode in Security settings."
        )
        brand.contains("huawei") || brand.contains("honor") -> listOf(
            "Huawei/Honor: Settings → Battery → App launch → RADE Decode → Set to \"Manage manually\" and enable all toggles.",
            "Huawei/Honor: Settings → Apps → RADE Decode → Battery → Enable \"Run in background\".",
            "Huawei/Honor: Disable \"Power-intensive prompt\" for this app."
        )
        brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> listOf(
            "OPPO/Realme/OnePlus: Settings → Battery → More settings → Optimize battery use → RADE Decode → Don't optimize.",
            "OnePlus: Settings → Battery → Battery optimization → RADE Decode → Don't optimize.",
            "OPPO/Realme: Enable \"Allow auto-launch\" and \"Allow background activity\"."
        )
        brand.contains("vivo") || brand.contains("iqoo") -> listOf(
            "vivo/iQOO: Settings → Battery → Background power consumption management → RADE Decode → Allow.",
            "vivo/iQOO: i Manager → App manager → Autostart manager → Enable RADE Decode."
        )
        brand.contains("asus") -> listOf(
            "ASUS: Settings → Battery → PowerMaster → Auto-start manager → Enable RADE Decode."
        )
        brand.contains("sony") -> listOf(
            "Sony: Settings → Battery → Adaptive battery → Exceptions → Add RADE Decode."
        )
        else -> emptyList()
    }

    return vendorSpecific + common
}

/** Check if we should show the dialog (first launch or optimization still enabled). */
fun shouldShowBatteryDialog(context: Context): Boolean {
    val prefs = context.getSharedPreferences("rade_prefs", Context.MODE_PRIVATE)
    val dismissed = prefs.getBoolean("battery_dialog_dismissed", false)
    if (dismissed) return false

    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return !pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun markBatteryDialogDismissed(context: Context) {
    context.getSharedPreferences("rade_prefs", Context.MODE_PRIVATE)
        .edit().putBoolean("battery_dialog_dismissed", true).apply()
}
