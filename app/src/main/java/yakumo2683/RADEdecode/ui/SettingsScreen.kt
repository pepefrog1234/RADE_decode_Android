package yakumo2683.RADEdecode.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import yakumo2683.RADEdecode.AudioBridge
import yakumo2683.RADEdecode.R
import yakumo2683.RADEdecode.service.AudioService
import yakumo2683.RADEdecode.ui.theme.Cyan400
import yakumo2683.RADEdecode.ui.theme.Cyan600
import yakumo2683.RADEdecode.ui.theme.OnSurfaceDim
import yakumo2683.RADEdecode.ui.theme.SurfaceCard

@Composable
fun SettingsScreen(viewModel: TransceiverViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var volume by remember { mutableFloatStateOf(viewModel.getSavedVolume()) }
    var inputGain by remember { mutableFloatStateOf(viewModel.getSavedInputGain()) }
    var txMicGain by remember { mutableFloatStateOf(viewModel.getSavedTxMicGain()) }
    var txVolume by remember { mutableFloatStateOf(viewModel.getSavedTxVolume()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(stringResource(R.string.header_audio_devices))

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
                    Text(stringResource(R.string.settings_input_devices), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.settings_refresh), tint = Cyan400)
                    }
                }

                if (state.devices.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_no_input_devices),
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

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Text(stringResource(R.string.header_tx_output_device), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)

                Text(
                    stringResource(R.string.settings_tx_output_help),
                    fontSize = 11.sp, color = OnSurfaceDim,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                if (state.outputDevices.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_no_output_devices),
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

        // Input Gain
        SectionHeader(stringResource(R.string.header_input_gain))

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
                    Text(stringResource(R.string.settings_digital_gain), color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        String.format("%.1fx (%.0f dB)", inputGain, 20f * kotlin.math.log10(inputGain)),
                        fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Cyan400
                    )
                }
                Slider(
                    value = inputGain,
                    onValueChange = { inputGain = it; viewModel.setInputGain(it) },
                    // Floor below unity (down to 0.1x = -20 dB) so a hot source —
                    // e.g. the IC-705's near-full-scale Wi-Fi audio — can be
                    // attenuated manually on top of the fixed network-RX trim.
                    valueRange = 0.1f..30f,
                    colors = SliderDefaults.colors(thumbColor = Cyan400, activeTrackColor = Cyan400)
                )
                Text(
                    stringResource(R.string.settings_gain_help),
                    fontSize = 11.sp, color = OnSurfaceDim
                )
            }
        }

        SectionHeader(stringResource(R.string.header_rx_output))

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
                        Text(stringResource(R.string.settings_volume), color = MaterialTheme.colorScheme.onSurface)
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
                Text(
                    stringResource(R.string.settings_volume_help),
                    fontSize = 11.sp, color = OnSurfaceDim, lineHeight = 16.sp
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Text(
                    stringResource(R.string.settings_rx_output_device),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                RxOutputOptionRow(
                    selected = state.selectedRxOutputDeviceId == AudioService.RX_OUTPUT_AUTO,
                    onClick = { viewModel.selectRxOutputDevice(AudioService.RX_OUTPUT_AUTO) },
                    title = stringResource(R.string.settings_rx_output_auto),
                    subtitle = stringResource(R.string.settings_rx_output_auto_help)
                )
                RxOutputOptionRow(
                    selected = state.selectedRxOutputDeviceId == AudioService.RX_OUTPUT_SYSTEM_DEFAULT,
                    onClick = { viewModel.selectRxOutputDevice(AudioService.RX_OUTPUT_SYSTEM_DEFAULT) },
                    title = stringResource(R.string.settings_rx_output_system),
                    subtitle = stringResource(R.string.settings_rx_output_system_help)
                )

                // Bluetooth SCO is the call-audio (mic) profile; USAGE_MEDIA playback
                // to it is silent without the communication route, so only A2DP is
                // offered here. The user's headset usually exposes both rows under the
                // same name, which made selecting the wrong one easy.
                state.outputDevices.filter { !it.isBluetoothSco }.forEach { device ->
                    AudioDeviceOptionRow(
                        device = device,
                        selected = device.id == state.selectedRxOutputDeviceId,
                        onClick = { viewModel.selectRxOutputDevice(device.id) }
                    )
                }

                Spacer(Modifier.height(8.dp))

                val rxRouteTesting by viewModel.rxRouteTesting.collectAsState()
                val rxRouteTest by viewModel.rxRouteTest.collectAsState()
                OutlinedButton(
                    onClick = { viewModel.testRxOutput() },
                    enabled = !rxRouteTesting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (rxRouteTesting) stringResource(R.string.settings_rx_output_testing)
                        else stringResource(R.string.settings_rx_output_test)
                    )
                }
                Text(
                    stringResource(R.string.settings_rx_output_test_help),
                    fontSize = 11.sp, color = OnSurfaceDim, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                rxRouteTest?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        SectionHeader(stringResource(R.string.header_tx_output))

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
                    Text(
                        stringResource(R.string.settings_tx_level),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${(txVolume * 100).toInt()}%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = Cyan400
                    )
                }
                Slider(
                    value = txVolume,
                    onValueChange = { txVolume = it; viewModel.setTxVolume(it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Cyan400,
                        activeTrackColor = Cyan400
                    )
                )
                Text(
                    stringResource(R.string.settings_tx_level_help),
                    fontSize = 11.sp, color = OnSurfaceDim, lineHeight = 16.sp
                )
            }
        }

        SectionHeader(stringResource(R.string.header_tx_callsign))

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
                    label = { Text(stringResource(R.string.settings_callsign_label)) },
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
                    stringResource(R.string.settings_callsign_help),
                    fontSize = 11.sp, color = OnSurfaceDim
                )
            }
        }

        // ── PTT ──
        SectionHeader(stringResource(R.string.header_ptt))

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
                    Text(
                        stringResource(R.string.settings_ptt_hold),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = state.pttHoldMode,
                        onCheckedChange = { viewModel.setPttHoldMode(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Cyan400)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_ptt_hold_help),
                    fontSize = 11.sp, color = OnSurfaceDim, lineHeight = 16.sp
                )
            }
        }

        SectionHeader(stringResource(R.string.header_tx_mic))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.settings_tx_mic_help),
                    fontSize = 11.sp, color = OnSurfaceDim, lineHeight = 16.sp
                )
                RxOutputOptionRow(
                    selected = state.txMicDeviceId <= 0,
                    onClick = { viewModel.selectTxMicDevice(-1) },
                    title = stringResource(R.string.settings_tx_mic_builtin),
                    subtitle = stringResource(R.string.settings_tx_mic_builtin_help)
                )
                // External (USB-hub / wired) mics. Bluetooth mics are handled by the
                // experimental toggle below, so they're excluded here.
                state.devices.filter { it.isUsb || it.isWired }.forEach { device ->
                    AudioDeviceOptionRow(
                        device = device,
                        selected = device.id == state.txMicDeviceId,
                        onClick = { viewModel.selectTxMicDevice(device.id) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.settings_tx_mic_gain), color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        String.format("%.1fx (%.0f dB)", txMicGain, 20f * kotlin.math.log10(txMicGain)),
                        fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Cyan400
                    )
                }
                Slider(
                    value = txMicGain,
                    onValueChange = { txMicGain = it; viewModel.setTxMicGain(it) },
                    valueRange = 0.5f..15f,
                    colors = SliderDefaults.colors(thumbColor = Cyan400, activeTrackColor = Cyan400)
                )
                Text(
                    stringResource(R.string.settings_tx_mic_gain_help),
                    fontSize = 11.sp, color = OnSurfaceDim
                )
            }
        }

        SectionHeader(stringResource(R.string.header_bt_mic))

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
                    Text(
                        stringResource(R.string.settings_bt_mic_tx),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = state.bluetoothMicTx,
                        onCheckedChange = { viewModel.setBluetoothMicTx(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Cyan400)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_bt_mic_tx_help),
                    fontSize = 11.sp, color = OnSurfaceDim, lineHeight = 16.sp
                )
            }
        }

        // ── FreeDV Reporter ──
        SectionHeader("FREEDV REPORTER")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val reporterConfig = viewModel.reporter.config
                val reporterEnabled by viewModel.reporterEnabledPref.collectAsState()
                var gridInput by remember { mutableStateOf(reporterConfig.gridSquare) }
                var messageInput by remember { mutableStateOf(viewModel.getSavedReporterMessage()) }
                val locationGrid by viewModel.locationTracker.state.collectAsState()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable Reporter", fontWeight = FontWeight.Bold)
                    Switch(
                        checked = reporterEnabled,
                        onCheckedChange = { viewModel.setReporterEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Cyan400)
                    )
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = gridInput,
                    onValueChange = {
                        val upper = it.uppercase().take(6)
                        gridInput = upper
                        viewModel.setReporterGrid(upper)
                    },
                    label = { Text("Grid Square") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan400,
                        focusedLabelColor = Cyan400,
                        cursorColor = Cyan400
                    )
                )

                if (locationGrid.gridSquare.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "GPS: ${locationGrid.gridSquare}",
                        fontSize = 11.sp, color = Cyan400
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = {
                            val capped = it.take(80)
                            messageInput = capped
                            viewModel.setReporterMessageDraft(capped)
                        },
                        label = { Text(stringResource(R.string.settings_reporter_message)) },
                        placeholder = { Text(stringResource(R.string.settings_reporter_message_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan400,
                            focusedLabelColor = Cyan400,
                            cursorColor = Cyan400
                        )
                    )
                    Button(
                        onClick = { viewModel.updateReporterMessage(messageInput) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan600)
                    ) {
                        Text(
                            stringResource(R.string.settings_reporter_message_send),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_reporter_message_help),
                    fontSize = 11.sp, color = OnSurfaceDim, lineHeight = 16.sp
                )
            }
        }

        // ── Performance (效能節約模式) ──
        SectionHeader(stringResource(R.string.header_performance))

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
                    Text(
                        stringResource(R.string.settings_power_save_mode),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = state.powerSaveMode,
                        onCheckedChange = { viewModel.setPowerSaveMode(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Cyan400)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_power_save_mode_help),
                    fontSize = 11.sp, color = OnSurfaceDim, lineHeight = 16.sp
                )
            }
        }

        // ── RADE Mode (V2 experimental) ──
        SectionHeader(stringResource(R.string.header_rade_mode))

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
                    Text(
                        stringResource(R.string.settings_rade_v2),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = state.radeV2Mode,
                        onCheckedChange = { viewModel.setRadeV2Mode(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Cyan400)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_rade_v2_help),
                    fontSize = 11.sp, color = OnSurfaceDim, lineHeight = 16.sp
                )
            }
        }

        SectionHeader(stringResource(R.string.header_diagnostics))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.settings_diag_help),
                    fontSize = 11.sp, color = OnSurfaceDim, lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))

                val clipboard = LocalClipboardManager.current
                val diagScope = rememberCoroutineScope()
                var catLog by remember { mutableStateOf("") }
                var capturing by remember { mutableStateOf(false) }
                var copied by remember { mutableStateOf(false) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            capturing = true
                            copied = false
                            diagScope.launch {
                                val log = withContext(Dispatchers.IO) { viewModel.captureCatLog() }
                                catLog = log
                                capturing = false
                            }
                        },
                        enabled = !capturing,
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan600)
                    ) {
                        Text(
                            if (capturing) stringResource(R.string.settings_diag_capturing)
                            else stringResource(R.string.settings_diag_capture)
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(catLog))
                            copied = true
                        },
                        enabled = catLog.isNotEmpty()
                    ) {
                        Text(
                            if (copied) stringResource(R.string.settings_diag_copied)
                            else stringResource(R.string.settings_diag_copy)
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            diagScope.launch {
                                withContext(Dispatchers.IO) { viewModel.clearCatLog() }
                                catLog = ""
                                copied = false
                            }
                        }
                    ) {
                        Text(stringResource(R.string.settings_diag_clear))
                    }
                }

                Spacer(Modifier.height(8.dp))
                // Audio / LE Audio (LC3) routing log — for diagnosing Bluetooth
                // output switching on TX. Reproduce one TX->RX, then capture+copy.
                Button(
                    onClick = {
                        capturing = true
                        copied = false
                        diagScope.launch {
                            val log = withContext(Dispatchers.IO) { viewModel.captureAudioLog() }
                            catLog = log
                            capturing = false
                        }
                    },
                    enabled = !capturing,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan600)
                ) {
                    Text(
                        if (capturing) stringResource(R.string.settings_diag_capturing)
                        else stringResource(R.string.settings_diag_capture_audio)
                    )
                }

                if (catLog.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            catLog,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .heightIn(max = 280.dp)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp)
                        )
                    }
                }
            }
        }

        SectionHeader(stringResource(R.string.header_about))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.about_app_name),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.about_description),
                    fontSize = 13.sp, color = OnSurfaceDim, lineHeight = 18.sp
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                InfoRow(stringResource(R.string.about_modem), stringResource(R.string.about_modem_value))
                InfoRow(stringResource(R.string.about_vocoder), stringResource(R.string.about_vocoder_value))
                InfoRow(stringResource(R.string.about_project), stringResource(R.string.about_project_value))
            }
        }

        SectionHeader(stringResource(R.string.header_license))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.license_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.license_description),
                    fontSize = 12.sp, color = OnSurfaceDim, lineHeight = 16.sp
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text(
                    stringResource(R.string.license_third_party),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp)
                )
                LicenseRow("RADE Modem", "David Rowe", "BSD 2-Clause")
                LicenseRow("Opus / FARGAN Vocoder", "Xiph.Org", "BSD 3-Clause")
                LicenseRow("EOO Callsign Codec", "Codec2 / FreeDV", "LGPL-2.1")
                LicenseRow("kiss_fft", "Mark Borgerding", "BSD 3-Clause")
                LicenseRow("Hamlib (rigctld)", "The Hamlib Group", "LGPL-2.1+")
                LicenseRow("Oboe Audio", "Google", "Apache 2.0")
                LicenseRow("OkHttp", "Square", "Apache 2.0")
                LicenseRow("Jetpack Compose", "Google", "Apache 2.0")
                LicenseRow("AndroidX Libraries", "Google", "Apache 2.0")
                LicenseRow("Play Services Location", "Google", "Proprietary")
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                val uriHandler = LocalUriHandler.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri("https://megabits.xyz") }
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        stringResource(R.string.license_icon_design),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "megabits.xyz",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Cyan400
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
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

@Composable
private fun RxOutputOptionRow(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Cyan400)
        )
        Column {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                color = OnSurfaceDim
            )
        }
    }
}

@Composable
private fun AudioDeviceOptionRow(
    device: AudioBridge.AudioDevice,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
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

@Composable
private fun LicenseRow(name: String, author: String, license: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(author, fontSize = 10.sp, color = OnSurfaceDim)
        }
        Text(
            license,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Cyan400
        )
    }
}
