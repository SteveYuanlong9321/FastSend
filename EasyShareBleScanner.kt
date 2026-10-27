package app.fastsend

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class AllianceBlePeer(
    val name: String,
    val address: String,
    val brand: String,
    val rssi: Int
)

/**
 * BLE discovery for the public EasyShare/CatShare advertisement identifiers.
 *
 * This stage only discovers and displays compatible advertisements. It does not
 * claim that the peer supports FastSend's file-transfer protocol.
 */
class EasyShareBleScanner(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val _peers = MutableStateFlow<List<AllianceBlePeer>>(emptyList())
    val peers: StateFlow<List<AllianceBlePeer>> = _peers.asStateFlow()

    private val _status = MutableStateFlow("准备启动 BLE 搜索")
    val status: StateFlow<String> = _status.asStateFlow()

    private var scanner: BluetoothLeScanner? = null
    @Volatile private var scanning = false

    private val advServiceUuid =
        UUID.fromString("00003331-0000-1000-8000-008123456789")
    private val genericServiceDataUuid =
        UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleResult)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _status.value = "BLE 搜索失败（错误码 $errorCode）"
            Log.w(TAG, "BLE scan failed: $errorCode")
        }
    }

    private fun hasPermission(permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) ||
                    !hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
        ) {
            _status.value = "需要蓝牙扫描与连接权限"
            return
        }

        try {
            val adapter: BluetoothAdapter? = bluetoothManager.adapter
            if (adapter == null) {
                _status.value = "此设备不支持蓝牙"
                return
            }
            if (!adapter.isEnabled) {
                _status.value = "请先开启蓝牙"
                return
            }
            scanner = adapter.bluetoothLeScanner
            val activeScanner = scanner
            if (activeScanner == null) {
                _status.value = "系统未提供 BLE 扫描器"
                return
            }

            _peers.value = emptyList()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            activeScanner.startScan(null, settings, callback)
            scanning = true
            _status.value = "正在搜索附近的 BLE 设备…"
        } catch (error: SecurityException) {
            _status.value = "蓝牙权限不足，无法搜索设备"
        } catch (error: Exception) {
            _status.value = "无法启动 BLE 搜索：${error.message ?: error.javaClass.simpleName}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        try {
            val record = result.scanRecord ?: return
            val serviceUuids = record.serviceUuids.orEmpty().map { it.uuid }
            val serviceData = record.serviceData.orEmpty()

            val hasAllianceMarker =
                advServiceUuid in serviceUuids ||
                        serviceData.keys.any { it.uuid == genericServiceDataUuid } ||
                        serviceData.keys.any { it.uuid.toString().startsWith("000001") }

            if (!hasAllianceMarker) return

            val brandUuid = serviceData.keys.firstOrNull {
                it.uuid.toString().startsWith("000001")
            }
            val brandBytes = brandUuid?.let { serviceData[it] }
            val brandId = brandUuid?.uuid?.toString()
                ?.take(8)?.takeLast(2)?.toIntOrNull(16) ?: 0

            val genericBytes = serviceData[ParcelUuid(genericServiceDataUuid)]
            val advertisedName = genericBytes?.let(::decodeNameFromServiceData)
                ?.takeIf { it.isNotBlank() }
            val name = advertisedName
                ?: record.deviceName?.takeIf { it.isNotBlank() }
                ?: runCatching { result.device.name }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "Android 设备"

            val peer = AllianceBlePeer(
                name = name,
                address = result.device.address ?: return,
                brand = DeviceBrand.nameForId(brandId),
                rssi = result.rssi
            )

            _peers.value = (_peers.value.filterNot { it.address == peer.address } + peer)
                .sortedByDescending { it.rssi }
            _status.value = "发现 ${_peers.value.size} 台 BLE 设备"
        } catch (error: SecurityException) {
            _status.value = "读取 BLE 设备信息时缺少权限"
        } catch (error: Exception) {
            Log.d(TAG, "Ignoring malformed BLE advertisement", error)
        }
    }

    private fun decodeNameFromServiceData(bytes: ByteArray): String? {
        // EasyShare service data stores a 27-byte payload; name begins at byte 10.
        if (bytes.size < 11) return null
        val nameBytes = bytes.copyOfRange(10, minOf(26, bytes.size))
        val end = nameBytes.indexOf(0)
        val trimmed = if (end >= 0) nameBytes.copyOfRange(0, end) else nameBytes
        return trimmed.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            if (scanning) scanner?.stopScan(callback)
        } catch (_: Exception) {
        } finally {
            scanning = false
            scanner = null
        }
    }

    fun refresh() {
        stop()
        _status.value = "正在刷新 BLE 搜索…"
        start()
    }

    companion object {
        private const val TAG = "FastSendBleScanner"
    }
}