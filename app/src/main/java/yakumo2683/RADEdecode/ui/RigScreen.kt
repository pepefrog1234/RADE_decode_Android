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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import yakumo2683.RADEdecode.R
import yakumo2683.RADEdecode.usb.UsbSerialManager
import yakumo2683.RADEdecode.ui.theme.*


data class RigModel(val id: Int, val mfg: String, val name: String) {
    val displayName: String get() = "$mfg $name"
}

private const val TCP_PROFILE_GENERIC = "generic"
private const val TCP_PROFILE_HERMES_LITE2 = "hermes_lite2"

// Which CAT dialect the HL2 SDR app serves. Thetis and piHPSDR expose
// Kenwood-style CAT over TCP — NOT the rigctld protocol — so the app must
// bridge through the bundled rigctld with a "host:port" network rig device.
// SparkSDR/Quisk do speak the rigctld protocol (hamlib NET rigctl, model 2).
private const val HL2_BACKEND_THETIS = "thetis"        // hamlib 2048 PowerSDR/Thetis
private const val HL2_BACKEND_PIHPSDR = "pihpsdr"      // hamlib 2040 OpenHPSDR/PiHPSDR
private const val HL2_BACKEND_NETRIGCTL = "netrigctl"  // hamlib 2 NET rigctl

