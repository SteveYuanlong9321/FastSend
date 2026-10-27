package app.fastsend

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class NearbyPeer(
    val endpointId: String,
    val name: String
)

internal data class PendingConnection(
    val endpointId: String,
    val name: String,
    val authenticationDigits: String
)

internal data class NearbyUiState(
    val peers: List<NearbyPeer> = emptyList(),
    val isSearching: Boolean = false,
    val status: String = "准备就绪。请在两台设备上打开 FastSend。",
    val connectedEndpointId: String? = null,
    val connectedName: String? = null,
    val incomingRequest: PendingConnection? = null,
    val transferProgress: Float = -1f,
    val transferStatus: String = "",
    val receivedFiles: List<String> = emptyList()
)

internal class NearbyTransferManager(context: Context) {
    private val context = context.applicationContext
    private val client: ConnectionsClient = Nearby.getConnectionsClient(this.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Both FastSend devices must use the same service ID and strategy.
    @Volatile private var serviceId = "app.fastsend"
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val generation = AtomicLong(0L)

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(NearbyUiState())
    val state: kotlinx.coroutines.flow.StateFlow<NearbyUiState> = _state.asStateFlow()

    private data class IncomingFile(
        val uri: Uri,
        var transferSucceeded: Boolean = false
    )

    private val incomingNames = ConcurrentHashMap<Long, String>()
    private val incomingFiles = ConcurrentHashMap<Long, IncomingFile>()
    private val outgoingFileNames = ConcurrentHashMap<Long, String>()
    private val savingPayloads = ConcurrentHashMap.newKeySet<Long>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val text = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
                    if (!text.startsWith("FASTSEND_META|")) return

                    val parts = text.split('|', limit = 3)
                    if (parts.size != 3) return
                    val fileId = parts[1].toLongOrNull() ?: return
                    val name = parts[2].substringAfterLast('/')
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        .ifBlank { "received_file" }

                    incomingNames[fileId] = name
                    trySaveIncomingFile(fileId)
                }

