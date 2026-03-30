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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yakumo2683.RADEdecode.R
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
                Text(stringResource(R.string.battery_dialog_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.battery_dialog_description),
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
                            if (isIgnoring) stringResource(R.string.battery_opt_disabled)
                            else stringResource(R.string.battery_opt_enabled),
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
                        Text(stringResource(R.string.battery_disable_btn))
                    }
                }

                // Vendor-specific tips
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                Text(
                    stringResource(R.string.battery_additional_steps),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp, color = Cyan400
                )

                val tipResIds = getVendorTipResIds(brand)
                tipResIds.forEach { resId ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("•", color = OnSurfaceDim, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                        Text(stringResource(resId), fontSize = 13.sp, color = OnSurfaceDim, lineHeight = 18.sp)
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
                    Text(stringResource(R.string.battery_open_app_settings))
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
                            stringResource(R.string.battery_warning),
                            fontSize = 12.sp, color = Amber400.copy(alpha = 0.8f), lineHeight = 16.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_got_it), color = Cyan400)
            }
        }
    )
}

private fun getVendorTipResIds(brand: String): List<Int> {
    val common = listOf(
        R.string.battery_tip_allow_background,
        R.string.battery_tip_battery_saver
    )

    val vendorSpecific = when {
        brand.contains("samsung") -> listOf(
            R.string.battery_tip_samsung_1,
            R.string.battery_tip_samsung_2,
            R.string.battery_tip_samsung_3
        )
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> listOf(
            R.string.battery_tip_xiaomi_1,
            R.string.battery_tip_xiaomi_2,
            R.string.battery_tip_xiaomi_3,
            R.string.battery_tip_xiaomi_4
        )
        brand.contains("huawei") || brand.contains("honor") -> listOf(
            R.string.battery_tip_huawei_1,
            R.string.battery_tip_huawei_2,
            R.string.battery_tip_huawei_3
        )
        brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> listOf(
            R.string.battery_tip_oppo_1,
            R.string.battery_tip_oppo_2,
            R.string.battery_tip_oppo_3
        )
        brand.contains("vivo") || brand.contains("iqoo") -> listOf(
            R.string.battery_tip_vivo_1,
            R.string.battery_tip_vivo_2
        )
        brand.contains("asus") -> listOf(
            R.string.battery_tip_asus
        )
        brand.contains("sony") -> listOf(
            R.string.battery_tip_sony
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
