package yakumo2683.RADEdecode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import yakumo2683.RADEdecode.ui.theme.*

private val modes = listOf("USB", "LSB", "PKTUSB", "PKTLSB", "CW", "CWR", "AM", "FM")

/** Common hamlib rig models — (model_id, display_name) */
private val rigModels = listOf(
    1 to "Dummy (test)",
    2 to "NET rigctl",
    // Icom
    3014 to "Icom IC-7300",
    3073 to "Icom IC-705",
    3070 to "Icom IC-7610",
    3081 to "Icom IC-905",
    3060 to "Icom IC-9700",
    3013 to "Icom IC-7200",
    3024 to "Icom IC-7100",
    3078 to "Icom IC-7851",
    // Yaesu
    1035 to "Yaesu FT-991A",
    1036 to "Yaesu FT-DX10",
    1037 to "Yaesu FT-710",
    1038 to "Yaesu FT-DX101",
    1031 to "Yaesu FT-950",
    1024 to "Yaesu FT-891",
    // Kenwood / Elecraft
    2048 to "Kenwood TS-890S",
    2044 to "Kenwood TS-590SG",
    2042 to "Kenwood TS-480",
    2028 to "Elecraft K3",
    2043 to "Elecraft KX3",
    2045 to "Elecraft K4",
    // FlexRadio
    2036 to "FlexRadio 6xxx",
    // Xiegu
    3079 to "Xiegu G90",
    3080 to "Xiegu X6100",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RigScreen(viewModel: TransceiverViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val rigState by viewModel.rigState.collectAsState()
    val focusManager = LocalFocusManager.current

    // Connection mode: 0 = TCP (remote rigctld), 1 = Serial (local rigctld)
    var connMode by remember { mutableIntStateOf(0) }
    var hostInput by remember { mutableStateOf(rigState.host.ifEmpty { "192.168.1.100" }) }
    var portInput by remember { mutableStateOf(if (rigState.port > 0) rigState.port.toString() else "4532") }
    var freqInput by remember { mutableStateOf("") }
    // Serial mode fields
    var serialDevice by remember { mutableStateOf("/dev/ttyUSB0") }
    var serialSpeed by remember { mutableStateOf("9600") }
    var selectedRigIndex by remember { mutableIntStateOf(0) }  // index into rigModels
    var rigModelExpanded by remember { mutableStateOf(false) }

    // Sync freq display when rig updates
    LaunchedEffect(rigState.freqHz) {
        if (rigState.freqHz > 0) {
            freqInput = rigState.freqHz.toString()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Connection mode selector ──
        SectionLabel("CONNECTION")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("TCP (rigctld)" to 0, "Serial (local)" to 1).forEach { (label, idx) ->
                val selected = connMode == idx
                Surface(
                    onClick = { if (!rigState.connected) connMode = idx },
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            1.5f.dp,
                            if (selected) Cyan400 else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(10.dp)
                        ),
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) Cyan600.copy(alpha = 0.2f) else SurfaceCard,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(vertical = 10.dp),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = if (selected) Cyan400 else OnSurfaceDim
                    )
                }
            }
        }

        // ── Connection settings ──
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (connMode == 0) {
                    // TCP mode
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = hostInput,
                            onValueChange = { hostInput = it },
                            label = { Text("Host") },
                            singleLine = true,
                            enabled = !rigState.connected,
                            modifier = Modifier.weight(2f),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace, fontSize = 14.sp
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400
                            )
                        )
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("Port") },
                            singleLine = true,
                            enabled = !rigState.connected,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace, fontSize = 14.sp
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400
                            )
                        )
                    }
                } else {
                    // Serial mode — rig model dropdown
                    ExposedDropdownMenuBox(
                        expanded = rigModelExpanded,
                        onExpandedChange = { if (!rigState.connected) rigModelExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = rigModels[selectedRigIndex].let { "${it.second} (${it.first})" },
                            onValueChange = {},
                            readOnly = true,
                            enabled = !rigState.connected,
                            label = { Text("Rig Model") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = rigModelExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = rigModelExpanded,
                            onDismissRequest = { rigModelExpanded = false }
                        ) {
                            rigModels.forEachIndexed { index, (id, name) ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(name, fontSize = 14.sp)
                                            Text(
                                                "#$id",
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = OnSurfaceDim
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedRigIndex = index
                                        rigModelExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = serialDevice,
                            onValueChange = { serialDevice = it },
                            label = { Text("Device") },
                            singleLine = true,
                            enabled = !rigState.connected,
                            modifier = Modifier.weight(2f),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400
                            )
                        )
                        OutlinedTextField(
                            value = serialSpeed,
                            onValueChange = { serialSpeed = it.filter { c -> c.isDigit() } },
                            label = { Text("Baud") },
                            singleLine = true,
                            enabled = !rigState.connected,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400
                            )
                        )
                    }
                }

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (rigState.connected) {
                            viewModel.rigDisconnect()
                        } else if (connMode == 0) {
                            val port = portInput.toIntOrNull() ?: 4532
                            viewModel.rigConnect(hostInput, port)
                        } else {
                            val model = rigModels[selectedRigIndex].first
                            val speed = serialSpeed.toIntOrNull() ?: 9600
                            viewModel.rigStartLocal(model, serialDevice, speed)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (rigState.connected) Red400 else Cyan600
                    )
                ) {
                    Icon(
                        if (rigState.connected) Icons.Default.LinkOff else Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (rigState.connected) "DISCONNECT" else "CONNECT",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }

                if (rigState.error.isNotEmpty()) {
                    Text(rigState.error, color = Red400, fontSize = 12.sp)
                }
            }
        }

        // ── Status indicator ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (rigState.connected) GreenBright else Red400)
            )
            Text(
                if (rigState.connected) "Connected to ${rigState.host}:${rigState.port}" else "Not connected",
                fontSize = 12.sp,
                color = if (rigState.connected) GreenBright else OnSurfaceDim,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            if (rigState.ptt) {
                Text(
                    "TX",
                    color = Red400,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp
                )
            }
        }

        // ── Frequency ──
        SectionLabel("FREQUENCY")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Frequency display
                Text(
                    text = formatFreq(rigState.freqHz),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    color = if (rigState.connected) Cyan400 else OnSurfaceDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                // Frequency entry
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = freqInput,
                        onValueChange = { freqInput = it.filter { c -> c.isDigit() } },
                        label = { Text("Freq (Hz)") },
                        singleLine = true,
                        enabled = rigState.connected,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                freqInput.toLongOrNull()?.let { viewModel.rigSetFreq(it) }
                                focusManager.clearFocus()
                            }
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 14.sp
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan400,
                            focusedLabelColor = Cyan400,
                            cursorColor = Cyan400
                        )
                    )
                    Button(
                        onClick = {
                            freqInput.toLongOrNull()?.let { viewModel.rigSetFreq(it) }
                            focusManager.clearFocus()
                        },
                        enabled = rigState.connected,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan600)
                    ) {
                        Text("SET", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Mode ──
        SectionLabel("MODE")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Current mode display
                Text(
                    text = rigState.mode.ifEmpty { "---" },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (rigState.connected) Cyan400 else OnSurfaceDim,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(10.dp))

                // Mode buttons grid (2 rows of 4)
                for (row in modes.chunked(4)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        row.forEach { mode ->
                            val selected = rigState.mode == mode
                            OutlinedButton(
                                onClick = { viewModel.rigSetMode(mode) },
                                enabled = rigState.connected,
                                modifier = Modifier.weight(1f).height(38.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected) Cyan600.copy(alpha = 0.2f) else Color.Transparent,
                                    contentColor = if (selected) Cyan400 else OnSurfaceDim
                                ),
                                border = ButtonDefaults.outlinedButtonBorder(enabled = true).let {
                                    if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, Cyan400) else it
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    mode,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // ── S-Meter ──
        SectionLabel("S-METER")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceCard,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val sUnit = dbToSUnit(rigState.sMeter)
                Text(
                    text = sUnit,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (rigState.connected) GreenBright else OnSurfaceDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${rigState.sMeter} dB",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = OnSurfaceDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        color = Cyan400,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/** Format frequency as "14.250.000" style display */
private fun formatFreq(hz: Long): String {
    if (hz <= 0) return "----.----.---"
    val s = hz.toString().padStart(9, ' ')
    val mhz = s.dropLast(6).trimStart()
    val khz = s.takeLast(6).take(3)
    val sub = s.takeLast(3)
    return "$mhz.$khz.$sub"
}

/** Convert dB relative to S9 to S-unit string */
private fun dbToSUnit(db: Int): String {
    // S9 = 0 dB, each S-unit = 6 dB below S9
    return when {
        db >= 40 -> "S9+${db}dB"
        db >= 0  -> "S9+${db}dB"
        db >= -6 -> "S9"
        db >= -12 -> "S8"
        db >= -18 -> "S7"
        db >= -24 -> "S6"
        db >= -30 -> "S5"
        db >= -36 -> "S4"
        db >= -42 -> "S3"
        db >= -48 -> "S2"
        db >= -54 -> "S1"
        else -> "S0"
    }
}
