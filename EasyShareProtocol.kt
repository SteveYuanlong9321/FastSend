package app.fastsend

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal EasyShare/CatShare-compatible BLE payload primitives.
 *
 * This file only implements the wire data and optional ECDH/AES-CTR credential
 * protection. It does not by itself establish a Wi-Fi Direct group or transfer files.
 */
data class EasyShareDeviceInfo(
    val state: Int,
    val key: String?,
    val mac: String,
    val catShare: Int? = null
) {
    companion object {
        fun fromJson(json: String): EasyShareDeviceInfo {
            val obj = JSONObject(json)
            return EasyShareDeviceInfo(
                state = obj.optInt("state", 0),
                key = obj.optString("key").takeIf { it.isNotBlank() && it != "null" },
                mac = obj.optString("mac", ""),
                catShare = if (obj.has("catShare") && !obj.isNull("catShare")) {
                    obj.optInt("catShare")
                } else null
            )
        }
    }
}

data class EasyShareP2pInfo(
    val id: String?,
    val ssid: String,
    val psk: String,
    val mac: String,
    val port: Int,
    val key: String? = null,
    val catShare: Int? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id ?: JSONObject.NULL)
        put("ssid", ssid)
        put("psk", psk)
        put("mac", mac)
        put("port", port)
        put("key", key ?: JSONObject.NULL)
        put("catShare", catShare ?: JSONObject.NULL)
    }.toString()

    companion object {
        fun fromJson(json: String): EasyShareP2pInfo {
            val obj = JSONObject(json)
            return EasyShareP2pInfo(
                id = obj.optString("id").takeIf { it.isNotBlank() && it != "null" },
                ssid = obj.optString("ssid"),
                psk = obj.optString("psk"),
                mac = obj.optString("mac"),
                port = obj.optInt("port"),
                key = obj.optString("key").takeIf { it.isNotBlank() && it != "null" },
                catShare = if (obj.has("catShare") && !obj.isNull("catShare")) {
                    obj.optInt("catShare")
                } else null
            )
        }
    }
}

object EasyShareBleSecurity {
    private val localKeyPair: KeyPair by lazy {
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }

    fun encodedPublicKey(): String =
        Base64.encodeToString(localKeyPair.public.encoded, Base64.NO_WRAP)

    fun deriveCipher(remoteEncodedPublicKey: String): SessionCipher {
        val remoteBytes = Base64.decode(remoteEncodedPublicKey, Base64.DEFAULT)
        val remotePublicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(remoteBytes))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localKeyPair.private as ECPrivateKey)
        agreement.doPhase(remotePublicKey, true)
        val secret = agreement.generateSecret("TlsPremasterSecret")
        return SessionCipher(SecretKeySpec(secret.encoded, "AES"))
    }

    class SessionCipher internal constructor(private val key: SecretKeySpec) {
        fun encrypt(value: String): String = crypt(Cipher.ENCRYPT_MODE, value)
        fun decrypt(value: String): String = crypt(Cipher.DECRYPT_MODE, value)

        private fun crypt(mode: Int, value: String): String {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            // Matches the fixed IV used by the reference implementation.
            cipher.init(
                mode,
                key,
                IvParameterSpec("0102030405060708".toByteArray(Charsets.UTF_8))
            )
            return if (mode == Cipher.ENCRYPT_MODE) {
                Base64.encodeToString(
                    cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
                    Base64.NO_WRAP
                )
            } else {
                String(
                    cipher.doFinal(Base64.decode(value, Base64.DEFAULT)),
                    Charsets.UTF_8
                )
            }
        }
    }
}