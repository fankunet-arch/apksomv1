package net.sushisom.sushivip.web

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import net.sushisom.sushivip.BuildConfig

/**
 * 负责导航控制与错误拦截。
 *
 * @param onPageLoaded         页面加载完成（用于收起加载动画）
 * @param onLoadError          主框架加载失败，回调错误码与描述
 * @param onExternalUrl        需要交给系统处理的站外链接 / 非 http scheme
 * @param onRetryRequested     内置错误页里点了「重试」
 */
class AppWebViewClient(
    private val onPageLoaded: () -> Unit,
    private val onLoadError: (code: Int, description: String, failingUrl: String) -> Unit,
    private val onExternalUrl: (Uri) -> Unit,
    private val onRetryRequested: () -> Unit
) : WebViewClient() {

    companion object {
        private const val TAG = "AppWebViewClient"

        /** 内置错误页里「重试」按钮使用的自定义 scheme */
        const val SCHEME_INTERNAL = "sushivip"
        private const val ACTION_RETRY = "retry"

        private val WHITELIST: Set<String> = BuildConfig.HOST_WHITELIST.toSet()
    }

    /**
     * 出现过加载错误。用于抑制「错误页加载完成」也触发 onPageFinished 的成功语义，
     * 并在下一次真正发起导航时复位。
     */
    private var hasError = false

    fun isHostAllowed(host: String?): Boolean = host != null && host in WHITELIST

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        val uri = request.url
        val scheme = uri.scheme?.lowercase()

        // 1) 内置错误页的「重试」
        if (scheme == SCHEME_INTERNAL) {
            if (uri.host == ACTION_RETRY || uri.schemeSpecificPart?.trimStart('/') == ACTION_RETRY) {
                onRetryRequested()
            }
            return true
        }

        // 2) 非 http(s) 的 scheme（tel: mailto: weixin:// alipays:// intent: 等）
        //    一律交给系统。注意必须由 Activity 侧 try/catch，设备上没装对应
        //    App 时直接 startActivity 会抛 ActivityNotFoundException 导致崩溃。
        if (scheme != "http" && scheme != "https") {
            onExternalUrl(uri)
            return true
        }

        // 3) 站外的 http(s) 链接：不留在容器内，转交系统浏览器。
        //    这同时是 JS Bridge 的一道防线 —— AppBridge 不会出现在站外页面上。
        if (request.isForMainFrame && !isHostAllowed(uri.host)) {
            Log.i(TAG, "站外链接转交系统浏览器: $uri")
            onExternalUrl(uri)
            return true
        }

        return false
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // 只有在导航到真实页面（而非我们自己的本地错误页）时才复位错误标记
        if (url != null && !url.startsWith("file:///android_asset/")) {
            hasError = false
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (!hasError) onPageLoaded()
    }

    /**
     * 网络层错误：DNS 解析失败、连接超时、连接被拒等。
     *
     * 关键：必须判断 isForMainFrame。页面里任何一张图片、一个埋点请求失败
     * 都会触发这个回调，不加判断会导致主页面明明正常显示却弹出错误页。
     */
    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (!request.isForMainFrame) return
        hasError = true
        val code = error.errorCode
        Log.w(TAG, "主框架加载失败 code=$code desc=${error.description} url=${request.url}")
        onLoadError(code, describeError(code, error.description?.toString()), request.url.toString())
    }

    /**
     * HTTP 层错误：404 / 500 等。同样只处理主框架。
     */
    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        if (!request.isForMainFrame) return
        hasError = true
        val code = errorResponse.statusCode
        Log.w(TAG, "主框架 HTTP 错误 $code url=${request.url}")
        onLoadError(code, "服务器返回 HTTP $code", request.url.toString())
    }

    /**
     * SSL 证书错误。
     *
     * 这里**故意不调用 handler.proceed()**。内网站点的自签证书是通过
     * network_security_config 里的 trust-anchors 正规信任的，正常情况下
     * 根本不会走到这个回调。一旦走到，说明证书确实有问题（过期、域名不匹配、
     * 或者 res/raw/intranet_ca.crt 没更新），此时无条件放行等于把 TLS 关掉，
     * 任何人都能中间人。所以直接拒绝并展示错误页，让问题暴露出来。
     */
    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError
    ) {
        handler.cancel()
        hasError = true
        val reason = when (error.primaryError) {
            SslError.SSL_EXPIRED -> "证书已过期"
            SslError.SSL_IDMISMATCH -> "证书域名不匹配"
            SslError.SSL_NOTYETVALID -> "证书尚未生效（请检查设备日期）"
            SslError.SSL_UNTRUSTED -> "证书不受信任（APK 内置的 CA 可能已失效）"
            SslError.SSL_DATE_INVALID -> "证书日期无效"
            else -> "证书校验失败"
        }
        Log.e(TAG, "SSL 错误: $reason url=${error.url}")
        onLoadError(-1000, "HTTPS $reason", error.url ?: "")
    }

    private fun describeError(code: Int, fallback: String?): String = when (code) {
        ERROR_HOST_LOOKUP -> "无法解析服务器地址"
        ERROR_CONNECT -> "无法连接到服务器"
        ERROR_TIMEOUT -> "连接服务器超时"
        ERROR_IO -> "网络读写失败"
        ERROR_UNSUPPORTED_SCHEME -> "不支持的地址格式"
        ERROR_FAILED_SSL_HANDSHAKE -> "HTTPS 握手失败"
        else -> fallback ?: "加载失败"
    }
}
