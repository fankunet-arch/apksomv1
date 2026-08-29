package net.sushisom.sushivip.network

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * 加载失败后的自助诊断。
 *
 * 目的：把「域名解析不了」和「根本没有网络」这两种表现相同、成因完全不同的
 * 故障当场区分开，不需要连电脑抓 adb。
 *
 * 三个探测都在应用自己的进程里做，因此结果反映的正是 WebView 所处的网络环境：
 *   1. 能否解析域名          —— 走的是与 WebView 相同的系统 resolver
 *   2. 解析出的地址能否连通   —— 排除「解析到了但服务不通」
 *   3. IP 直连能否连通        —— 完全不经过 DNS，用于反证网络本身是通的
 *
 * 必须在子线程调用（内含阻塞式 socket 连接）。
 */
object ConnectivityDiagnostics {

    private const val TAG = "Diagnostics"
    private const val TIMEOUT_MS = 3000
    private const val HTTPS_PORT = 443

    fun describe(targetUrl: String, fallbackIp: String, hostsOverride: String = ""): String {
        val host = runCatching { URI(targetUrl).host }.getOrNull()
            ?: return "无法解析目标地址格式：$targetUrl"

        val lines = mutableListOf<String>()
        if (hostsOverride.isNotBlank()) lines += "内置 hosts：$hostsOverride"


        // 1) 域名解析
        val addresses = runCatching {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
        }.getOrNull()?.takeIf { it.isNotEmpty() }

        lines += if (addresses != null) {
            "域名解析：成功 → ${addresses.joinToString(", ")}"
        } else {
            "域名解析：失败（本应用无法解析 $host）"
        }

        // 2) 解析结果是否可达
        val resolvedReachable = addresses?.first()?.let { canConnect(it) }
        if (resolvedReachable != null) {
            lines += "连接 ${addresses.first()}:$HTTPS_PORT：${yesNo(resolvedReachable)}"
        }

        // 3) IP 直连（完全绕过 DNS）
        val ipReachable = canConnect(fallbackIp)
        lines += "直连 $fallbackIp:$HTTPS_PORT：${yesNo(ipReachable)}"

        lines += "————"
        lines += when {
            addresses == null && ipReachable ->
                "结论：网络是通的，只有 DNS 解析不了。改用 IP 地址即可绕开。"
            addresses == null && !ipReachable ->
                "结论：本应用完全没有网络（解析和直连都失败）。" +
                    "请检查 Wi-Fi 连接、VPN、以及 Wi-Fi 的代理设置。"
            resolvedReachable == true ->
                "结论：解析和连接都正常，故障可能在代理或 TLS 层，请查看日志。"
            else ->
                "结论：能解析但连不上服务器，请检查服务端与防火墙。"
        }

        return lines.joinToString("\n")
    }

    private fun yesNo(ok: Boolean) = if (ok) "通" else "不通"

    private fun canConnect(ip: String): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress(ip, HTTPS_PORT), TIMEOUT_MS)
            true
        }
    } catch (e: IOException) {
        Log.w(TAG, "连接 $ip:$HTTPS_PORT 失败: ${e.message}")
        false
    } catch (e: SecurityException) {
        // 缺少 INTERNET 权限时会走到这里
        Log.e(TAG, "连接被安全策略拒绝", e)
        false
    }
}
