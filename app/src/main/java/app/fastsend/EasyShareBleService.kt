package app.fastsend

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.util.Arrays
import java.util.UUID

/**
 * FastSend's first EasyShare/Mutual Transfer Alliance BLE compatibility layer.
 *
 * This ports the public BLE UUIDs and advertisement layout used by EasyShare.
 * The P2P write callback intentionally does not pretend to complete EasyShare's
 * encrypted Wi-Fi Direct negotiation yet; that protocol requires the matching
 * P2pInfo encryption/session and HTTPS/WebSocket implementation.
 */
class EasyShareBleService : Service() {
    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val statusUuid = UUID.fromString("00009954-0000-1000-8000-00805f9b34fb")
    private val p2pUuid = UUID.fromString("00009953-0000-1000-8000-00805f9b34fb")
    private val serviceUuid = UUID.fromString("00009955-0000-1000-8000-00805f9b34fb")
    private val advServiceUuid = UUID.fromString("00003331-0000-1000-8000-008123456789")
    private val randomData: ByteArray = ByteArray(8).also(java.security.SecureRandom()::nextBytes)
    private val localBrandId by lazy { DeviceBrand.localBrandId().let { if (it == 114514) 114 else it } }
    private val deviceName by lazy { "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" }.take(64) }

    private fun statusJson(): ByteArray {
        val pub = EasyShareBleSecurity.encodedPublicKey()
        return JSONObject()
            .put("state", 0)
            .put("key", pub)
            .put("mac", "02:00:00:00:00:00")
            .put("catShare", 1)
            .put("catShareCrypto", 2)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    // The four GATT response calls below require BLUETOOTH_CONNECT on Android 12+.
    // Check the permission at the call site and handle revocation without crashing.
    private fun sendGattResponse(
        device: android.bluetooth.BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasBluetoothPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission missing; GATT response skipped")
            return
        }
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission revoked while sending GATT response", e)
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != statusUuid) {
                sendGattResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return
            }
            val bytes = statusJson()
            if (offset < 0 || offset > bytes.size) {
                sendGattResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset.coerceAtLeast(0), null)
            } else {
                sendGattResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes.copyOfRange(offset, bytes.size))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid != p2pUuid || offset != 0 || value.isEmpty() || value.size > 4096) {
                if (responseNeeded) {
                    sendGattResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                }
                return
            }

            // Acknowledge the GATT write, then parse/validate the payload asynchronously.
            if (responseNeeded) sendGattResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            val raw = runCatching { EasyShareP2pInfo.fromJson(value.toString(Charsets.UTF_8)) }.getOrElse {
                Log.w(TAG, "Invalid P2P JSON payload", it)
                broadcastNegotiation(device.address, false, "P2P 参数不是有效 JSON")
                return
            }

            serviceScope.launch {
                try {
                    val cipher = raw.key?.let { EasyShareBleSecurity.deriveCipher(it) }
                    val ssid = cipher?.decrypt(raw.ssid) ?: raw.ssid
                    val passphrase = cipher?.decrypt(raw.psk) ?: raw.psk
                    val ownerMac = cipher?.decrypt(raw.mac) ?: raw.mac
                    require(ssid.isNotBlank() && passphrase.isNotBlank() && ownerMac.isNotBlank()) {
                        "P2P 参数缺少 SSID、密码或 MAC"
                    }
                    require(raw.port in 1..65535) { "文件传输端口无效" }

                    // The sender creates the group and sends its actual credentials.
                    // This receiver requests to join that group; OEM support varies.
                    val wifi = EasyShareWifiDirect(this@EasyShareBleService)
                    wifi.joinGroup(ssid, passphrase, ownerMac)
                    broadcastNegotiation(
                        device.address,
                        true,
                        "已提交 Wi-Fi Direct 加入请求；文件传输服务尚未启动",
                        raw.port,
                        raw.id
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "P2P negotiation failed", e)
                    broadcastNegotiation(
                        device.address,
                        false,
                        e.message ?: "Wi-Fi Direct 协商失败"
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: android.bluetooth.BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "GATT peer ${device.address}: state=$newState status=$status")
        }
    }

    private fun hasBluetoothPermission(permission: String): Boolean {
        // Android 11 and below use install-time Bluetooth permissions.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return when (permission) {
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_SCAN ->
                    checkSelfPermission(android.Manifest.permission.BLUETOOTH) ==
                            PackageManager.PERMISSION_GRANTED
                else -> checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        if (!hasBluetoothPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ||
            !hasBluetoothPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
        ) {
            Log.w(TAG, "Bluetooth permissions have not been granted; BLE service will not start")
            stopSelf()
            return
        }
        try {
            val adapter: BluetoothAdapter = bluetoothManager.adapter
            check(adapter.isEnabled) { "请先开启蓝牙" }
            advertiser = adapter.bluetoothLeAdvertiser ?: error("设备不支持 BLE 广播")

            val status = BluetoothGattCharacteristic(
                statusUuid,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val p2p = BluetoothGattCharacteristic(
                p2pUuid,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(status)
                addCharacteristic(p2p)
            }
            gattServer = bluetoothManager.openGattServer(this, gattCallback)?.also {
                check(it.addService(service)) { "注册 GATT 服务失败" }
            } ?: error("无法打开 GATT Server")

            val brandUuid = UUID.fromString("000001%02x-0000-1000-8000-00805f9b34fb".format(localBrandId and 0xff))
            val advData = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(advServiceUuid))
                .addServiceData(ParcelUuid(brandUuid), Arrays.copyOfRange(randomData, 0, 6))
                .build()

            val nameBytes = deviceName.toByteArray(Charsets.UTF_8).let { if (it.size <= 16) it else it.copyOf(15) }
            val scanDataBytes = ByteArray(27)
            System.arraycopy(ByteArray(8), 0, scanDataBytes, 0, 8)
            scanDataBytes[8] = randomData[0]
            scanDataBytes[9] = randomData[1]
            System.arraycopy(nameBytes, 0, scanDataBytes, 10, minOf(nameBytes.size, 16))
            scanDataBytes[26] = 1
            val scanResponse = AdvertiseData.Builder()
                .addServiceData(ParcelUuid(UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")), scanDataBytes)
                .build()

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    Log.i(TAG, "Alliance BLE advertising started")
                }

                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "Alliance BLE advertising failed: $errorCode")
                }
            }
            advertiseCallback = callback
            advertiser?.startAdvertising(settings, advData, scanResponse, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start alliance BLE service", e)
            stopSelf()
        }
    }

    private fun broadcastNegotiation(
        address: String,
        success: Boolean,
        message: String,
        port: Int? = null,
        senderId: String? = null
    ) {
        val intent = Intent(ACTION_NEGOTIATION_RESULT).setPackage(packageName)
            .putExtra(EXTRA_ADDRESS, address)
            .putExtra(EXTRA_SUCCESS, success)
            .putExtra(EXTRA_MESSAGE, message)
        if (port != null) intent.putExtra(EXTRA_PORT, port)
        if (senderId != null) intent.putExtra(EXTRA_SENDER_ID, senderId)
        sendBroadcast(intent)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        serviceScope.cancel()
        if (hasBluetoothPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)) {
            advertiseCallback?.let { runCatching { advertiser?.stopAdvertising(it) } }
        }
        if (hasBluetoothPermission(android.Manifest.permission.BLUETOOTH_CONNECT)) {
            runCatching { gattServer?.close() }
        }
        gattServer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "FastSendAllianceBLE"
        const val ACTION_NEGOTIATION_RESULT = "app.fastsend.ACTION_EASYSHARE_NEGOTIATION_RESULT"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PORT = "port"
        const val EXTRA_SENDER_ID = "sender_id"
        fun startIntent(context: android.content.Context) = Intent(context, EasyShareBleService::class.java)
    }
}