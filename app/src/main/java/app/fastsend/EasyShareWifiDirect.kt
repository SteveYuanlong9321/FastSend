package app.fastsend

import android.annotation.SuppressLint
import android.content.Context
import android.net.MacAddress
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wi-Fi Direct helpers for Android 10+.
 * Credentials are real only after the group owner has created a group and
 * requestGroupInfo() has returned its network name/passphrase.
 */
class EasyShareWifiDirect(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(appContext, Looper.getMainLooper(), null)

    data class GroupCredentials(
        val ssid: String,
        val passphrase: String,
        val ownerMac: String
    )

    @SuppressLint("MissingPermission")
    suspend fun discoverPeers() = suspendCancellableCoroutine<Unit> { c ->
        manager.discoverPeers(channel, listener(c, "Wi-Fi Direct 搜索失败"))
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: WifiP2pDevice) = suspendCancellableCoroutine<Unit> { c ->
        val config = WifiP2pConfig.Builder()
            .setDeviceAddress(MacAddress.fromString(device.deviceAddress))
            .build()
        manager.connect(channel, config, listener(c, "Wi-Fi Direct 连接请求失败"))
    }

    @SuppressLint("MissingPermission")
    suspend fun createGroup() = suspendCancellableCoroutine<Unit> { c ->
        manager.createGroup(channel, listener(c, "创建 Wi-Fi Direct 群组失败"))
    }

    @SuppressLint("MissingPermission")
    suspend fun removeGroup() = suspendCancellableCoroutine<Unit> { c ->
        manager.removeGroup(channel, listener(c, "移除 Wi-Fi Direct 群组失败"))
    }

    @SuppressLint("MissingPermission")
    suspend fun requestConnectionInfo(): android.net.wifi.p2p.WifiP2pInfo =
        suspendCancellableCoroutine { c ->
            manager.requestConnectionInfo(channel) { info -> if (c.isActive) c.resume(info) }
        }

    @SuppressLint("MissingPermission")
    suspend fun requestPeers(): List<WifiP2pDevice> =
        suspendCancellableCoroutine { c ->
            manager.requestPeers(channel) { peers -> if (c.isActive) c.resume(peers.deviceList.toList()) }
        }

    @SuppressLint("MissingPermission")
    suspend fun requestGroupInfo(): WifiP2pGroup? =
        suspendCancellableCoroutine { c ->
            manager.requestGroupInfo(channel) { group -> if (c.isActive) c.resume(group) }
        }

    /**
     * Returns credentials only if Android exposes a group passphrase to this app.
     * Android/OEM restrictions can leave the passphrase unavailable.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentGroupCredentials(): GroupCredentials {
        val group = requestGroupInfo() ?: error("当前设备尚未创建或加入 Wi-Fi Direct 群组")
        val ssid = group.networkName?.takeIf { it.isNotBlank() }
            ?: error("系统未提供 Wi-Fi Direct SSID")
        val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
            ?: error("系统未向应用提供 Wi-Fi Direct 密码；无法安全发送协商参数")
        val mac = group.owner?.deviceAddress?.takeIf { it.isNotBlank() }
            ?: error("系统未提供群组所有者 MAC")
        return GroupCredentials(ssid, passphrase, mac)
    }

    /**
     * Android 10+ supports setting a Wi-Fi Direct group network name/passphrase.
     * This is the receiver-side join request. It may be rejected by OEM firmware.
     */
    @SuppressLint("MissingPermission")
    suspend fun joinGroup(ssid: String, passphrase: String, ownerMac: String) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "通过 SSID/密码加入 Wi-Fi Direct 群组需要 Android 10 或更高版本"
        }
        require(ssid.isNotBlank() && passphrase.isNotBlank() && ownerMac.isNotBlank()) {
            "Wi-Fi Direct 参数不完整"
        }
        val config = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(passphrase)
            .setDeviceAddress(MacAddress.fromString(ownerMac))
            .build()
        suspendCancellableCoroutine<Unit> { c ->
            manager.connect(channel, config, listener(c, "加入 Wi-Fi Direct 群组失败"))
        }
    }

    private fun listener(
        c: kotlinx.coroutines.CancellableContinuation<Unit>,
        prefix: String
    ) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { if (c.isActive) c.resume(Unit) }
        override fun onFailure(reason: Int) {
            if (c.isActive) c.resumeWithException(IllegalStateException("$prefix，错误码：$reason"))
        }
    }

    @SuppressLint("MissingPermission")
    fun cancelConnect(onResult: (Boolean, Int) -> Unit = { _, _ -> }) {
        manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onResult(true, 0)
            override fun onFailure(reason: Int) = onResult(false, reason)
        })
    }
}