package app.fastsend

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.app.Activity
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.South
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

private val FastSendDark = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF172D60),
    primaryContainer = Color(0xFF304777),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFFB9C7E8),
    onSecondary = Color(0xFF233149),
    secondaryContainer = Color(0xFF35465F),
    onSecondaryContainer = Color(0xFFD9E3FF),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF101318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    surfaceContainerLowest = Color(0xFF0B0E12),
    surfaceContainerLow = Color(0xFF191C21),
    surfaceContainer = Color(0xFF1D2026),
    surfaceContainerHigh = Color(0xFF272A31),
    surfaceContainerHighest = Color(0xFF32353D)
)

class MainActivity : ComponentActivity() {
    private lateinit var transfer: LanTransfer
    private lateinit var allianceBleScanner: EasyShareBleScanner
    private var fileCallback: ((List<Uri>) -> Unit)? = null

    private val easyShareBluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it } || hasEasyShareBluetoothPermissions()) {
                startEasyShareBleService()
            } else {
                Toast.makeText(
                    this,
                    "未授予蓝牙权限，FastSend 暂时无法启动 BLE 兼容服务。",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private fun easyShareBluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasEasyShareBluetoothPermissions(): Boolean =
        easyShareBluetoothPermissions().all {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

    private fun startEasyShareBleServiceIfAllowed() {
        if (hasEasyShareBluetoothPermissions()) {
            startEasyShareBleService()
        } else {
            easyShareBluetoothPermissionLauncher.launch(easyShareBluetoothPermissions())
        }
    }

    private fun startEasyShareBleService() {
        try {
            startService(EasyShareBleService.startIntent(this))
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "启动 BLE 广播服务失败：${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
        if (::allianceBleScanner.isInitialized) {
            allianceBleScanner.start()
        }
    }
    private var incomingUris by mutableStateOf<List<Uri>>(emptyList())
    private var nfcAdapter: NfcAdapter? = null
    private var nfcReaderActive = false
    private var pairingResultCallback: ((String) -> Unit)? = null

    @Suppress("DEPRECATION")
    private fun sharedUrisFrom(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                list.orEmpty()
            }
            else -> emptyList()
        }
    }

    // ACTION_GET_CONTENT lets Android/the device vendor present its familiar file manager
    // (for example Samsung My Files) instead of forcing the DocumentsUI OpenDocument flow.
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) fileCallback?.invoke(uris)
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingUris = sharedUrisFrom(intent)
        transfer = LanTransfer(applicationContext)
        transfer.start()
        allianceBleScanner = EasyShareBleScanner(applicationContext)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        startEasyShareBleServiceIfAllowed()
        setContent {
            MaterialTheme(colorScheme = FastSendDark) {
                FastSendScreen(
                    transfer = transfer,
                    allianceBleScanner = allianceBleScanner,
                    incomingUris = incomingUris,
                    onChooseFile = { callback ->
                        fileCallback = callback
                        filePicker.launch("*/*")
                    },
                    onStartNfcReader = { callback ->
                        pairingResultCallback = callback
                        startNfcReader()
                    },
                    onStopNfcReader = { stopNfcReader() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUris = sharedUrisFrom(intent)
    }

    private fun startNfcReader() {
        val adapter = nfcAdapter
        if (adapter == null) {
            Toast.makeText(this, "这台设备不支持 NFC，请使用二维码。", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "请先在系统设置中开启 NFC。", Toast.LENGTH_LONG).show()
            return
        }
        try {
            adapter.enableReaderMode(
                this,
                { tag -> readFastSendTag(tag) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null
            )
            nfcReaderActive = true
        } catch (_: Exception) {
            Toast.makeText(this, "启动 NFC 读取失败，请改用二维码。", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopNfcReader() {
        nfcReaderActive = false
        runCatching { nfcAdapter?.disableReaderMode(this) }
    }

    private fun readFastSendTag(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.timeout = 3000
            isoDep.connect()
            val aid = FastSendHostApduService.AID
            val selectApdu = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) +
                    aid + byteArrayOf(0x00)
            val response = isoDep.transceive(selectApdu)
            if (response.size < 2 || response[response.size - 2] != 0x90.toByte() ||
                response[response.size - 1] != 0x00.toByte()) return
            val payload = response.copyOfRange(0, response.size - 2).toString(Charsets.UTF_8)
            runOnUiThread {
                val message = transfer.addPeerFromPairingPayload(payload)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                stopNfcReader()
            }
        } catch (_: Exception) {
            runOnUiThread {
                Toast.makeText(this, "NFC 配对失败，请使用二维码。", Toast.LENGTH_LONG).show()
            }
        } finally {
            runCatching { isoDep.close() }
        }
    }

    override fun onPause() {
        stopNfcReader()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (nfcReaderActive) startNfcReader()
    }

    override fun onDestroy() {
        stopNfcReader()
        if (::transfer.isInitialized) transfer.stop()
        if (::allianceBleScanner.isInitialized) allianceBleScanner.stop()
        runCatching { stopService(EasyShareBleService.startIntent(this)) }
        super.onDestroy()
    }
}

data class Peer(val name: String, val address: String, val port: Int)
data class TransferState(val message: String = "正在搜索附近设备", val busy: Boolean = false)

private data class PendingFile(val uri: Uri, val name: String)

@Composable
private fun FastSendScreen(
    transfer: LanTransfer,
    allianceBleScanner: EasyShareBleScanner,
    incomingUris: List<Uri>,
    onChooseFile: (((List<Uri>) -> Unit) -> Unit),
    onStartNfcReader: ((String) -> Unit) -> Unit,
    onStopNfcReader: () -> Unit
) {
    val peers by transfer.peers.collectAsState()
    val alliancePeers by allianceBleScanner.peers.collectAsState()
    val allianceScanStatus by allianceBleScanner.status.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val allianceBleClient = remember { EasyShareBleClient(context) }
    val allianceGattResults by allianceBleClient.results.collectAsState()
    val allianceGattActive by allianceBleClient.active.collectAsState()
    val state by transfer.state.collectAsState()
    var selectedFiles by remember { mutableStateOf<List<PendingFile>>(emptyList()) }
    var showInfo by remember { mutableStateOf(false) }
    var selectedRole by remember { mutableStateOf("发送") }
    var showQrScreen by remember { mutableStateOf(false) }
    var showNfcScreen by remember { mutableStateOf(false) }
    var nfcReaderStarted by remember { mutableStateOf(false) }
    var pairingPayload by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme

    val handleScannedPayload: (String) -> Unit = { payload ->
        val result = transfer.addPeerFromPairingPayload(payload)
        Toast.makeText(context, result, Toast.LENGTH_LONG).show()
        if (result.startsWith("已连接信息配对")) showQrScreen = false
    }

    // 接收端点击“二维码连接”后，直接启动 Google 扫码器，不再显示中间页面。
    val scanAndPair: () -> Unit = {
        val activity = context as? Activity
        if (activity == null) {
            Toast.makeText(context, "无法启动 Google 扫码器。", Toast.LENGTH_LONG).show()
        } else {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            GmsBarcodeScanning.getClient(activity, options)
                .startScan()
                .addOnSuccessListener { barcode ->
                    val value = barcode.rawValue
                    if (!value.isNullOrBlank()) {
                        handleScannedPayload(value)
                    } else {
                        Toast.makeText(context, "二维码内容为空，请重新扫描。", Toast.LENGTH_LONG).show()
                    }
                }
                .addOnCanceledListener {
                    // 用户取消扫描，留在接收文件主页。
                }
                .addOnFailureListener { error ->
                    Toast.makeText(
                        context,
                        "无法启动 Google 扫码器：${error.localizedMessage ?: "请确认 Google Play 服务可用"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    BackHandler(enabled = showInfo || showQrScreen || showNfcScreen) {
        when {
            showInfo -> showInfo = false
            showQrScreen -> showQrScreen = false
            showNfcScreen -> {
                showNfcScreen = false
                nfcReaderStarted = false
                onStopNfcReader()
            }
        }
    }

    LaunchedEffect(incomingUris) {
        if (incomingUris.isNotEmpty()) {
            selectedFiles = incomingUris.distinct().map { uri ->
                PendingFile(uri, queryName(context, uri) ?: uri.path?.substringAfterLast('/') ?: "共享文件")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        AnimatedVisibility(
            visible = !showQrScreen && !showNfcScreen,
            enter = fadeIn(animationSpec = tween(240)) + slideInHorizontally(
                initialOffsetX = { -it / 10 }, animationSpec = tween(260)
            ),
            exit = fadeOut(animationSpec = tween(150)) + slideOutHorizontally(
                targetOffsetX = { -it / 12 }, animationSpec = tween(180)
            )
        ) {
            Scaffold(
                containerColor = colors.background,
                topBar = {
                    Row(
                        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 22.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("FastSend", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("跨设备文件传输", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        IconButton(onClick = { showInfo = true }) {
                            Icon(Icons.Default.Info, contentDescription = "关于", tint = colors.onSurfaceVariant)
                        }
                    }
                }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(25.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle("开始传输")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Card(
                                    modifier = Modifier.weight(1f).height(148.dp),
                                    shape = RoundedCornerShape(22.dp),
                                    colors = CardDefaults.cardColors(containerColor = if (selectedRole == "发送") colors.primaryContainer else colors.surfaceContainerHigh),
                                    onClick = {
                                        selectedRole = "发送"
                                    }
                                ) {
                                    Column(Modifier.fillMaxSize().padding(17.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Icon(Icons.Default.FileUpload, contentDescription = null, tint = if (selectedRole == "发送") colors.onPrimaryContainer else colors.primary, modifier = Modifier.size(26.dp))
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("发送文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (selectedRole == "发送") colors.onPrimaryContainer else colors.onSurface)
                                            Text("选择内容并分享", style = MaterialTheme.typography.bodySmall, color = if (selectedRole == "发送") colors.onPrimaryContainer.copy(alpha = .78f) else colors.onSurfaceVariant)
                                        }
                                    }
                                }
                                Card(
                                    modifier = Modifier.weight(1f).height(148.dp),
                                    shape = RoundedCornerShape(22.dp),
                                    colors = CardDefaults.cardColors(containerColor = if (selectedRole == "接收") colors.primaryContainer else colors.surfaceContainerHigh),
                                    onClick = {
                                        selectedRole = "接收"
                                        selectedFiles = emptyList()
                                    }
                                ) {
                                    Column(Modifier.fillMaxSize().padding(17.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Icon(Icons.Default.FileDownload, contentDescription = null, tint = if (selectedRole == "接收") colors.onPrimaryContainer else colors.primary, modifier = Modifier.size(26.dp))
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("接收文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (selectedRole == "接收") colors.onPrimaryContainer else colors.onSurface)
                                            Text("等待设备连接", style = MaterialTheme.typography.bodySmall, color = if (selectedRole == "接收") colors.onPrimaryContainer.copy(alpha = .78f) else colors.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            if (selectedRole == "发送") {
                                Button(
                                    onClick = {
                                        onChooseFile { uri ->
                                            selectedFiles = uri.map { fileUri ->
                                                PendingFile(
                                                    fileUri,
                                                    queryName(context, fileUri) ?: fileUri.path?.substringAfterLast('/') ?: "所选文件"
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(17.dp)
                                ) {
                                    Icon(Icons.Default.FileUpload, contentDescription = null)
                                    Spacer(Modifier.width(9.dp))
                                    Text(if (selectedFiles.isEmpty()) "选择文件" else "更换文件")
                                }
                            }
                        }
                    }
                    if (selectedFiles.isNotEmpty()) {
                        item {
                            Surface(shape = RoundedCornerShape(16.dp), color = colors.secondaryContainer) {
                                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = colors.onSecondaryContainer)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(if (selectedFiles.size == 1) "已选择文件" else "已选择 ${selectedFiles.size} 个文件", style = MaterialTheme.typography.labelMedium, color = colors.onSecondaryContainer)
                                        Text(selectedFiles.first().name + if (selectedFiles.size > 1) " 等" else "", style = MaterialTheme.typography.bodyMedium, color = colors.onSecondaryContainer, maxLines = 1)
                                    }
                                    IconButton(onClick = { selectedFiles = emptyList() }) { Icon(Icons.Default.Close, contentDescription = "取消选择", tint = colors.onSecondaryContainer) }
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle("连接方式")
                            Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer) {
                                Column {
                                    ConnectionRow(
                                        icon = { Icon(Icons.Default.QrCode2, contentDescription = null, tint = colors.onSecondaryContainer) },
                                        title = "二维码连接", subtitle = "扫描二维码建立设备会话", colors = colors,
                                        onClick = {
                                            if (selectedRole == "发送") {
                                                pairingPayload = transfer.localPairingPayload()
                                                context.getSharedPreferences(FastSendHostApduService.PREFS_NAME, Context.MODE_PRIVATE)
                                                    .edit().putString(FastSendHostApduService.PREF_TOKEN, pairingPayload).apply()
                                                showQrScreen = true
                                            } else {
                                                scanAndPair()
                                            }
                                        }
                                    )
                                    HorizontalDivider(Modifier.padding(start = 72.dp), color = colors.outlineVariant.copy(alpha = .55f))
                                    ConnectionRow(
                                        icon = { Icon(Icons.Default.Nfc, contentDescription = "NFC", tint = colors.onSecondaryContainer) },
                                        title = "NFC 碰一碰", subtitle = "轻触设备，快速建立连接", colors = colors,
                                        onClick = {
                                            if (selectedRole == "发送") {
                                                pairingPayload = transfer.localPairingPayload()
                                                context.getSharedPreferences(FastSendHostApduService.PREFS_NAME, Context.MODE_PRIVATE)
                                                    .edit().putString(FastSendHostApduService.PREF_TOKEN, pairingPayload).apply()
                                                showNfcScreen = true
                                                nfcReaderStarted = false
                                                if (nfcAdapterMissing(context)) {
                                                    Toast.makeText(context, "当前设备可能不支持 NFC/HCE，请使用二维码。", Toast.LENGTH_LONG).show()
                                                }
                                            } else {
                                                showNfcScreen = true
                                                nfcReaderStarted = false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionTitle("互传联盟设备（BLE）")
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { allianceBleScanner.refresh() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "刷新互传联盟设备", tint = colors.onSurfaceVariant)
                                }
                            }
                            Text(allianceScanStatus, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            if (alliancePeers.isEmpty()) {
                                Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = colors.onSurfaceVariant)
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text("暂未发现 BLE 设备", style = MaterialTheme.typography.titleSmall)
                                            Text("请让对方开启支持的互传功能，并保持蓝牙开启。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        }
                                    }
                                }
                            } else {
                                alliancePeers.forEach { peer ->
                                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)) {
                                        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Surface(shape = RoundedCornerShape(13.dp), color = colors.secondaryContainer, modifier = Modifier.size(44.dp)) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.Bluetooth, contentDescription = null, tint = colors.onSecondaryContainer)
                                                }
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(peer.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                                                Text("${peer.brand} · ${peer.address} · ${peer.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                            }
                                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("已发现", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                                                Button(
                                                    onClick = { allianceBleClient.readStatus(peer) },
                                                    enabled = peer.address !in allianceGattActive
                                                ) {
                                                    Text(if (peer.address in allianceGattActive) "读取中" else "读取信息")
                                                }
                                            }
                                        }
                                        allianceGattResults[peer.address]?.let { result ->
                                            Column(Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text("GATT：${result.state}", style = MaterialTheme.typography.bodySmall, color = colors.primary)
                                                if (result.state == "读取完成") {
                                                    Text("协议标记：${result.protocol ?: "未知"} · 加密标记：${result.crypto ?: "未知"}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                                    Text(if (result.publicKeyPresent) "已读取到公钥字段" else "未发现公钥字段", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                                }
                                                result.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
                                            }
                                        }
                                    }
                                }
                            }
                            Text(
                                "下一步测试：点“读取信息”检查对端是否开放互传联盟 GATT 状态服务。读取成功只代表 BLE/GATT 可通信，尚不代表已完成加密协商或文件传输。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionTitle("局域网设备")
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { transfer.refreshDiscovery() }) { Icon(Icons.Default.Refresh, contentDescription = "刷新设备", tint = colors.onSurfaceVariant) }
                            }
                            Text(state.message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            if (peers.isEmpty()) {
                                Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(32.dp), tint = colors.onSurfaceVariant)
                                        Spacer(Modifier.height(12.dp))
                                        Text("正在寻找附近设备", style = MaterialTheme.typography.titleSmall)
                                        Spacer(Modifier.height(5.dp))
                                        Text("让另一台设备打开 FastSend，\n并连接到同一个局域网。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, lineHeight = 19.sp)
                                    }
                                }
                            } else {
                                peers.forEach { peer ->
                                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)) {
                                        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Surface(shape = RoundedCornerShape(13.dp), color = colors.secondaryContainer, modifier = Modifier.size(44.dp)) {
                                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Smartphone, contentDescription = null, tint = colors.onSecondaryContainer) }
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(peer.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                                                Text(peer.address, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                            }
                                            FilledTonalButton(enabled = selectedFiles.isNotEmpty() && !state.busy, onClick = {
                                                transfer.sendFiles(peer, selectedFiles.map { it.uri to it.name }) { result ->
                                                    Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                                                }
                                            }) { Text("发送") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text("FASTSEND  ·  本地设备直连", modifier = Modifier.fillMaxWidth().padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = .65f))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showQrScreen,
            enter = fadeIn(animationSpec = tween(220)) + slideInHorizontally(
                initialOffsetX = { it / 7 }, animationSpec = tween(280)
            ),
            exit = fadeOut(animationSpec = tween(150)) + slideOutHorizontally(
                targetOffsetX = { it / 9 }, animationSpec = tween(180)
            )
        ) {
            QrPairingScreen(
                isSender = selectedRole == "发送",
                payload = pairingPayload,
                onBack = { showQrScreen = false },
                onScan = scanAndPair
            )
        }

        AnimatedVisibility(
            visible = showNfcScreen,
            enter = fadeIn(animationSpec = tween(220)) + slideInHorizontally(
                initialOffsetX = { it / 7 }, animationSpec = tween(280)
            ),
            exit = fadeOut(animationSpec = tween(150)) + slideOutHorizontally(
                targetOffsetX = { it / 9 }, animationSpec = tween(180)
            )
        ) {
            NfcPairingScreen(
                isSender = selectedRole == "发送",
                readerStarted = nfcReaderStarted,
                onBack = {
                    showNfcScreen = false
                    nfcReaderStarted = false
                    onStopNfcReader()
                },
                onStartReader = {
                    nfcReaderStarted = true
                    onStartNfcReader { payload ->
                        val result = transfer.addPeerFromPairingPayload(payload)
                        Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                        onStopNfcReader()
                        nfcReaderStarted = false
                        if (result.startsWith("已连接信息配对")) showNfcScreen = false
                    }
                }
            )
        }
    }

    AnimatedVisibility(
        visible = showInfo,
        enter = slideInHorizontally(
            initialOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(durationMillis = 280)
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(durationMillis = 240)
        ),
        modifier = Modifier.fillMaxSize()
    ) {
        var edgeDragEligible by remember { mutableStateOf(false) }
        var edgeDragDistance by remember { mutableStateOf(0f) }
        var updateChecking by remember { mutableStateOf(false) }
        var updateDialog by remember { mutableStateOf<UpdateDialogInfo?>(null) }
        val updateScope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .pointerInput(showInfo) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            edgeDragEligible = offset.x <= 32.dp.toPx()
                            edgeDragDistance = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (edgeDragEligible) {
                                edgeDragDistance = (edgeDragDistance + dragAmount).coerceAtLeast(0f)
                            }
                        },
                        onDragEnd = {
                            if (edgeDragEligible && edgeDragDistance >= 88.dp.toPx()) {
                                showInfo = false
                            }
                            edgeDragEligible = false
                            edgeDragDistance = 0f
                        },
                        onDragCancel = {
                            edgeDragEligible = false
                            edgeDragDistance = 0f
                        }
                    )
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showInfo = false }) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回主页",
                        tint = colors.onSurface
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "关于快速互传",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        color = colors.primaryContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = colors.onPrimaryContainer.copy(alpha = 0.10f),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Devices,
                                        contentDescription = null,
                                        tint = colors.onPrimaryContainer,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }
                            Text(
                                text = "FastSend",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.onPrimaryContainer
                            )
                            Text(
                                text = "让设备之间的文件传输更简单、快速，并尽可能减少对云端服务的依赖。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onPrimaryContainer.copy(alpha = 0.82f)
                            )
                        }
                    }
                }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = colors.surfaceContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            AboutInfoRow(label = "版本", value = "1.0")
                            HorizontalDivider(color = colors.outlineVariant)
                            AboutInfoRow(label = "当前传输方式", value = "FastSend 局域网直连")
                            HorizontalDivider(color = colors.outlineVariant)
                            AboutInfoRow(label = "后续计划", value = "二维码 · NFC · 互传联盟兼容")
                        }
                    }
                }
                item {
                    Text(
                        text = "FastSend 目前仍处于开发阶段，功能与安全机制会持续完善。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                item {
                    Button(
                        onClick = {
                            if (!updateChecking) {
                                updateChecking = true
                                updateScope.launch {
                                    updateDialog = try {
                                        withContext(Dispatchers.IO) {
                                            checkFastSendUpdate(context)
                                        }
                                    } catch (error: Exception) {
                                        UpdateDialogInfo(
                                            title = "检查更新失败",
                                            message = "无法连接 GitHub 获取版本信息。请检查网络后重试。\\n\\n${error.localizedMessage ?: error.javaClass.simpleName}"
                                        )
                                    } finally {
                                        updateChecking = false
                                    }
                                }
                            }
                        },
                        enabled = !updateChecking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (updateChecking) "正在检查更新…" else "检查更新")
                    }
                }
            }
        }

        updateDialog?.let { info ->
            AlertDialog(
                onDismissRequest = { updateDialog = null },
                title = { Text(info.title) },
                text = { Text(info.message) },
                confirmButton = {
                    if (!info.downloadUrl.isNullOrBlank()) {
                        TextButton(onClick = {
                            val url = info.downloadUrl
                            updateDialog = null
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }.onFailure {
                                Toast.makeText(context, "无法打开下载链接", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("前往下载")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateDialog = null }) { Text("关闭") }
                }
            )
        }
    }
}


private data class UpdateDialogInfo(
    val title: String,
    val message: String,
    val downloadUrl: String? = null
)

private fun checkFastSendUpdate(context: Context): UpdateDialogInfo {
    val apiUrl = URL("https://api.github.com/repos/SteveYuanlong9321/FastSend/releases/latest")
    val connection = (apiUrl.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10000
        readTimeout = 10000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "FastSend-Android")
    }

    try {
        if (connection.responseCode !in 200..299) {
            throw java.io.IOException("GitHub 返回 HTTP ${connection.responseCode}")
        }
        val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val release = JSONObject(json)
        val latestTag = release.optString("tag_name", "").removePrefix("v").trim()
        if (latestTag.isBlank()) throw java.io.IOException("发布信息中没有版本号")

        val currentVersion = context.packageManager
            .getPackageInfo(context.packageName, 0).versionName.orEmpty()
            .removePrefix("v").trim()

        fun versionParts(value: String): List<Int> =
            value.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }

        fun isNewer(latest: String, current: String): Boolean {
            val a = versionParts(latest)
            val b = versionParts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val av = a.getOrElse(i) { 0 }
                val bv = b.getOrElse(i) { 0 }
                if (av != bv) return av > bv
            }
            return false
        }

        if (!isNewer(latestTag, currentVersion)) {
            return UpdateDialogInfo(
                title = "已是最新版本",
                message = "当前版本：$currentVersion\\nGitHub 最新版本：$latestTag\\n\\nFastSend 已经是最新版本。"
            )
        }

        val assets = release.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                    if (apkUrl != null) break
                }
            }
        }
        val releaseUrl = release.optString("html_url", "https://github.com/SteveYuanlong9321/FastSend/releases/latest")
        val downloadUrl = apkUrl ?: releaseUrl
        val notes = release.optString("body", "").trim().ifBlank { "此版本暂无更新说明。" }
        return UpdateDialogInfo(
            title = "发现新版本：$latestTag",
            message = "当前版本：$currentVersion\\n最新版本：$latestTag\\n\\n更新说明：\\n$notes",
            downloadUrl = downloadUrl
        )
    } finally {
        connection.disconnect()
    }
}

private fun createQrBitmap(content: String, size: Int): Bitmap? {
    if (content.isBlank() || size <= 0) return null
    return runCatching {
        val matrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }.getOrNull()
}

@Composable
private fun PairingPageFrame(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
private fun QrPairingScreen(
    isSender: Boolean,
    payload: String,
    onBack: () -> Unit,
    onScan: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    PairingPageFrame(
        title = "二维码连接",
        subtitle = if (isSender) "让另一台设备扫描此码" else "扫描发送设备上的二维码",
        onBack = onBack
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(colors.primary.copy(alpha = 0.13f), colors.secondary.copy(alpha = 0.06f))
                    ),
                    shape = RoundedCornerShape(30.dp)
                )
                .padding(22.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(292.dp)
                        .background(Color.White, RoundedCornerShape(24.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSender) {
                        val bitmap = remember(payload) { createQrBitmap(payload, 640) }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "FastSend 配对二维码",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(96.dp), tint = Color.DarkGray)
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(72.dp), tint = colors.primary)
                            Spacer(Modifier.height(12.dp))
                            Text("准备扫描", style = MaterialTheme.typography.titleMedium, color = Color.DarkGray)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    if (isSender) "FastSend 安全配对" else "连接附近设备",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isSender) "请让接收方打开二维码连接并扫描上方二维码。"
                    else "请对准发送方设备上的 FastSend 二维码。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        if (!isSender) {
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Default.QrCode2, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("打开相机扫描二维码")
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = colors.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.primary)
                    Text("保持此页面打开，等待接收设备扫描。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("返回首页") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NfcPairingScreen(
    isSender: Boolean,
    readerStarted: Boolean,
    onBack: () -> Unit,
    onStartReader: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "nfc-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.08f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(1100),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "nfc-pulse-scale"
    )
    PairingPageFrame(
        title = "NFC 碰一碰",
        subtitle = if (isSender) "等待另一台设备靠近" else "将手机靠近发送设备",
        onBack = onBack
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(colors.primary.copy(alpha = 0.16f), colors.tertiary.copy(alpha = 0.07f))
                    ),
                    shape = RoundedCornerShape(30.dp)
                )
                .padding(vertical = 28.dp, horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(250.dp)) {
                    repeat(3) { index ->
                        Box(
                            modifier = Modifier
                                .size((150 + index * 42).dp * if (readerStarted) pulse else 1f)
                                .background(
                                    colors.primary.copy(alpha = (0.07f - index * 0.012f).coerceAtLeast(0.025f)),
                                    CircleShape
                                )
                        )
                    }
                    Surface(
                        modifier = Modifier.size(118.dp),
                        shape = RoundedCornerShape(32.dp),
                        color = colors.primary,
                        shadowElevation = 10.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Nfc, contentDescription = "NFC", tint = colors.onPrimary, modifier = Modifier.size(68.dp))
                        }
                    }
                    Icon(
                        Icons.Default.Smartphone,
                        contentDescription = null,
                        tint = colors.primary.copy(alpha = 0.75f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = (-8).dp, y = (-8).dp)
                            .size(46.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        isSender -> "准备好碰一碰"
                        readerStarted -> "正在读取配对信息…"
                        else -> "点击下方按钮开始读取"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        isSender -> "保持此页面打开，并确保系统已开启 NFC。"
                        readerStarted -> "将手机背部靠近发送端手机的 NFC 感应区域。"
                        else -> "接收端需要主动启动 NFC 读取，再与发送端轻触。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        if (!isSender) {
            Button(
                onClick = onStartReader,
                enabled = !readerStarted,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Default.Nfc, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(if (readerStarted) "正在等待 NFC 触碰" else "开始 NFC 配对")
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = colors.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.primary)
                    Text("发送端已准备好。接收端点击“开始 NFC 配对”后，将两台手机背部靠近。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("返回首页") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ConnectionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    colors: androidx.compose.material3.ColorScheme,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = colors.secondaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
    }
}

private fun queryName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
} catch (_: Exception) { null }

private fun createFastSendQr(content: String, size: Int): Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until size) for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}.getOrNull()

private fun nfcAdapterMissing(context: Context): Boolean =
    NfcAdapter.getDefaultAdapter(context) == null

/** Prototype LAN channel: UDP discovery + TCP file transfer. */
class LanTransfer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val peerList = CopyOnWriteArrayList<Peer>()
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers = _peers
    private val _state = MutableStateFlow(TransferState())
    val state = _state
    private val udpPort = 37020
    private val tcpPort = 37021
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".take(40)

    fun localPairingPayload(): String {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = runCatching {
            val raw = wifi.connectionInfo.ipAddress
            listOf(raw and 0xff, (raw shr 8) and 0xff, (raw shr 16) and 0xff, (raw shr 24) and 0xff).joinToString(".")
        }.getOrDefault("")
        return "FASTSEND1|$ip|$tcpPort|${deviceName.replace('|', ' ')}"
    }

    fun addPeerFromPairingPayload(payload: String): String {
        val parts = payload.split("|", limit = 4)
        if (parts.size != 4 || parts[0] != "FASTSEND1") return "配对信息无效，请重新扫码或碰一碰。"
        val address = parts[1]
        val port = parts[2].toIntOrNull()
        val name = parts[3].ifBlank { "FastSend 设备" }
        val validIp = address.split(".").size == 4 && address.split(".").all { it.toIntOrNull() in 0..255 }
        if (!validIp || port == null || port !in 1..65535) return "配对信息无效，请确认发送端已连接 Wi-Fi。"
        val peer = Peer(name, address, port)
        peerList.removeAll { it.address == address }
        peerList.add(0, peer)
        _peers.value = peerList.toList()
        _state.value = TransferState("已通过配对信息找到设备：$name")
        return "已读取配对信息：$name"
    }

    fun start() {
        if (running) return
        running = true
        scope.launch { runTcpServer() }
        scope.launch { runDiscoveryListener() }
        scope.launch { announceLoop() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) { }
        try { discoverySocket?.close() } catch (_: Exception) { }
        scope.cancel()
    }

    fun refreshDiscovery() {
        _state.value = _state.value.copy(message = "正在重新搜索附近设备")
        scope.launch {
            delay(1500)
            _state.value = _state.value.copy(message = if (peerList.isEmpty()) "暂未发现设备，请确认两台设备在同一局域网" else "发现 ${peerList.size} 台设备")
        }
    }

    private suspend fun announceLoop() {
        while (running) {
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lock = wifi.createMulticastLock("fastsend-discovery")
                lock.setReferenceCounted(false)
                lock.acquire()
                try {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        val bytes = "FASTSEND|$tcpPort|$deviceName".toByteArray()
                        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), udpPort))
                    }
                } finally { if (lock.isHeld) lock.release() }
            } catch (_: Exception) { }
            delay(3000)
        }
    }

    private suspend fun runDiscoveryListener() {
        while (running) {
            try {
                DatagramSocket(udpPort).use { socket ->
                    discoverySocket = socket
                    socket.broadcast = true
                    socket.soTimeout = 2000
                    val buffer = ByteArray(1024)
                    while (running) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val parts = String(packet.data, 0, packet.length).split("|", limit = 3)
                            if (parts.size == 3 && parts[0] == "FASTSEND") {
                                val address = packet.address.hostAddress ?: continue
                                if (!isOwnAddress(address)) {
                                    val peer = Peer(parts[2], address, parts[1].toIntOrNull() ?: tcpPort)
                                    peerList.removeAll { it.address == address }
                                    peerList.add(peer)
                                    _peers.value = peerList.toList()
                                    _state.value = TransferState("已发现 ${peerList.size} 台设备")
                                }
                            }
                        } catch (_: java.net.SocketTimeoutException) { }
                    }
                }
            } catch (_: Exception) { delay(500) }
        }
    }

    private fun isOwnAddress(address: String): Boolean {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val addresses = interfaces.nextElement().inetAddresses
                while (addresses.hasMoreElements()) {
                    if (addresses.nextElement().hostAddress == address) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun runTcpServer() {
        while (running) {
            try {
                ServerSocket(tcpPort).use { server ->
                    serverSocket = server
                    while (running) {
                        val client = server.accept()
                        scope.launch { receiveFile(client) }
                    }
                }
            } catch (_: Exception) { if (running) delay(500) }
        }
    }

    private fun receiveFile(socket: Socket) {
        socket.use { client ->
            var targetUri: Uri? = null
            try {
                client.soTimeout = 30_000
                val input = DataInputStream(client.getInputStream())
                if (input.readUTF() != "FASTSEND_V1") return
                val fileName = input.readUTF().replace("/", "_").replace("\\", "_").take(180)
                val size = input.readLong()
                if (size < 0 || size > 10L * 1024 * 1024 * 1024) return
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "FastSend_$fileName")
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Downloads.RELATIVE_PATH, "Download/FastSend")
                }
                val resolver = context.contentResolver
                targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(targetUri!!)?.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = size
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw java.io.EOFException("文件传输中断")
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                } ?: throw java.io.IOException("无法创建目标文件")
                _state.value = TransferState("接收完成：$fileName")
            } catch (e: Exception) {
                targetUri?.let { try { context.contentResolver.delete(it, null, null) } catch (_: Exception) { } }
                _state.value = TransferState("接收失败：${e.message ?: "连接异常"}")
            }
        }
    }

    fun sendFile(peer: Peer, uri: Uri, displayName: String, onResult: (String) -> Unit) =
        sendFiles(peer, listOf(uri to displayName), onResult)

    fun sendFiles(peer: Peer, files: List<Pair<Uri, String>>, onResult: (String) -> Unit) {
        if (_state.value.busy) { onResult("当前已有传输任务"); return }
        if (files.isEmpty()) { onResult("请先选择文件"); return }
        _state.value = TransferState("正在发送 ${files.size} 个文件到 ${peer.name}", true)
        scope.launch {
            try {
                files.forEachIndexed { index, (uri, displayName) ->
                    val resolver = context.contentResolver
                    val name = queryName(context, uri) ?: displayName.ifBlank { "FastSend_file" }
                    val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    if (size < 0) throw IllegalStateException("无法读取文件大小：$name")
                    Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(peer.address, peer.port), 8000)
                        socket.soTimeout = 30_000
                        val output = DataOutputStream(socket.getOutputStream())
                        output.writeUTF("FASTSEND_V1")
                        output.writeUTF(name)
                        output.writeLong(size)
                        resolver.openInputStream(uri)?.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var sent = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                sent += count
                                _state.value = TransferState("发送第 ${index + 1}/${files.size} 个：${sent / 1024} KB / ${size / 1024} KB", true)
                            }
                        } ?: throw java.io.IOException("无法读取文件：$name")
                        output.flush()
                    }
                }
                val result = "已向 ${peer.name} 发送 ${files.size} 个文件"
                _state.value = TransferState("发送完成：${files.size} 个文件")
                withContext(Dispatchers.Main) { onResult(result) }
            } catch (e: Exception) {
                val message = "发送失败：${e.message ?: "请确认两台设备在同一局域网"}"
                _state.value = TransferState(message)
                withContext(Dispatchers.Main) { onResult(message) }
            } finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}