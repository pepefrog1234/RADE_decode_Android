package yakumo2683.RADEdecode.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import yakumo2683.RADEdecode.ui.theme.Cyan400
import yakumo2683.RADEdecode.ui.theme.OnSurfaceDim
import yakumo2683.RADEdecode.ui.theme.SurfaceCard

@Composable
fun SettingsScreen(viewModel: TransceiverViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var volume by remember { mutableFloatStateOf(1.0f) }
    var inputGain by remember { mutableFloatStateOf(4.0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader("AUDIO INPUT")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Input Devices", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Cyan400)
                    }
                }

                if (state.devices.isEmpty()) {
                    Text(
                        "No audio input devices found",
                        color = OnSurfaceDim,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    state.devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = device.id == state.selectedDeviceId,
                                onClick = { viewModel.selectDevice(device.id) },
                                colors = RadioButtonDefaults.colors(selectedColor = Cyan400)
                            )
                            if (device.isUsb) {
                                Icon(
                                    Icons.Default.Usb, null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Cyan400
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Column {
                                Text(
                                    device.typeName,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    device.name,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = OnSurfaceDim
                                )
                            }
                        }
                    }
                }
            }
        }

        // Input Gain
        SectionHeader("INPUT GAIN")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Digital Gain", color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        String.format("%.1fx (%.0f dB)", inputGain, 20f * kotlin.math.log10(inputGain)),
                        fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Cyan400
                    )
                }
                Slider(
                    value = inputGain,
                    onValueChange = { inputGain = it; viewModel.setInputGain(it) },
                    valueRange = 1f..30f,
                    colors = SliderDefaults.colors(thumbColor = Cyan400, activeTrackColor = Cyan400)
                )
                Text(
                    "Boost weak USB audio input. Adjust until peak level is around -15 to -5 dBFS.",
                    fontSize = 11.sp, color = OnSurfaceDim
                )
            }
        }

        SectionHeader("RX OUTPUT")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, null, tint = Cyan400, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Volume", color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        "${(volume * 100).toInt()}%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = Cyan400
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = { volume = it; viewModel.setVolume(it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Cyan400,
                        activeTrackColor = Cyan400
                    )
                )
            }
        }

        SectionHeader("TX CALLSIGN")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                var callsignInput by remember { mutableStateOf(state.txCallsign) }
                OutlinedTextField(
                    value = callsignInput,
                    onValueChange = {
                        val upper = it.uppercase().take(8)
                        callsignInput = upper
                        viewModel.setTxCallsign(upper)
                    },
                    label = { Text("Callsign (EOO)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan400,
                        focusedLabelColor = Cyan400,
                        cursorColor = Cyan400
                    )
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your callsign will be encoded in the EOO (End-of-Over) frame during TX.",
                    fontSize = 11.sp, color = OnSurfaceDim
                )
            }
        }

        SectionHeader("TX OUTPUT DEVICE")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Output Devices", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Cyan400)
                    }
                }

                Text(
                    "Select the audio interface for RADE TX modulated output.",
                    fontSize = 11.sp, color = OnSurfaceDim,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (state.outputDevices.isEmpty()) {
                    Text(
                        "No audio output devices found",
                        color = OnSurfaceDim,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    state.outputDevices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = device.id == state.selectedOutputDeviceId,
                                onClick = { viewModel.selectOutputDevice(device.id) },
                                colors = RadioButtonDefaults.colors(selectedColor = Cyan400)
                            )
                            if (device.isUsb) {
                                Icon(
                                    Icons.Default.Usb, null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Cyan400
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Column {
                                Text(
                                    device.typeName,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    device.name,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = OnSurfaceDim
                                )
                            }
                        }
                    }
                }
            }
        }

        SectionHeader("ABOUT")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "RADE Decode",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "HF digital voice SWL receiver. Decodes RADE OFDM signals into " +
                    "speech using on-device FARGAN neural vocoder.",
                    fontSize = 13.sp, color = OnSurfaceDim, lineHeight = 18.sp
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                InfoRow("Modem", "8kHz OFDM, 30 carriers")
                InfoRow("Vocoder", "FARGAN @ 16kHz")
                InfoRow("Project", "FreeDV / Codec2")
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        color = Cyan400,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = OnSurfaceDim)
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}
