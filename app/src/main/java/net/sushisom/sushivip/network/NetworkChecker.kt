package net.sushisom.sushivip.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

/**
 * 加载前的网络可用性前置校验。
 *
 * 只判断「是否存在一条已连接且具备互联网能力的链路」，不做实际连通性探测 ——
 * 内网环境下设备通常连着 Wi-Fi 但不出公网，如果去 ping 公网地址反而会误判。
 * 真正的服务端可达性由 WebView 的错误回调兜住（见 AppWebViewClient）。
 */
object NetworkChecker {

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService<ConnectivityManager>() ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false

        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }
}
