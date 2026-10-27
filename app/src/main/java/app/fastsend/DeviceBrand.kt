package app.fastsend

import android.os.Build

/**
 * First-stage FastSend brand identification, using EasyShare's documented brand-ID ranges.
 * This is app-level metadata for FastSend discovery; it is not the EasyShare BLE protocol yet.
 */
object DeviceBrand {
    private data class Brand(val id: Int, val name: String, val keys: List<String>)

    private val brands = listOf(
        Brand(11, "realme", listOf("realme")),
        Brand(10, "OPPO", listOf("oppo")),
        Brand(20, "vivo", listOf("vivo")),
        Brand(30, "Xiaomi", listOf("xiaomi", "redmi")),
        Brand(42, "OnePlus", listOf("oneplus")),
        Brand(50, "Meizu", listOf("meizu")),
        Brand(60, "Nubia", listOf("nubia")),
        Brand(70, "Samsung", listOf("samsung")),
        Brand(80, "ZTE", listOf("zte")),
        Brand(100, "Lenovo", listOf("lenovo")),
        Brand(110, "Motorola", listOf("motorola", "moto")),
        Brand(130, "Pixel", listOf("google", "pixel")),
        Brand(140, "Honor", listOf("honor")),
        Brand(160, "ROG", listOf("rog")),
        Brand(161, "ASUS", listOf("asus")),
        Brand(170, "Hisense", listOf("hisense")),
    )

    fun localBrandId(): Int = detectBrandId(Build.BRAND, Build.MANUFACTURER, Build.MODEL)

    fun detectBrandId(brand: String?, manufacturer: String?, model: String?): Int {
        val values = listOf(brand, manufacturer, model).map { it.orEmpty().lowercase() }
        return brands.firstOrNull { item -> item.keys.any { key -> values.any { it.contains(key) } } }?.id ?: 0
    }

    fun nameForId(id: Int): String = brands.firstOrNull { item ->
        when (item.id) {
            10 -> id in 10..19
            20 -> id in 20..29
            30 -> id in 30..39
            42 -> id in 41..45
            50 -> id in 50..59
            60 -> id in 60..69
            70 -> id in 70..75
            80 -> id in 80..89
            100 -> id in 100..109
            110 -> id in 110..119
            130 -> id in 130..139
            140 -> id in 140..149
            161 -> id in 161..169
            170 -> id in 170..179
            else -> id == item.id
        }
    }?.name ?: "Android"

    fun nameForDeviceName(deviceName: String): String {
        val inferred = detectBrandId(deviceName, deviceName, deviceName)
        return if (inferred == 0) "Android" else nameForId(inferred)
    }
}