                Payload.Type.FILE -> {
                    val uri = payload.asFile()?.asUri() ?: return
                    incomingFiles[payload.id] = IncomingFile(uri)
                    trySaveIncomingFile(payload.id)
                }

                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(
            endpointId: String,
            update: PayloadTransferUpdate
        ) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    _state.value = _state.value.copy(
                        transferProgress = if (update.totalBytes > 0L) {
                            update.bytesTransferred.toFloat() / update.totalBytes.toFloat()
                        } else {
                            0f
                        },
                        transferStatus = outgoingFileNames[update.payloadId]
                            ?.let { "正在发送：$it" } ?: "正在接收文件…"
                    )
                }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    // A received FILE URI must not be read until Nearby reports SUCCESS.
                    incomingFiles[update.payloadId]?.transferSucceeded = true
                    trySaveIncomingFile(update.payloadId)

                    outgoingFileNames.remove(update.payloadId)?.let { name ->
                        _state.value = _state.value.copy(
                            transferStatus = "已发送：$name",
                            transferProgress = 1f,
                            status = "文件发送完成。"
                        )
                    }
                }

                PayloadTransferUpdate.Status.FAILURE -> {
                    outgoingFileNames.remove(update.payloadId)
                    incomingFiles.remove(update.payloadId)
                    incomingNames.remove(update.payloadId)
                    savingPayloads.remove(update.payloadId)
                    _state.value = _state.value.copy(
                        transferStatus = "传输失败，请重试。",
                        transferProgress = -1f,
                        status = "文件传输失败。"
                    )
                }

                PayloadTransferUpdate.Status.CANCELED -> {
                    outgoingFileNames.remove(update.payloadId)
                    incomingFiles.remove(update.payloadId)
                    incomingNames.remove(update.payloadId)
                    savingPayloads.remove(update.payloadId)
                    _state.value = _state.value.copy(
                        transferStatus = "传输已取消。",
                        transferProgress = -1f
                    )
                }
            }
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            _state.value = _state.value.copy(
                incomingRequest = PendingConnection(
                    endpointId = endpointId,
                    name = info.endpointName,
                    authenticationDigits = info.authenticationDigits
                ),
                status = "收到来自 ${info.endpointName} 的连接请求，请核对验证码后接受。"
            )
        }

        override fun onConnectionResult(
            endpointId: String,
            resolution: ConnectionResolution
        ) {
            if (resolution.status.statusCode == ConnectionResult.SUCCESS) {
                val peerName = _state.value.peers.firstOrNull { it.endpointId == endpointId }?.name
                    ?: _state.value.incomingRequest
                        ?.takeIf { it.endpointId == endpointId }
                        ?.name
                    ?: "FastSend 设备"

                _state.value = _state.value.copy(
                    connectedEndpointId = endpointId,
                    connectedName = peerName,
                    incomingRequest = null,
                    status = "已连接到 $peerName。可以选择文件并发送。"
                )
            } else {
                _state.value = _state.value.copy(
                    incomingRequest = null,
                    status = "连接未建立（错误码 ${resolution.status.statusCode}）。请在两台设备上确认连接并检查权限。"
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            val wasConnected = _state.value.connectedEndpointId == endpointId
            _state.value = _state.value.copy(
                connectedEndpointId = if (wasConnected) null else _state.value.connectedEndpointId,
                connectedName = if (wasConnected) null else _state.value.connectedName,
                incomingRequest = _state.value.incomingRequest
                    ?.takeUnless { it.endpointId == endpointId },
                status = if (wasConnected) "设备已断开连接。" else _state.value.status
            )
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Ignore self-discovery or malformed names.
            val name = info.endpointName.trim().ifBlank { "FastSend 设备" }
            val peers = (
                    _state.value.peers.filterNot { it.endpointId == endpointId } +
                            NearbyPeer(endpointId, name)
                    ).sortedBy { it.name.lowercase() }

            _state.value = _state.value.copy(
                peers = peers,
                status = "正在搜索附近的 FastSend 设备…"
            )
        }

        override fun onEndpointLost(endpointId: String) {
            _state.value = _state.value.copy(
                peers = _state.value.peers.filterNot { it.endpointId == endpointId }
            )
        }
    }

    fun setStatus(message: String) {
        _state.value = _state.value.copy(status = message)
    }

    /**
     * Follows the lifecycle used by Tickets' Nearby transport:
     * stop stale sessions first, start advertising/discovery, and report each
     * operation's failure instead of leaving the UI in a false "searching" state.
     */
    fun start(pairingToken: String? = null) {
        // QR/NFC share a short-lived token. Both devices derive the same Nearby service ID from it.
        serviceId = pairingToken
            ?.takeIf { it.matches(Regex("[A-Fa-f0-9]{32}")) }
            ?.let { "app.fastsend.pair.${it.lowercase()}" }
            ?: "app.fastsend"
        val runId = generation.incrementAndGet()

        val availability = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)

        if (availability != ConnectionResult.SUCCESS) {
            _state.value = _state.value.copy(
                isSearching = false,
                status = "此设备没有可用的 Google Play 服务，无法启动 Nearby Connections。"
            )
            return
        }

        val missing = missingRuntimePermissions()
        if (missing.isNotEmpty()) {
            _state.value = _state.value.copy(
                isSearching = false,
                status = "Nearby 缺少运行时权限：${missing.joinToString()}。请先在系统权限弹窗中允许后重试。"
            )
            return
        }

        // Avoid overlapping discovery/advertising sessions after repeated taps.
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }

        _state.value = _state.value.copy(
            peers = emptyList(),
            isSearching = false,
            status = "正在启动附近设备搜索…"
        )

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()
        val endpointName = getLocalDeviceName()

        client.startAdvertising(
            endpointName,
            serviceId,
            connectionCallback,
            advertisingOptions
        ).addOnSuccessListener {
            if (generation.get() == runId) {
                _state.value = _state.value.copy(
                    status = "设备广播已启动，正在搜索附近设备…"
                )
            } else {
                runCatching { client.stopAdvertising() }
            }
        }.addOnFailureListener { error ->
            if (generation.get() == runId) {
                _state.value = _state.value.copy(
                    status = "Nearby 广播启动失败：${error.message ?: error.javaClass.simpleName}"
                )
            }
        }

        client.startDiscovery(
            serviceId,
            discoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            if (generation.get() == runId) {
                _state.value = _state.value.copy(
                    isSearching = true,
                    status = "正在搜索附近的 FastSend 设备…"
                )
            } else {
                runCatching { client.stopDiscovery() }
            }
        }.addOnFailureListener { error ->
            if (generation.get() == runId) {
                _state.value = _state.value.copy(
                    isSearching = false,
                    status = "Nearby 搜索启动失败：${error.message ?: error.javaClass.simpleName}"
                )
            }
        }
    }

    private fun missingRuntimePermissions(): List<String> {
        val required = mutableListOf<String>()

        fun check(permission: String) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                required += permission.substringAfterLast('.')
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            check(android.Manifest.permission.BLUETOOTH_SCAN)
            check(android.Manifest.permission.BLUETOOTH_CONNECT)
            check(android.Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            check(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            check(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        return required
    }

    /**
     * Prefer the user-visible Android device name (for example, "Steve 的 S22").
     * Fall back to the device model on Android versions/devices that do not expose it.
     */
    private fun getLocalDeviceName(): String {
        val systemName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            runCatching {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()
        } else {
            null
        }

        return systemName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Android"
    }

    fun stop() {
        generation.incrementAndGet()
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        _state.value = _state.value.copy(
            isSearching = false,
            peers = emptyList(),
            status = "已停止搜索。已建立的连接仍可继续传输。"
        )
    }

    fun requestConnection(endpointId: String) {
        val peer = _state.value.peers.firstOrNull { it.endpointId == endpointId }
        _state.value = _state.value.copy(
            status = "正在请求连接${peer?.name?.let { "：$it" } ?: ""}…"
        )

        client.requestConnection(
            getLocalDeviceName(),
            endpointId,
            connectionCallback
        ).addOnFailureListener { error ->
            _state.value = _state.value.copy(
                status = "连接请求失败：${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    fun acceptIncoming(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener {
                _state.value = _state.value.copy(
                    incomingRequest = null,
                    status = "已接受连接，正在建立安全连接…"
                )
            }
            .addOnFailureListener { error ->
                _state.value = _state.value.copy(
                    status = "接受连接失败：${error.message ?: error.javaClass.simpleName}",
                    incomingRequest = null
                )
            }
    }

    fun rejectIncoming(endpointId: String) {
        client.rejectConnection(endpointId)
            .addOnFailureListener { error ->
                _state.value = _state.value.copy(
                    status = "拒绝连接失败：${error.message ?: error.javaClass.simpleName}"
                )
            }
        _state.value = _state.value.copy(
            incomingRequest = null,
            status = "已拒绝连接请求。"
        )
    }

    fun sendFiles(endpointId: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_state.value.connectedEndpointId != endpointId) {
            _state.value = _state.value.copy(status = "设备尚未连接，请先建立连接。")
            return
        }

        scope.launch {
            _state.value = _state.value.copy(
                transferProgress = 0f,
                transferStatus = "正在准备文件…",
                status = "正在准备发送文件…"
            )

            for (uri in uris) {
                try {
                    val (file, displayName) = withContext(Dispatchers.IO) { copyUriToCache(uri) }
                    val filePayload = Payload.fromFile(file)

                    outgoingFileNames[filePayload.id] = displayName
                    val metadata = Payload.fromBytes(
                        "FASTSEND_META|${filePayload.id}|$displayName".toByteArray(Charsets.UTF_8)
                    )

                    // Send metadata first; receiver pairs it with the FILE payload ID.
                    client.sendPayload(endpointId, metadata)
                        .addOnFailureListener { error ->
                            _state.value = _state.value.copy(
                                status = "发送文件信息失败：${error.message ?: error.javaClass.simpleName}"
                            )
                        }

                    client.sendPayload(endpointId, filePayload)
                        .addOnFailureListener { error ->
                            outgoingFileNames.remove(filePayload.id)
                            _state.value = _state.value.copy(
                                transferProgress = -1f,
                                transferStatus = "发送失败：$displayName",
                                status = "文件发送失败：${error.message ?: error.javaClass.simpleName}"
                            )
                        }

                    _state.value = _state.value.copy(
                        transferStatus = "正在发送：$displayName",
                        status = "正在发送文件…"
                    )
                } catch (error: Exception) {
                    _state.value = _state.value.copy(
                        transferProgress = -1f,
                        transferStatus = "",
                        status = "无法读取文件：${error.message ?: error.javaClass.simpleName}"
                    )
                    return@launch
                }
            }
        }
    }

    private fun copyUriToCache(uri: Uri): Pair<File, String> {
        val resolver = context.contentResolver
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "FastSend_${System.currentTimeMillis()}"

        val safeName = displayName.substringAfterLast('/')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "send_file" }

        val output = File(context.cacheDir, "send_${System.currentTimeMillis()}_$safeName")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开所选文件" }
            FileOutputStream(output).use { destination -> input.copyTo(destination) }
        }
        return output to safeName
    }

    private fun trySaveIncomingFile(payloadId: Long) {
        val name = incomingNames[payloadId] ?: return
        val incoming = incomingFiles[payloadId] ?: return
        if (!incoming.transferSucceeded) return
        if (!savingPayloads.add(payloadId)) return

        incomingNames.remove(payloadId)
        incomingFiles.remove(payloadId)

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val resolver = context.contentResolver
                    val safeName = name.substringAfterLast('/')
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        .ifBlank { "received_file" }
                    val mime = resolver.getType(incoming.uri) ?: "application/octet-stream"
                    val collection = if (Build.VERSION.SDK_INT >= 29) {
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Files.getContentUri("external")
                    }

                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(
                                MediaStore.MediaColumns.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS + "/FastSend"
                            )
                        }
                    }

                    val destination = resolver.insert(collection, values)
                        ?: error("无法创建下载文件")

                    try {
                        resolver.openInputStream(incoming.uri).use { input ->
                            requireNotNull(input) { "无法读取接收文件" }
                            resolver.openOutputStream(destination).use { output ->
                                requireNotNull(output) { "无法写入下载目录" }
                                input.copyTo(output)
                            }
                        }
                    } catch (error: Exception) {
                        resolver.delete(destination, null, null)
                        throw error
                    }

                    safeName
                } catch (error: Exception) {
                    "接收文件保存失败：${error.message ?: error.javaClass.simpleName}"
                }
            }

            savingPayloads.remove(payloadId)
            val failed = result.startsWith("接收文件保存失败：")
            _state.value = if (failed) {
                _state.value.copy(status = result)
            } else {
                _state.value.copy(
                    receivedFiles = _state.value.receivedFiles + result,
                    status = "已接收并保存到 下载/FastSend/$result"
                )
            }
        }
    }

    fun stopAndDisconnect() {
        stop()
        runCatching { client.stopAllEndpoints() }
        incomingNames.clear()
        incomingFiles.clear()
        outgoingFileNames.clear()
        savingPayloads.clear()
        _state.value = _state.value.copy(
            connectedEndpointId = null,
            connectedName = null,
            incomingRequest = null,
            transferProgress = -1f,
            transferStatus = "",
            status = "已断开所有设备。"
        )
    }

    fun dispose() {
        stopAndDisconnect()
        scope.cancel()
    }
}