package net.sushisom.sushivip.device

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.UUID

/**
 * 设备唯一标识提供者。
 *
 * 基准来源是 ANDROID_ID（64 位十六进制字符串）。但 ANDROID_ID 并非绝对可靠：
 *
 *  - Android 8.0 起，它是按「应用签名 + 用户 + 设备」派生的。同签名卸载重装
 *    不会变，但换签名会变 —— 意味着 **debug 包和 release 包拿到的 ID 不同**，
 *    联调时务必注意。
 *  - 恢复出厂设置、多用户切换、应用分身，都会得到不同的值。
 *  - 少数定制 ROM 会返回 null、空串，或已知的垃圾值 "9774d56d682e549c"
 *    （某批 Android 2.2 设备的著名 bug，至今仍有 ROM 在抄）。
 *
 * 因此这里做了一层兜底：首次取到的合法值会落盘缓存，之后优先读缓存；
 * 如果 ANDROID_ID 不可用，降级为随机 UUID 并同样落盘。
 * 这样保证 getDeviceId() 永远返回一个非空且在本次安装内稳定的值。
 */
object DeviceIdProvider {

    private const val TAG = "DeviceIdProvider"
    private const val PREFS_NAME = "device_identity"
    private const val KEY_DEVICE_ID = "device_id"

    /** 已知的无效 ANDROID_ID */
    private val INVALID_IDS = setOf(
        "9774d56d682e549c",
        "0000000000000000",
        "null",
        "unknown"
    )

    @Volatile
    private var cached: String? = null

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        cached?.let { return it }

        synchronized(this) {
            cached?.let { return it }

            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // 1) 优先读本地缓存：即便系统 ID 之后发生变化，同一次安装内保持稳定
            prefs.getString(KEY_DEVICE_ID, null)
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    cached = it
                    return it
                }

            // 2) 读取系统 ANDROID_ID
            val androidId = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
            }.getOrNull()

            val resolved = if (isValid(androidId)) {
                androidId!!.lowercase()
            } else {
                // 3) 降级：随机 UUID（去掉连字符，与 ANDROID_ID 保持相同的字符集形态）
                Log.w(TAG, "ANDROID_ID 不可用（值=$androidId），降级为随机 UUID")
                UUID.randomUUID().toString().replace("-", "")
            }

            prefs.edit().putString(KEY_DEVICE_ID, resolved).apply()
            cached = resolved
            return resolved
        }
    }

    private fun isValid(id: String?): Boolean =
        !id.isNullOrBlank() && id.lowercase() !in INVALID_IDS
}
