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

data class RigModel(val id: Int, val mfg: String, val name: String) {
    val displayName: String get() = "$mfg $name"
}

/** All hamlib 4.5.5 rig models (348 rigs) */
private val rigModels = listOf(
    // Hamlib
    RigModel(1, "Hamlib", "Dummy"),
    RigModel(2, "Hamlib", "NET rigctl"),
    RigModel(4, "Hamlib", "FLRig"),
    RigModel(7, "Hamlib", "TCI 1.X"),
    // Yaesu
    RigModel(1001, "Yaesu", "FT-847"),
    RigModel(1002, "Yaesu", "FT-1000"),
    RigModel(1003, "Yaesu", "FT-1000D"),
    RigModel(1004, "Yaesu", "FT-1000MP MKV"),
    RigModel(1005, "Yaesu", "FT-747"),
    RigModel(1006, "Yaesu", "FT-757"),
    RigModel(1007, "Yaesu", "FT-757GX II"),
    RigModel(1008, "Yaesu", "FT-575"),
    RigModel(1009, "Yaesu", "FT-767"),
    RigModel(1010, "Yaesu", "FT-736R"),
    RigModel(1011, "Yaesu", "FT-840"),
    RigModel(1012, "Yaesu", "FT-820"),
    RigModel(1013, "Yaesu", "FT-900"),
    RigModel(1014, "Yaesu", "FT-920"),
    RigModel(1015, "Yaesu", "FT-890"),
    RigModel(1016, "Yaesu", "FT-990"),
    RigModel(1017, "Yaesu", "FRG-100"),
    RigModel(1018, "Yaesu", "FRG-9600"),
    RigModel(1019, "Yaesu", "FRG-8800"),
    RigModel(1020, "Yaesu", "FT-817"),
    RigModel(1021, "Yaesu", "FT-100"),
    RigModel(1022, "Yaesu", "FT-857"),
    RigModel(1023, "Yaesu", "FT-897"),
    RigModel(1024, "Yaesu", "FT-1000MP"),
    RigModel(1025, "Yaesu", "FT-1000MP MKV Fld"),
    RigModel(1026, "Yaesu", "VR-5000"),
    RigModel(1027, "Yaesu", "FT-450"),
    RigModel(1028, "Yaesu", "FT-950"),
    RigModel(1029, "Yaesu", "FT-2000"),
    RigModel(1030, "Yaesu", "FT-9000"),
    RigModel(1031, "Yaesu", "FT-980"),
    RigModel(1032, "Yaesu", "FTDX-5000"),
    RigModel(1033, "Yaesu", "VX-1700"),
    RigModel(1034, "Yaesu", "FTDX-1200"),
    RigModel(1035, "Yaesu", "FT-991/A"),
    RigModel(1036, "Yaesu", "FT-891"),
    RigModel(1037, "Yaesu", "FTDX-3000"),
    RigModel(1039, "Yaesu", "FT-600"),
    RigModel(1040, "Yaesu", "FTDX-101D"),
    RigModel(1041, "Yaesu", "FT-818"),
    RigModel(1042, "Yaesu", "FTDX-10"),
    RigModel(1043, "Yaesu", "FT-897D"),
    RigModel(1044, "Yaesu", "FTDX-101MP"),
    RigModel(1046, "Yaesu", "FT-450D"),
    RigModel(1047, "Yaesu", "FT-650"),
    RigModel(1049, "Yaesu", "FT-710"),
    // Kenwood
    RigModel(2001, "Kenwood", "TS-50"),
    RigModel(2002, "Kenwood", "TS-440"),
    RigModel(2003, "Kenwood", "TS-450S"),
    RigModel(2004, "Kenwood", "TS-570D"),
    RigModel(2005, "Kenwood", "TS-690S"),
    RigModel(2006, "Kenwood", "TS-711"),
    RigModel(2007, "Kenwood", "TS-790"),
    RigModel(2009, "Kenwood", "TS-850"),
    RigModel(2010, "Kenwood", "TS-870S"),
    RigModel(2011, "Kenwood", "TS-940"),
    RigModel(2012, "Kenwood", "TS-950S"),
    RigModel(2013, "Kenwood", "TS-950SDX"),
    RigModel(2014, "Kenwood", "TS-2000"),
    RigModel(2015, "Kenwood", "R-5000"),
    RigModel(2016, "Kenwood", "TS-570S"),
    RigModel(2022, "Kenwood", "TS-930"),
    RigModel(2024, "Kenwood", "TS-680S"),
    RigModel(2025, "Kenwood", "TS-140S"),
    RigModel(2028, "Kenwood", "TS-480"),
    RigModel(2031, "Kenwood", "TS-590S"),
    RigModel(2037, "Kenwood", "TS-590SG"),
    RigModel(2039, "Kenwood", "TS-990S"),
    RigModel(2041, "Kenwood", "TS-890S"),
    // Elecraft (Kenwood backend)
    RigModel(2021, "Elecraft", "K2"),
    RigModel(2029, "Elecraft", "K3"),
    RigModel(2043, "Elecraft", "K3S"),
    RigModel(2044, "Elecraft", "KX2"),
    RigModel(2045, "Elecraft", "KX3"),
    RigModel(2047, "Elecraft", "K4"),
    RigModel(2038, "Elecraft", "XG3"),
    // FlexRadio (Kenwood backend)
    RigModel(2036, "FlexRadio", "6xxx/SSDR"),
    RigModel(2048, "FlexRadio", "PowerSDR"),
    // Other Kenwood-protocol
    RigModel(2040, "Apache Labs", "HPSDR"),
    RigModel(2049, "Malahit", "Malachite DSP"),
    RigModel(2050, "Lab599", "TX-500"),
    RigModel(2051, "SDRplay", "SDRuno"),
    RigModel(2052, "QRP Labs", "QMX/QDX"),
    // Icom
    RigModel(3001, "Icom", "IC-1271"),
    RigModel(3003, "Icom", "IC-271"),
    RigModel(3004, "Icom", "IC-275"),
    RigModel(3008, "Icom", "IC-575"),
    RigModel(3009, "Icom", "IC-706"),
    RigModel(3010, "Icom", "IC-706MkII"),
    RigModel(3011, "Icom", "IC-706MkIIG"),
    RigModel(3012, "Icom", "IC-707"),
    RigModel(3013, "Icom", "IC-718"),
    RigModel(3014, "Icom", "IC-725"),
    RigModel(3015, "Icom", "IC-726"),
    RigModel(3016, "Icom", "IC-728"),
    RigModel(3019, "Icom", "IC-735"),
    RigModel(3020, "Icom", "IC-736"),
    RigModel(3023, "Icom", "IC-746"),
    RigModel(3024, "Icom", "IC-751"),
    RigModel(3026, "Icom", "IC-756"),
    RigModel(3027, "Icom", "IC-756PRO"),
    RigModel(3028, "Icom", "IC-761"),
    RigModel(3029, "Icom", "IC-765"),
    RigModel(3030, "Icom", "IC-775"),
    RigModel(3031, "Icom", "IC-781"),
    RigModel(3035, "Icom", "IC-970"),
    RigModel(3039, "Icom", "IC-R75"),
    RigModel(3044, "Icom", "IC-910"),
    RigModel(3046, "Icom", "IC-746PRO"),
    RigModel(3047, "Icom", "IC-756PROII"),
    RigModel(3055, "Icom", "IC-703"),
    RigModel(3056, "Icom", "IC-7800"),
    RigModel(3057, "Icom", "IC-756PROIII"),
    RigModel(3060, "Icom", "IC-7000"),
    RigModel(3061, "Icom", "IC-7200"),
    RigModel(3062, "Icom", "IC-7700"),
    RigModel(3063, "Icom", "IC-7600"),
    RigModel(3067, "Icom", "IC-7410"),
    RigModel(3068, "Icom", "IC-9100"),
    RigModel(3070, "Icom", "IC-7100"),
    RigModel(3073, "Icom", "IC-7300"),
    RigModel(3075, "Icom", "IC-785x"),
    RigModel(3078, "Icom", "IC-7610"),
    RigModel(3081, "Icom", "IC-9700"),
    RigModel(3085, "Icom", "IC-705"),
    // Xiegu (Icom backend)
    RigModel(3087, "Xiegu", "X6100"),
    RigModel(3088, "Xiegu", "G90"),
    RigModel(3089, "Xiegu", "X5105"),
    RigModel(3076, "Xiegu", "X108G"),
    // Icom Marine
    RigModel(30001, "Icom Marine", "IC-M700PRO"),
    RigModel(30002, "Icom Marine", "IC-M802"),
    RigModel(30003, "Icom Marine", "IC-M710"),
    RigModel(30004, "Icom Marine", "IC-M803"),
    // Icom PCR
    RigModel(4001, "Icom", "PCR-1000"),
    RigModel(4002, "Icom", "PCR-100"),
    RigModel(4003, "Icom", "PCR-1500"),
    RigModel(4004, "Icom", "PCR-2500"),
    // AOR
    RigModel(5001, "AOR", "AR-8200"),
    RigModel(5003, "AOR", "AR-7030"),
    RigModel(5004, "AOR", "AR-5000"),
    RigModel(5013, "AOR", "AR-8600"),
    RigModel(5016, "AOR", "SR-2200"),
    // JRC
    RigModel(6001, "JRC", "JST-145"),
    RigModel(6002, "JRC", "JST-245"),
    RigModel(6006, "JRC", "NRD-535"),
    RigModel(6007, "JRC", "NRD-545"),
    // Ten-Tec
    RigModel(16001, "Ten-Tec", "Orion (TT550)"),
    RigModel(16002, "Ten-Tec", "Jupiter (TT538)"),
    RigModel(16003, "Ten-Tec", "RX-320"),
    RigModel(16008, "Ten-Tec", "Orion II (TT565)"),
    RigModel(16011, "Ten-Tec", "Omni VII (TT588)"),
    RigModel(16013, "Ten-Tec", "Eagle (TT599)"),
    // Alinco
    RigModel(17001, "Alinco", "DX-77"),
    RigModel(17002, "Alinco", "DX-SR8"),
    // Drake
    RigModel(9001, "Drake", "R-8"),
    RigModel(9002, "Drake", "R-8A"),
    RigModel(9003, "Drake", "R-8B"),
    // ELAD
    RigModel(33001, "ELAD", "FDM-DUO"),
    // Barrett
    RigModel(32001, "Barrett", "2050"),
    RigModel(32002, "Barrett", "950"),
    RigModel(32003, "Barrett", "4050"),
    // Codan
    RigModel(34001, "Codan", "Envoy"),
    RigModel(34002, "Codan", "NGT"),
    // ADAT
    RigModel(29001, "ADAT", "ADT-200A"),
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
                    // Searchable rig model dropdown
                    var searchQuery by remember { mutableStateOf("") }
                    val filteredModels = remember(searchQuery) {
                        if (searchQuery.isBlank()) rigModels
                        else rigModels.filter {
                            it.displayName.contains(searchQuery, ignoreCase = true) ||
                                it.id.toString().contains(searchQuery)
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = rigModelExpanded,
                        onExpandedChange = { if (!rigState.connected) rigModelExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = if (rigModelExpanded) searchQuery
                                    else rigModels[selectedRigIndex].let { "${it.displayName} (#${it.id})" },
                            onValueChange = { searchQuery = it },
                            enabled = !rigState.connected,
                            label = { Text("Rig Model") },
                            placeholder = { Text("Search...") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = rigModelExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400
                            )
                        )
                        if (filteredModels.isNotEmpty()) {
                            ExposedDropdownMenu(
                                expanded = rigModelExpanded,
                                onDismissRequest = { rigModelExpanded = false; searchQuery = "" }
                            ) {
                                filteredModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(model.displayName, fontSize = 14.sp)
                                                Text(
                                                    "#${model.id}",
                                                    fontSize = 12.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = OnSurfaceDim
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedRigIndex = rigModels.indexOf(model)
                                            rigModelExpanded = false
                                            searchQuery = ""
                                        }
                                    )
                                }
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
                            val model = rigModels[selectedRigIndex].id
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
