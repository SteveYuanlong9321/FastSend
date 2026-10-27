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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

data class EasyShareNegotiationState(
    val address: String,
    val message: String,
    val success: Boolean = false
)

/**
 * Sends the EasyShare P2P negotiation payload over the public GATT P2P characteristic.
 *
 * Caller must supply already-established local Wi-Fi Direct group credentials.
 * This class does not create a Wi-Fi Direct group or start the HTTPS/WebSocket receiver.
 */
class EasyShareBleNegotiator(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val _state = MutableStateFlow<EasyShareNegotiationState?>(null)
    val state: StateFlow<EasyShareNegotiationState?> = _state.asStateFlow()

    private val serviceUuid = UUID.fromString("00009955-0000-1000-8000-00805f9b34fb")
    private val statusUuid = UUID.fromString("00009954-0000-1000-8000-00805f9b34fb")
    private val p2pUuid = UUID.fromString("00009953-0000-1000-8000-00805f9b34fb")

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Attempts the BLE handshake after the caller has configured its own Wi-Fi Direct group.
     * The MAC/SSID/PSK must be the real credentials of that group, never placeholders.
     */
    @SuppressLint("MissingPermission")
    fun negotiate(
        peer: AllianceBlePeer,
        ssid: String,
        psk: String,
        localP2pMac: String,
        port: Int,
        senderId: String
    ) {
        if (!hasConnectPermission()) {
            publish(peer.address, "缺少蓝牙连接权限")
            return
        }
        if (port !in 1..65535 || ssid.isBlank() || psk.isBlank() || localP2pMac.isBlank()) {
            publish(peer.address, "Wi-Fi Direct 参数不完整；未发送协商数据")
            return
        }

        val device = runCatching { manager.adapter?.getRemoteDevice(peer.address) }.getOrNull()
        if (device == null) {
            publish(peer.address, "无法取得蓝牙设备")
            return
        }
        publish(peer.address, "正在连接并读取对端状态")

        try {
            device.connectGatt(appContext, false, object : BluetoothGattCallback() {
                private var remoteInfo: EasyShareDeviceInfo? = null
                private var sent = false

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(gatt, peer.address, "GATT 连接失败（$status）")
                        return
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        @SuppressLint("MissingPermission")
                        val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                        if (!started) finish(gatt, peer.address, "无法发现 GATT 服务")
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (!sent) finish(gatt, peer.address, "设备在协商完成前断开")
                        else close(gatt)
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(gatt, peer.address, "发现 GATT 服务失败")
                        return
                    }
                    val statusChar = gatt.getService(serviceUuid)?.getCharacteristic(statusUuid)
                    if (statusChar == null) {
                        finish(gatt, peer.address, "对端没有互传联盟状态特征")
                        return
                    }
                    @SuppressLint("MissingPermission")
                    val started = runCatching { gatt.readCharacteristic(statusChar) }.getOrDefault(false)
                    if (!started) finish(gatt, peer.address, "无法读取对端状态")
                }

                @Deprecated("Deprecated in Android 13")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (Build.VERSION.SDK_INT >= 33) return
                    onStatusRead(gatt, characteristic, characteristic.value, status)
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int
                ) = onStatusRead(gatt, characteristic, value, status)

                private fun onStatusRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray?,
                    status: Int
                ) {
                    if (characteristic.uuid != statusUuid) return
                    if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
                        finish(gatt, peer.address, "读取对端状态失败")
                        return
                    }
                    val info = runCatching {
                        EasyShareDeviceInfo.fromJson(String(value, Charsets.UTF_8))
                    }.getOrElse {
                        finish(gatt, peer.address, "对端状态 JSON 无法解析")
                        return
                    }
                    remoteInfo = info
                    val p2pChar = gatt.getService(serviceUuid)?.getCharacteristic(p2pUuid)
                    if (p2pChar == null) {
                        finish(gatt, peer.address, "对端没有 P2P 协商特征")
                        return
                    }

                    val cipher = info.key?.let {
                        runCatching { EasyShareBleSecurity.deriveCipher(it) }.getOrElse { err ->
                            finish(gatt, peer.address, "建立 ECDH 会话失败：${err.message ?: "未知错误"}")
                            return
                        }
                    }

                    val payload = EasyShareP2pInfo(
                        id = senderId,
                        ssid = cipher?.encrypt(ssid) ?: ssid,
                        psk = cipher?.encrypt(psk) ?: psk,
                        mac = cipher?.encrypt(localP2pMac) ?: localP2pMac,
                        port = port,
                        key = if (cipher != null) EasyShareBleSecurity.encodedPublicKey() else null,
                        catShare = 1
                    ).toJson().toByteArray(Charsets.UTF_8)

                    // Legacy GATT write API for broad Android compatibility.
                    @SuppressLint("MissingPermission")
                    val started = try {
                        p2pChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        p2pChar.value = payload
                        gatt.writeCharacteristic(p2pChar)
                    } catch (_: Exception) {
                        false
                    }
                    if (!started) {
                        finish(gatt, peer.address, "发送 P2P 参数失败；可能需要分包/MTU 协商")
                    } else {
                        sent = true
                        publish(peer.address, "P2P 参数已提交至 BLE（尚未确认 Wi-Fi Direct 已连接）", true)
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (characteristic.uuid != p2pUuid) return
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        publish(peer.address, "BLE 协商数据写入成功；等待 Wi-Fi Direct 后续流程", true)
                    } else {
                        finish(gatt, peer.address, "对端拒绝 P2P 参数（GATT $status）")
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            publish(peer.address, "蓝牙权限不足")
        } catch (e: Exception) {
            publish(peer.address, "连接失败：${e.message ?: "未知错误"}")
        }
    }

    private fun publish(address: String, message: String, success: Boolean = false) {
        _state.value = EasyShareNegotiationState(address, message, success)
    }

    @SuppressLint("MissingPermission")
    private fun finish(gatt: BluetoothGatt, address: String, message: String) {
        publish(address, message)
        close(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun close(gatt: BluetoothGatt) {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }
}