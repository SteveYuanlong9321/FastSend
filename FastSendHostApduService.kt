package app.fastsend

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * FastSend's own NFC HCE endpoint.
 * Returns the same FASTSEND1 payload that the NFC reader and QR pairing use.
 */
class FastSendHostApduService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || !isSelectAid(commandApdu)) return SW_NOT_FOUND

        val payload = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_TOKEN, null)
            ?.takeIf(::isValidPairingPayload)
            ?: return SW_NOT_FOUND

        return payload.toByteArray(Charsets.UTF_8) + SW_OK
    }

    override fun onDeactivated(reason: Int) = Unit

    private fun isValidPairingPayload(payload: String): Boolean {
        val parts = payload.split("|", limit = 4)
        if (parts.size != 4 || parts[0] != "FASTSEND1") return false

        val octets = parts[1].split(".")
        val validIp = octets.size == 4 &&
                octets.all { it.toIntOrNull()?.let { value -> value in 0..255 } == true } &&
                parts[1] != "0.0.0.0"

        val port = parts[2].toIntOrNull()
        return validIp && port != null && port in 1..65535 && parts[3].isNotBlank()
    }

    private fun isSelectAid(apdu: ByteArray): Boolean {
        // ISO-DEP SELECT by AID: 00 A4 04 00 Lc AID [Le]
        if (apdu.size < 5 ||
            apdu[0] != 0x00.toByte() ||
            apdu[1] != 0xA4.toByte() ||
            apdu[2] != 0x04.toByte() ||
            apdu[3] != 0x00.toByte()
        ) return false

        val aidLength = apdu[4].toInt() and 0xFF
        if (aidLength != AID.size || apdu.size < 5 + aidLength) return false
        return apdu.copyOfRange(5, 5 + aidLength).contentEquals(AID)
    }

    companion object {
        const val PREFS_NAME = "fastsend_pairing"
        // MainActivity already stores the FASTSEND1 payload under this key.
        const val PREF_TOKEN = "pairing_token"

        // Private FastSend AID: F053454E445631
        val AID = byteArrayOf(
            0xF0.toByte(), 0x53, 0x45, 0x4E, 0x44, 0x56, 0x31
        )

        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    }
}