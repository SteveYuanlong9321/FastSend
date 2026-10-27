package app.fastsend

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class AllianceGattResult(
    val address: String,
    val state: String,
    val deviceState: String? = null,
    val protocol: String? = null,
    val crypto: String? = null,
    val publicKeyPresent: Boolean = false,
    val error: String? = null
)

/**
 * Connects to a discovered BLE peer and reads its public status characteristic.
 * This is a diagnostic/read-only step; it does not perform the encrypted
 * EasyShare session negotiation or transfer files.
 */
class EasyShareBleClient(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val _results = MutableStateFlow<Map<String, AllianceGattResult>>(emptyMap())
    val results: StateFlow<Map<String, AllianceGattResult>> = _results.asStateFlow()

    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    private val serviceUuid = UUID.fromString("00009955-0000-1000-8000-00805f9b34fb")
    private val statusUuid = UUID.fromString("00009954-0000-1000-8000-00805f9b34fb")
    private val connections = mutableMapOf<String, BluetoothGatt>()

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun readStatus(peer: AllianceBlePeer) {
        if (!hasConnectPermission()) {
            setResult(peer.address, AllianceGattResult(peer.address, "读取失败", error = "缺少蓝牙连接权限"))
            return
        }
        if (peer.address in _active.value) return

        val device = try {
            bluetoothManager.adapter?.getRemoteDevice(peer.address)
        } catch (e: Exception) {
            null
        }
        if (device == null) {
            setResult(peer.address, AllianceGattResult(peer.address, "读取失败", error = "无法取得设备"))
            return
        }

        _active.value = _active.value + peer.address
        setResult(peer.address, AllianceGattResult(peer.address, "正在连接"))

        try {
            val gatt = device.connectGatt(appContext, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failAndClose(peer.address, gatt, "GATT 连接失败（$status）")
                        return
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        setResult(peer.address, AllianceGattResult(peer.address, "已连接，正在发现服务"))
                        @SuppressLint("MissingPermission")
                        val started = try { gatt.discoverServices() } catch (_: SecurityException) { false }
                        if (!started) failAndClose(peer.address, gatt, "无法发现 GATT 服务")
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        val old = _results.value[peer.address]
                        if (old?.state == "读取完成") {
                            close(peer.address, gatt)
                        } else {
                            failAndClose(peer.address, gatt, "设备已断开连接")
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failAndClose(peer.address, gatt, "发现 GATT 服务失败（$status）")
                        return
                    }
                    val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(statusUuid)
                    if (characteristic == null) {
                        failAndClose(peer.address, gatt, "设备未提供互传联盟状态特征")
                        return
                    }
                    setResult(peer.address, AllianceGattResult(peer.address, "正在读取设备状态"))
                    @SuppressLint("MissingPermission")
                    val started = try { gatt.readCharacteristic(characteristic) } catch (_: SecurityException) { false }
                    if (!started) failAndClose(peer.address, gatt, "无法读取设备状态")
                }

                @Deprecated("Deprecated in Android 13")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (Build.VERSION.SDK_INT >= 33) return
                    handleCharacteristicRead(peer.address, gatt, characteristic, status, characteristic.value)
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int
                ) {
                    handleCharacteristicRead(peer.address, gatt, characteristic, status, value)
                }
            }, BluetoothDevice.TRANSPORT_LE)
            connections[peer.address] = gatt
        } catch (e: SecurityException) {
            _active.value = _active.value - peer.address
            setResult(peer.address, AllianceGattResult(peer.address, "读取失败", error = "蓝牙权限不足"))
        } catch (e: Exception) {
            _active.value = _active.value - peer.address
            setResult(peer.address, AllianceGattResult(peer.address, "读取失败", error = e.message ?: "连接异常"))
        }
    }

    private fun handleCharacteristicRead(
        address: String,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
        value: ByteArray?
    ) {
        if (characteristic.uuid != statusUuid) return
        if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
            failAndClose(address, gatt, "读取状态失败（$status）")
            return
        }
        try {
            val json = JSONObject(String(value, Charsets.UTF_8))
            val result = AllianceGattResult(
                address = address,
                state = "读取完成",
                deviceState = json.opt("state")?.toString(),
                protocol = json.opt("catShare")?.toString(),
                crypto = json.opt("catShareCrypto")?.toString(),
                publicKeyPresent = json.optString("key").isNotBlank()
            )
            setResult(address, result)
            close(address, gatt)
        } catch (e: Exception) {
            failAndClose(address, gatt, "设备返回的数据不是有效状态 JSON")
        }
    }

    private fun setResult(address: String, result: AllianceGattResult) {
        _results.value = _results.value + (address to result)
    }

    @SuppressLint("MissingPermission")
    private fun close(address: String, gatt: BluetoothGatt) {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        connections.remove(address)
        _active.value = _active.value - address
    }

    private fun failAndClose(address: String, gatt: BluetoothGatt, message: String) {
        Log.w(TAG, "$address: $message")
        setResult(address, AllianceGattResult(address, "读取失败", error = message))
        close(address, gatt)
    }

    fun clear() {
        connections.toMap().forEach { (address, gatt) -> close(address, gatt) }
    }

    companion object {
        private const val TAG = "FastSendBleClient"
    }
}