private fun hl2BackendModel(backend: String): Int = when (backend) {
    HL2_BACKEND_PIHPSDR -> 2040
    HL2_BACKEND_NETRIGCTL -> 2
    else -> 2048
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
    // FlexRadio / OpenHPSDR / SDR app CAT servers
    RigModel(2036, "FlexRadio", "6xxx/SSDR"),
    RigModel(2048, "FlexRadio/Apache", "PowerSDR/Thetis"),
    RigModel(2040, "OpenHPSDR", "PiHPSDR"),
    // Other Kenwood-protocol
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
    val context = LocalContext.current
    val rigPrefs = remember { context.getSharedPreferences("rig_prefs", Context.MODE_PRIVATE) }

    val state by viewModel.uiState.collectAsState()
    val rigState by viewModel.rigState.collectAsState()
    val usbState by viewModel.usbSerialState.collectAsState()
    val icomState by viewModel.icomNetworkState.collectAsState()
    val hl2State by viewModel.hermesState.collectAsState()
    val vbanState by viewModel.vbanState.collectAsState()
    val connecting by viewModel.rigConnecting.collectAsState()
    val focusManager = LocalFocusManager.current

    // Connection mode: 0 = TCP (remote rigctld), 1 = Serial (local rigctld)
    var connMode by remember { mutableIntStateOf(rigPrefs.getInt("conn_mode", 0)) }
    var hostInput by remember { mutableStateOf(rigPrefs.getString("host", "192.168.1.100") ?: "192.168.1.100") }
    var portInput by remember { mutableStateOf(rigPrefs.getString("port", "4532") ?: "4532") }
    var freqInput by remember { mutableStateOf("") }
    var tcpProfile by remember {
        mutableStateOf(rigPrefs.getString("tcp_profile", TCP_PROFILE_GENERIC) ?: TCP_PROFILE_GENERIC)
    }
    var hl2Backend by remember {
        mutableStateOf(rigPrefs.getString("hl2_backend", HL2_BACKEND_THETIS) ?: HL2_BACKEND_THETIS)
    }
    // Network (Icom RS-BA1 / IC-705 Wi-Fi) mode fields
    var icomPortInput by remember { mutableStateOf(rigPrefs.getString("icom_port", "50001") ?: "50001") }
    var icomUser by remember { mutableStateOf(rigPrefs.getString("icom_user", "") ?: "") }
    var icomPass by remember { mutableStateOf(rigPrefs.getString("icom_pass", "") ?: "") }
    // Hermes-Lite 2 direct (openHPSDR protocol 1) mode fields
    var hl2HostInput by remember { mutableStateOf(rigPrefs.getString("hl2_host", "") ?: "") }
    var hl2Drive by remember { mutableFloatStateOf(viewModel.getSavedHl2Drive().toFloat()) }
    var hl2Lna by remember { mutableFloatStateOf(viewModel.getSavedHl2LnaDb().toFloat()) }
    var hl2Pa by remember { mutableStateOf(viewModel.getSavedHl2PaEnabled()) }
    // Thetis (VBAN) network-audio mode fields
    var vbanHostInput by remember { mutableStateOf(rigPrefs.getString("vban_host", "") ?: "") }
    var vbanPortInput by remember { mutableStateOf(rigPrefs.getString("vban_port", "6980") ?: "6980") }
    var vbanCatPortInput by remember { mutableStateOf(rigPrefs.getString("vban_cat_port", "13013") ?: "13013") }
    // Serial mode fields
    var serialSpeed by remember { mutableStateOf(rigPrefs.getString("baud", "19200") ?: "19200") }
    var selectedRigIndex by remember { mutableIntStateOf(rigPrefs.getInt("rig_index", 0).coerceIn(0, rigModels.size - 1)) }
    var rigModelExpanded by remember { mutableStateOf(false) }
    // USB device selection
    var selectedUsbDeviceIndex by remember { mutableIntStateOf(0) }
    var usbDeviceExpanded by remember { mutableStateOf(false) }
    // CI-V address (for Icom rigs)
    var civAddrInput by remember { mutableStateOf(rigPrefs.getString("civ_addr", "") ?: "") }
    // DTR / RTS modem line state. Defaults: DTR on (most CAT interfaces need it),
    // RTS off (some USB-serial cables wire RTS to PTT — asserting it would key the rig).
    var dtrEnabled by remember { mutableStateOf(rigPrefs.getBoolean("dtr_enabled", true)) }
    var rtsEnabled by remember { mutableStateOf(rigPrefs.getBoolean("rts_enabled", false)) }
    // Manufacturer filter
    val manufacturers = remember { listOf("All") + rigModels.map { it.mfg }.distinct() }
    var selectedMfg by remember { mutableStateOf(rigPrefs.getString("mfg_filter", "All") ?: "All") }
    var mfgExpanded by remember { mutableStateOf(false) }

    val anyRigConnected = rigState.connected || hl2State.connected || vbanState.connected

    // Sync freq display when rig updates (show as kHz)
    LaunchedEffect(rigState.freqHz, hl2State.freqHz, hl2State.connected) {
        val hz = if (hl2State.connected) hl2State.freqHz else rigState.freqHz
        if (hz > 0) {
            val khz = hz / 1000.0
            freqInput = if (khz == khz.toLong().toDouble()) khz.toLong().toString()
                        else String.format("%.1f", khz)
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
        SectionLabel(stringResource(R.string.header_connection))

        listOf(
            stringResource(R.string.rig_tcp_mode) to 0,
            stringResource(R.string.rig_serial_mode) to 1,
            stringResource(R.string.rig_network_mode) to 2,
            stringResource(R.string.rig_hl2_network_mode) to 3,
            stringResource(R.string.rig_vban_mode) to 4
        ).chunked(2).forEach { rowModes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowModes.forEach { (label, idx) ->
                    val selected = connMode == idx
                    Surface(
                        onClick = { if (!anyRigConnected) connMode = idx },
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            stringResource(R.string.rig_tcp_profile_generic) to TCP_PROFILE_GENERIC,
                            stringResource(R.string.rig_tcp_profile_hl2) to TCP_PROFILE_HERMES_LITE2
                        ).forEach { (label, profile) ->
                            val selected = tcpProfile == profile
                            Surface(
                                onClick = { if (!rigState.connected) tcpProfile = profile },
                                modifier = Modifier
                                    .weight(1f)
                                    .border(
                                        1.dp,
                                        if (selected) Cyan400 else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(8.dp)
                                    ),
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) Cyan600.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = if (selected) Cyan400 else OnSurfaceDim
                                )
                            }
                        }
                    }
                    if (tcpProfile == TCP_PROFILE_HERMES_LITE2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "Thetis" to HL2_BACKEND_THETIS,
                                "piHPSDR" to HL2_BACKEND_PIHPSDR,
                                "Spark/Quisk" to HL2_BACKEND_NETRIGCTL
                            ).forEach { (label, backend) ->
                                val selected = hl2Backend == backend
                                Surface(
                                    onClick = { if (!rigState.connected) hl2Backend = backend },
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(
                                            1.dp,
                                            if (selected) Cyan400 else MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(8.dp)
                                        ),
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) Cyan600.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface
                                ) {
                                    Text(
                                        text = label,
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = if (selected) Cyan400 else OnSurfaceDim
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.rig_tcp_hl2_hint),
                            color = OnSurfaceDim,
                            fontSize = 11.sp
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = hostInput,
                            onValueChange = { hostInput = it },
                            label = { Text(stringResource(R.string.rig_host)) },
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
                            label = { Text(stringResource(R.string.rig_port)) },
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
                } else if (connMode == 2) {
                    // Network (Icom RS-BA1) mode — IC-705 over Wi-Fi
                    Text(
                        stringResource(R.string.rig_network_hint),
                        color = OnSurfaceDim,
                        fontSize = 11.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = hostInput,
                            onValueChange = { hostInput = it },
                            label = { Text(stringResource(R.string.rig_host)) },
                            singleLine = true,
                            enabled = !rigState.connected,
                            modifier = Modifier.weight(2f),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400)
                        )
                        OutlinedTextField(
                            value = icomPortInput,
                            onValueChange = { icomPortInput = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.rig_port)) },
                            singleLine = true,
                            enabled = !rigState.connected,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400)
                        )
                    }
                    OutlinedTextField(
                        value = icomUser,
                        onValueChange = { icomUser = it },
                        label = { Text(stringResource(R.string.rig_username)) },
                        singleLine = true,
                        enabled = !rigState.connected,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400)
                    )
                    OutlinedTextField(
                        value = icomPass,
                        onValueChange = { icomPass = it },
                        label = { Text(stringResource(R.string.rig_password)) },
                        singleLine = true,
                        enabled = !rigState.connected,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400)
                    )
                } else if (connMode == 3) {
                    // Hermes-Lite 2 direct — openHPSDR protocol 1 on the LAN
                    Text(
                        stringResource(R.string.rig_hl2_network_hint),
                        color = OnSurfaceDim,
                        fontSize = 11.sp
                    )
                    OutlinedTextField(
                        value = hl2HostInput,
                        onValueChange = { hl2HostInput = it },
                        label = { Text(stringResource(R.string.rig_host)) },
                        placeholder = { Text(stringResource(R.string.rig_hl2_host_auto)) },
                        singleLine = true,
                        enabled = !hl2State.connected,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400)
                    )
                    if (hl2State.connected && hl2State.deviceName.isNotEmpty()) {
                        Text(
                            hl2State.deviceName,
                            color = GreenBright,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.rig_hl2_pa),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                stringResource(R.string.rig_hl2_pa_hint),
                                color = OnSurfaceDim,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = hl2Pa,
                            onCheckedChange = { hl2Pa = it; viewModel.hl2SetPaEnabled(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan600)
                        )
                    }
                    Text(
                        stringResource(R.string.rig_hl2_drive) + ": ${hl2Drive.toInt()} / 255",
                        color = OnSurfaceDim,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Slider(
                        value = hl2Drive,
                        onValueChange = { hl2Drive = it; viewModel.hl2SetDrive(it.toInt()) },
                        valueRange = 0f..255f,
                        colors = SliderDefaults.colors(thumbColor = Cyan400, activeTrackColor = Cyan600)
                    )
                    Text(
                        stringResource(R.string.rig_hl2_lna) + ": ${hl2Lna.toInt()} dB",
                        color = OnSurfaceDim,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Slider(
                        value = hl2Lna,
                        onValueChange = { hl2Lna = it; viewModel.hl2SetLnaDb(it.toInt()) },
                        valueRange = -12f..48f,
                        colors = SliderDefaults.colors(thumbColor = Cyan400, activeTrackColor = Cyan600)
                    )
                } else if (connMode == 4) {
                    // Thetis (VBAN) — network audio to Voicemeeter + optional CAT
                    Text(
                        stringResource(R.string.rig_vban_hint),
                        color = OnSurfaceDim,
                        fontSize = 11.sp
                    )
                    OutlinedTextField(
                        value = vbanHostInput,
                        onValueChange = { vbanHostInput = it },
                        label = { Text(stringResource(R.string.rig_host)) },
                        singleLine = true,
                        enabled = !vbanState.connected,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = vbanPortInput,
                            onValueChange = { vbanPortInput = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.rig_vban_port)) },
                            singleLine = true,
                            enabled = !vbanState.connected,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400)
                        )
                        OutlinedTextField(
                            value = vbanCatPortInput,
                            onValueChange = { vbanCatPortInput = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.rig_vban_cat_port)) },
                            singleLine = true,
                            enabled = !vbanState.connected,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400)
                        )
                    }
                    // CAT dialect of the PC SDR (same backends as the TCP HL2 profile)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "Thetis" to HL2_BACKEND_THETIS,
                            "piHPSDR" to HL2_BACKEND_PIHPSDR,
                            "Spark/Quisk" to HL2_BACKEND_NETRIGCTL
                        ).forEach { (label, key) ->
                            val selected = hl2Backend == key
                            Surface(
                                onClick = { if (!vbanState.connected) hl2Backend = key },
                                modifier = Modifier
                                    .weight(1f)
                                    .border(
                                        1.dp,
                                        if (selected) Cyan400 else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(8.dp)
                                    ),
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) Cyan600.copy(alpha = 0.2f) else SurfaceCard,
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    textAlign = TextAlign.Center,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = if (selected) Cyan400 else OnSurfaceDim
                                )
                            }
                        }
                    }
                    if (vbanState.connected && vbanState.deviceName.isNotEmpty()) {
                        Text(
                            stringResource(R.string.rig_vban_rx_stream, vbanState.deviceName),
                            color = GreenBright,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    // Serial mode — manufacturer + model dropdowns
                    val mfgFilteredModels = remember(selectedMfg) {
                        if (selectedMfg == "All") rigModels
                        else rigModels.filter { it.mfg == selectedMfg }
                    }
                    var searchQuery by remember { mutableStateOf("") }
                    val filteredModels = remember(searchQuery, mfgFilteredModels) {
                        if (searchQuery.isBlank()) mfgFilteredModels
                        else mfgFilteredModels.filter {
                            it.name.contains(searchQuery, ignoreCase = true) ||
                                it.id.toString().contains(searchQuery)
                        }
                    }

                    // Manufacturer dropdown
                    ExposedDropdownMenuBox(
                        expanded = mfgExpanded,
                        onExpandedChange = { if (!rigState.connected) mfgExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = if (selectedMfg == "All") stringResource(R.string.rig_all_manufacturers) else selectedMfg,
                            onValueChange = {},
                            readOnly = true,
                            enabled = !rigState.connected,
                            label = { Text(stringResource(R.string.rig_manufacturer)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mfgExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = mfgExpanded,
                            onDismissRequest = { mfgExpanded = false }
                        ) {
                            manufacturers.forEach { mfg ->
                                val count = if (mfg == "All") rigModels.size
                                            else rigModels.count { it.mfg == mfg }
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                if (mfg == "All") stringResource(R.string.rig_all_manufacturers) else mfg,
                                                fontSize = 14.sp
                                            )
                                            Text("$count", fontSize = 12.sp, color = OnSurfaceDim)
                                        }
                                    },
                                    onClick = {
                                        selectedMfg = mfg
                                        mfgExpanded = false
                                        searchQuery = ""
                                        // Reset model selection to first in new manufacturer
                                        val first = if (mfg == "All") rigModels else rigModels.filter { it.mfg == mfg }
                                        if (first.isNotEmpty()) {
                                            selectedRigIndex = rigModels.indexOf(first.first())
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Model dropdown (filtered by manufacturer)
                    ExposedDropdownMenuBox(
                        expanded = rigModelExpanded,
                        onExpandedChange = { if (!rigState.connected) rigModelExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = if (rigModelExpanded) searchQuery
                                    else rigModels[selectedRigIndex].let { "${it.name} (#${it.id})" },
                            onValueChange = { searchQuery = it },
                            enabled = !rigState.connected,
                            label = { Text(stringResource(R.string.rig_model)) },
                            placeholder = { Text(stringResource(R.string.rig_search)) },
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
                                                Text(model.name, fontSize = 14.sp)
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
                    // USB device picker
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExposedDropdownMenuBox(
                            expanded = usbDeviceExpanded,
                            onExpandedChange = { if (!rigState.connected) usbDeviceExpanded = it },
                            modifier = Modifier.weight(1f)
                        ) {
                            val displayText = if (usbState.devices.isEmpty()) {
                                stringResource(R.string.rig_no_usb_device)
                            } else if (selectedUsbDeviceIndex < usbState.devices.size) {
                                usbState.devices[selectedUsbDeviceIndex].displayName
                            } else {
                                stringResource(R.string.rig_no_usb_device)
                            }
                            OutlinedTextField(
                                value = displayText,
                                onValueChange = {},
                                readOnly = true,
                                enabled = !rigState.connected && usbState.devices.isNotEmpty(),
                                label = { Text(stringResource(R.string.rig_usb_device)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = usbDeviceExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400
                                )
                            )
                            ExposedDropdownMenu(
                                expanded = usbDeviceExpanded,
                                onDismissRequest = { usbDeviceExpanded = false }
                            ) {
                                usbState.devices.forEachIndexed { idx, dev ->
                                    DropdownMenuItem(
                                        text = { Text(dev.displayName, fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
                                        onClick = {
                                            selectedUsbDeviceIndex = idx
                                            usbDeviceExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { viewModel.usbSerialManager.refreshDevices() },
                            enabled = !rigState.connected,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text(stringResource(R.string.rig_usb_refresh), fontSize = 12.sp)
                        }
                    }

                    // Baud rate (+ CI-V address for Icom rigs only)
                    val selectedRig = rigModels[selectedRigIndex]
                    val isIcomRig = selectedRig.mfg == "Icom" || selectedRig.mfg == "Icom Marine" || selectedRig.mfg == "Xiegu"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = serialSpeed,
                            onValueChange = { serialSpeed = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.rig_baud)) },
                            singleLine = true,
                            enabled = !rigState.connected,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400
                            )
                        )
                        if (isIcomRig) {
                            OutlinedTextField(
                                value = civAddrInput,
                                onValueChange = { civAddrInput = it.filter { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' }.take(4) },
                                label = { Text("CI-V") },
                                placeholder = { Text("auto") },
                                singleLine = true,
                                enabled = !rigState.connected,
                                modifier = Modifier.weight(1f),
                                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Cyan400, focusedLabelColor = Cyan400, cursorColor = Cyan400
                                )
                            )
                        }
                    }

                    // DTR / RTS modem line controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ModemLineToggle(
                            label = "DTR",
                            checked = dtrEnabled,
                            onCheckedChange = {
                                dtrEnabled = it
                                rigPrefs.edit().putBoolean("dtr_enabled", it).apply()
                                if (usbState.connectedDevice != null) {
                                    viewModel.setSerialModemLines(dtrEnabled, rtsEnabled)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        ModemLineToggle(
                            label = "RTS",
                            checked = rtsEnabled,
                            onCheckedChange = {
                                rtsEnabled = it
                                rigPrefs.edit().putBoolean("rts_enabled", it).apply()
                                if (usbState.connectedDevice != null) {
                                    viewModel.setSerialModemLines(dtrEnabled, rtsEnabled)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        stringResource(R.string.rig_dtr_rts_hint),
                        color = OnSurfaceDim,
                        fontSize = 11.sp
                    )

                    // USB permission status
                    if (usbState.permissionRequested) {
                        Text(
                            stringResource(R.string.rig_usb_permission_waiting),
                            color = Cyan400,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (anyRigConnected) {
                            viewModel.rigDisconnect()
                        } else {
                            // Persist settings on connect
                            rigPrefs.edit()
                                .putInt("conn_mode", connMode)
                                .putString("host", hostInput)
                                .putString("port", portInput)
                                .putString("tcp_profile", tcpProfile)
                                .putString("hl2_backend", hl2Backend)
                                .putString("baud", serialSpeed)
                                .putInt("rig_index", selectedRigIndex)
                                .putString("civ_addr", civAddrInput)
                                .putString("mfg_filter", selectedMfg)
                                .putString("icom_port", icomPortInput)
                                .putString("icom_user", icomUser)
                                .putString("icom_pass", icomPass)
                                .putString("hl2_host", hl2HostInput)
                                .putString("vban_host", vbanHostInput)
                                .putString("vban_port", vbanPortInput)
                                .putString("vban_cat_port", vbanCatPortInput)
                                .apply()

                            if (connMode == 0) {
                                val port = portInput.toIntOrNull() ?: 4532
                                if (tcpProfile == TCP_PROFILE_HERMES_LITE2) {
                                    // Thetis/piHPSDR CAT servers speak Kenwood
                                    // dialects, not the rigctld protocol — bridge
                                    // through the bundled rigctld instead of
                                    // connecting the rigctld client directly.
                                    viewModel.rigMfg = "OpenHPSDR"
                                    viewModel.rigStartTcpBridge(
                                        model = hl2BackendModel(hl2Backend),
                                        host = hostInput,
                                        port = port
                                    )
                                } else {
                                    viewModel.rigMfg = rigModels[selectedRigIndex].mfg
                                    viewModel.rigConnect(hostInput, port)
                                }
                            } else if (connMode == 2) {
                                val port = icomPortInput.toIntOrNull() ?: 50001
                                viewModel.rigStartIcomNetwork(hostInput, port, icomUser, icomPass)
                            } else if (connMode == 3) {
                                viewModel.rigStartHermesNetwork(hl2HostInput)
                            } else if (connMode == 4) {
                                viewModel.rigMfg = "OpenHPSDR"
                                viewModel.rigStartThetisVban(
                                    host = vbanHostInput,
                                    vbanPort = vbanPortInput.toIntOrNull() ?: 6980,
                                    catModel = hl2BackendModel(hl2Backend),
                                    catPort = vbanCatPortInput.toIntOrNull() ?: 0
                                )
                            } else {
                                val rig = rigModels[selectedRigIndex]
                                viewModel.rigMfg = rig.mfg
                                val speed = serialSpeed.toIntOrNull() ?: 19200
                                val usbDevices = usbState.devices
                                val needsCiv = rig.mfg == "Icom" || rig.mfg == "Icom Marine" || rig.mfg == "Xiegu"
                                if (usbDevices.isNotEmpty() && selectedUsbDeviceIndex < usbDevices.size) {
                                    viewModel.rigStartLocalUsb(
                                        model = rig.id,
                                        usbDevice = usbDevices[selectedUsbDeviceIndex],
                                        speed = speed,
                                        civAddr = if (needsCiv) civAddrInput else "",
                                        dtr = dtrEnabled,
                                        rts = rtsEnabled
                                    )
                                }
                            }
                        }
                    },
                    enabled = !connecting && (anyRigConnected || connMode == 0 || connMode == 2 || connMode == 3 || connMode == 4 || usbState.devices.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            anyRigConnected -> Red400
                            connecting -> OnSurfaceDim
                            else -> Cyan600
                        }
                    )
                ) {
                    if (connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.rig_connecting),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                    } else {
                        Icon(
                            if (anyRigConnected) Icons.Default.LinkOff else Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (anyRigConnected) stringResource(R.string.btn_disconnect) else stringResource(R.string.btn_connect),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                    }
                }

                if (rigState.error.isNotEmpty()) {
                    Text(rigState.error, color = Red400, fontSize = 12.sp)
                }
                if (usbState.error.isNotEmpty() && connMode == 1) {
                    Text(usbState.error, color = Red400, fontSize = 12.sp)
                }
                if (icomState.error.isNotEmpty() && connMode == 2) {
                    Text(icomState.error, color = Red400, fontSize = 12.sp)
                }
                if (hl2State.error.isNotEmpty() && connMode == 3) {
                    Text(hl2State.error, color = Red400, fontSize = 12.sp)
                }
                if (vbanState.error.isNotEmpty() && connMode == 4) {
                    Text(vbanState.error, color = Red400, fontSize = 12.sp)
                }
            }
        }

        // ── Status indicator ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val statusConnected = if (connMode == 3) hl2State.connected else rigState.connected
            val statusPtt = if (connMode == 3) hl2State.ptt else rigState.ptt
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (statusConnected) GreenBright else Red400)
            )
            Text(
                when {
                    connMode == 3 && hl2State.connected -> hl2State.deviceName
                    connMode == 3 -> stringResource(R.string.rig_not_connected)
                    rigState.connected -> stringResource(R.string.rig_connected_to, rigState.host, rigState.port)
                    else -> stringResource(R.string.rig_not_connected)
                },
                fontSize = 12.sp,
                color = if (statusConnected) GreenBright else OnSurfaceDim,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            if (statusPtt) {
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

        // Network audio/IQ stream status (full wireless). Only shown once
        // control is up — tells the user whether RX/TX audio rides the network.
        if ((connMode == 2 && rigState.connected) || (connMode == 3 && hl2State.connected) ||
            (connMode == 4 && vbanState.connected)
        ) {
            val netAudioUp = when (connMode) {
                3 -> hl2State.streaming
                4 -> vbanState.streaming
                else -> icomState.audioConnected
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (netAudioUp) GreenBright else Red400)
                )
                Text(
                    if (netAudioUp) stringResource(R.string.rig_net_audio_on)
                    else stringResource(R.string.rig_net_audio_off),
                    fontSize = 12.sp,
                    color = if (netAudioUp) GreenBright else Red400,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // ── Frequency ──
        SectionLabel(stringResource(R.string.header_frequency))

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
                    text = formatFreq(if (hl2State.connected) hl2State.freqHz else rigState.freqHz),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    color = if (anyRigConnected) Cyan400 else OnSurfaceDim,
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
                        onValueChange = { freqInput = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text(stringResource(R.string.rig_freq_khz)) },
                        singleLine = true,
                        enabled = anyRigConnected,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                freqInput.toDoubleOrNull()?.let { viewModel.rigSetFreq((it * 1000).toLong()) }
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
                            freqInput.toDoubleOrNull()?.let { viewModel.rigSetFreq((it * 1000).toLong()) }
                            focusManager.clearFocus()
                        },
                        enabled = anyRigConnected,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan600)
                    ) {
                        Text(stringResource(R.string.btn_set), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Mode ──
        SectionLabel(stringResource(R.string.header_mode))

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
                    text = rigState.mode.ifEmpty { stringResource(R.string.rig_no_mode) },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (rigState.connected) Cyan400 else OnSurfaceDim,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

            }
        }

        // ── S-Meter ──
        SectionLabel(stringResource(R.string.header_s_meter))

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
private fun ModemLineToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Cyan400)
        )
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

/** Format frequency as "14,250.0 kHz" style display */
private fun formatFreq(hz: Long): String {
    if (hz <= 0) return "-----.-- kHz"  // static placeholder, no i18n needed
    val khz = hz / 1000.0
    return "%,.1f kHz".format(khz)
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
