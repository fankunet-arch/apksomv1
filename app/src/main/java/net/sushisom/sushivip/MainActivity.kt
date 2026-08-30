package net.sushisom.sushivip

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import net.sushisom.sushivip.bridge.AppBridge
import net.sushisom.sushivip.databinding.ActivityMainBinding
import net.sushisom.sushivip.network.ConnectivityDiagnostics
import net.sushisom.sushivip.network.HostsProxyServer
import net.sushisom.sushivip.network.NetworkChecker
import net.sushisom.sushivip.web.AppWebChromeClient
import net.sushisom.sushivip.web.AppWebViewClient
import net.sushisom.sushivip.web.FileChooserDelegate
import org.json.JSONObject
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val ERROR_PAGE = "file:///android_asset/error.html"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileChooserDelegate: FileChooserDelegate

    /** 应用内置 hosts 用的本地代理，未启用内置映射时为 null */
    private var hostsProxy: HostsProxyServer? = null

    /** 网页发起的相机请求，等 Android 运行时权限结果出来后再决定 grant/deny */
    private var pendingWebPermissionRequest: PermissionRequest? = null

    /** 非网页发起的相机权限请求（拍照上传路径）的结果回调 */
    private var pendingCameraPermissionCallback: ((Boolean) -> Unit)? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onCameraPermissionResult(granted) }

    // -----------------------------------------------------------------------
    // 生命周期
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableImmersiveMode()

        fileChooserDelegate = FileChooserDelegate(this) { onResult ->
            requestCameraPermission(onResult)
        }

        setupWebView()
        setupBackNavigation()

        // 内置 hosts 生效后再加载，否则首次请求会赶在代理生效之前发出。
        startHostsProxyThenLoad()
    }

    /**
     * WebView 的 Cookie 是攒一批再定期落盘的，进程被系统回收时最近一次写入
     * 可能还停留在内存里。登录态是一个 12 小时的会话 Cookie，这里主动落一次盘，
     * 避免用户把应用划走之后回来还要重新登录。
     */
    /**
     * WebView 的 Cookie 默认只在内存里，要 flush() 才写入磁盘。
     * 熄屏或切后台之后进程随时可能被系统回收，不落盘的话这次会话的 Cookie
     * 就没了 —— 现场表现就是「熄屏一会儿再打开要求重新登录」。
     *
     * onPause 与 onStop 都做一次：onPause 一定会被调用，onStop 覆盖
     * 「切到后台后才被回收」的场景。flush() 是同步的，API 21+ 起取代了
     * 已废弃的 CookieSyncManager。
     */
    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
    }

    /**
     * 启用内置 hosts：拉起本地 CONNECT 代理并让 WebView 走它，成功后再加载页面。
     *
     * setProxyOverride 是**异步**的，必须等回调到达才能 loadUrl —— 否则首次
     * 请求会在代理生效前发出，仍旧走设备 DNS，白白失败一次。
     *
     * 任一环节不可用（未配置映射、WebView 不支持代理覆盖、端口起不来）都直接
     * 退回普通加载，不让这个增强手段本身变成新的故障点。
     */
    private fun startHostsProxyThenLoad() {
        val mapping = parseHostsOverride(BuildConfig.HOSTS_OVERRIDE)
        if (mapping.isEmpty()) {
            Log.i(TAG, "未配置内置 hosts，按常规方式加载")
            loadTargetUrlOrPromptNoNetwork()
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "当前 WebView 不支持代理覆盖，内置 hosts 无法生效")
            loadTargetUrlOrPromptNoNetwork()
            return
        }

        val proxy = HostsProxyServer(mapping)
        val port = proxy.start()
        if (port <= 0) {
            loadTargetUrlOrPromptNoNetwork()
            return
        }
        hostsProxy = proxy

        val config = ProxyConfig.Builder()
            .addProxyRule("127.0.0.1:$port")
            .addDirect()   // 代理不可用时退回直连，不至于彻底断网
            .build()

        ProxyController.getInstance().setProxyOverride(
            config,
            ContextCompat.getMainExecutor(this)
        ) {
            Log.i(TAG, "内置 hosts 已生效: $mapping")
            loadTargetUrlOrPromptNoNetwork()
        }
    }

    /** 解析 "域名=IP,域名=IP" 形式的配置 */
    private fun parseHostsOverride(spec: String): Map<String, String> =
        spec.split(',')
            .mapNotNull { entry ->
                val kv = entry.split('=')
                if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) {
                    kv[0].trim().lowercase() to kv[1].trim()
                } else {
                    null
                }
            }
            .toMap()

    override fun onDestroy() {
        hostsProxy?.stop()
        hostsProxy = null
        // 悬空的文件选择回调要结掉，否则 WebView 内部状态残留
        if (::fileChooserDelegate.isInitialized) fileChooserDelegate.cancelPending()
        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = null

        // WebView 必须先从视图树摘除再 destroy，否则部分机型会泄漏 Activity
        if (::binding.isInitialized) binding.webView.let { web ->
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.stopLoading()
            web.removeJavascriptInterface(AppBridge.NAME)
            web.destroy()
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 用户上滑呼出系统栏后，重新获得焦点时再藏回去
        if (hasFocus) enableImmersiveMode()
    }

    // -----------------------------------------------------------------------
    // 窗口与显示（需求 3.1）
    // -----------------------------------------------------------------------

    /** 沉浸式全屏：隐藏状态栏与导航栏，允许用户上滑临时呼出 */
    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // 【刻意不加 FLAG_KEEP_SCREEN_ON】
    // 门店要求平板正常息屏省电。会话要能扛住息屏与进程回收，靠的是
    // onPause/onStop 里把 Cookie 落盘（见下），而不是靠不息屏来回避问题。
    // 曾经加过这个标志，与门店要求冲突，已移除，不要再加回来。

    // -----------------------------------------------------------------------
    // WebView 配置（需求 3.2 / 3.3 / 3.4）
    // -----------------------------------------------------------------------

    private fun setupWebView() {
        val web = binding.webView

        web.settings.apply {
            // 现代前端框架的基本盘
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            // HTTPS 页面里加载 HTTP 子资源时不被拦截。
            // 注意：这解决的是「混合内容」，跨域(CORS)由服务端响应头决定，
            // 客户端没有任何开关能绕过，详见 README。
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // 关键：不加这行，getUserMedia 拿到的视频流无法自动播放，
            // 扫码取景框会是一片黑，且不会有任何报错。
            mediaPlaybackRequiresUserGesture = false

            // 适配桌面端布局的页面
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            cacheMode = WebSettings.LOAD_DEFAULT

            // 收紧本地文件访问：页面没有读取本地文件的需求，关掉减少攻击面。
            // 内置错误页走 file:///android_asset/，不受这两项影响。
            allowFileAccess = false
            allowContentAccess = false

            // 便于服务端识别来自本容器的请求
            userAgentString = "$userAgentString SushiVIP/${BuildConfig.VERSION_NAME}"
        }

        // 持久化 Cookie，保持登录态；三方 Cookie 放开以防 LMS 用了子域名鉴权
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        // 需求 3.4：注入 JS 桥接。对象名与方法名由前端契约固定，不要改。
        web.addJavascriptInterface(AppBridge(applicationContext), AppBridge.NAME)

        web.webViewClient = AppWebViewClient(
            onPageLoaded = { hideLoading() },
            onLoadError = { code, description, url -> showErrorPage(code, description, url) },
            onExternalUrl = { uri -> openExternally(uri) },
            onRetryRequested = { reload() }
        )

        web.webChromeClient = AppWebChromeClient(
            onCameraPermissionNeeded = { request -> handleWebCameraRequest(request) },
            onProgress = { progress -> if (progress >= 100) hideLoading() },
            onFileChooser = { callback, params ->
                fileChooserDelegate.onShowFileChooser(callback, params)
            }
        )

        if (BuildConfig.DEBUG) {
            // 允许 chrome://inspect 调试内网页面，仅 debug 包开启
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    // -----------------------------------------------------------------------
    // 相机权限（需求 3.3）
    // -----------------------------------------------------------------------

    /**
     * 网页通过 getUserMedia 请求相机。
     * 两层权限：Android 运行时 CAMERA 权限 + WebView 的 PermissionRequest，
     * 前者没拿到就 grant 后者是无效的。
     */
    private fun handleWebCameraRequest(request: PermissionRequest) {
        if (hasCameraPermission()) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            return
        }
        pendingWebPermissionRequest = request
        requestCameraPermission(null)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * @param callback 非空表示这是拍照上传路径发起的请求，结果直接回调；
     *                 为空表示是网页 getUserMedia 路径，结果用于决定
     *                 pendingWebPermissionRequest 的 grant/deny。
     */
    private fun requestCameraPermission(callback: ((Boolean) -> Unit)?) {
        if (hasCameraPermission()) {
            callback?.invoke(true)
            return
        }
        pendingCameraPermissionCallback = callback
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun onCameraPermissionResult(granted: Boolean) {
        // 1) 网页 getUserMedia 路径
        pendingWebPermissionRequest?.let { request ->
            if (granted) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            } else {
                request.deny()
            }
            pendingWebPermissionRequest = null
        }

        // 2) 拍照上传路径
        pendingCameraPermissionCallback?.invoke(granted)
        pendingCameraPermissionCallback = null

        if (!granted) {
            // 用户勾了「不再询问」时，系统对话框不会再弹出，
            // 此时 shouldShowRequestPermissionRationale 返回 false，
            // 唯一出路是引导用户去设置页手动开启。
            val permanentlyDenied =
                !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            if (permanentlyDenied) showGoToSettingsDialog()
            else Toast.makeText(this, R.string.toast_camera_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showGoToSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_camera_title)
            .setMessage(R.string.dialog_camera_message)
            .setCancelable(true)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_settings) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                }.onFailure { Log.e(TAG, "无法打开应用设置页", it) }
            }
            .show()
    }

    // -----------------------------------------------------------------------
    // 加载、网络与错误（需求 3.5）
    // -----------------------------------------------------------------------

    private fun loadTargetUrlOrPromptNoNetwork() {
        if (!NetworkChecker.isNetworkAvailable(this)) {
            showNoNetworkDialog()
            return
        }
        showLoading()
        binding.webView.loadUrl(BuildConfig.BASE_URL)
    }

    private fun showNoNetworkDialog() {
        hideLoading()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_no_network_title)
            .setMessage(R.string.dialog_no_network_message)
            .setCancelable(false)
            .setPositiveButton(R.string.action_retry) { _, _ -> loadTargetUrlOrPromptNoNetwork() }
            .setNegativeButton(R.string.action_exit) { _, _ -> finish() }
            .show()
    }

    /**
     * 需求 3.5：绝不能让 WebView 显示内核自带的错误页。
     * 这里加载打包在 assets 里的极简错误页。
     *
     * 【只传错误码，不传地址和文字说明】
     * 容器的意义之一就是让使用者感知不到背后是个网站。错误页上一旦出现
     * 内网地址，套壳就白做了；具体成因也不该暴露给门店人员。
     * 所以界面上只留一个编号，编号到成因的对照表放在
     * doc/执行说明.md 的故障速查表里，由运维查阅。
     *
     * 完整信息（含地址与原因）只写进 logcat，用
     *   adb logcat -s MainActivity AppWebViewClient Diagnostics
     * 取用。
     */
    private fun showErrorPage(code: Int, description: String, failingUrl: String) {
        hideLoading()
        Log.w(TAG, "加载失败 code=$code reason=$description url=$failingUrl")

        val url = buildString {
            append(ERROR_PAGE)
            append("?code=").append(code)
            // debug 包附带详情便于联调；release 包一律不带
            if (BuildConfig.DEBUG) {
                append("&detail=").append(URLEncoder.encode("$description\n$failingUrl", "UTF-8"))
            }
        }
        binding.webView.loadUrl(url)
        runDiagnostics(failingUrl)
    }

    /**
     * 错误页展示后，在子线程做一次网络自助诊断，把结论回填到页面上。
     *
     * 这样现场无需连电脑抓 adb，就能当场区分「只是 DNS 解析不了」和
     * 「本应用根本没有网络」——两者的错误码相同，处理方式却完全相反。
     */
    private fun runDiagnostics(failingUrl: String) {
        val target = failingUrl.ifBlank { BuildConfig.BASE_URL }
        Thread {
            val report = ConnectivityDiagnostics.describe(
                target, BuildConfig.FALLBACK_IP, BuildConfig.HOSTS_OVERRIDE
            )
            Log.i(TAG, "网络诊断结果:\n$report")
            // 诊断结论含域名与内网 IP，只允许出现在 debug 包的界面上。
            // release 包仅写日志，由运维通过 adb logcat -s Diagnostics 取用。
            if (!BuildConfig.DEBUG) return@Thread
            runOnUiThread {
                // 本地错误页加载极快，这里留一点余量确保 __setDiag 已定义
                binding.webView.postDelayed({
                    binding.webView.evaluateJavascript(
                        "window.__setDiag && window.__setDiag(${JSONObject.quote(report)})",
                        null
                    )
                }, 200)
            }
        }.start()
    }

    private fun reload() {
        if (!NetworkChecker.isNetworkAvailable(this)) {
            showNoNetworkDialog()
            return
        }
        showLoading()
        binding.webView.loadUrl(BuildConfig.BASE_URL)
    }

    private fun showLoading() {
        binding.loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        if (binding.loadingOverlay.visibility != View.GONE) {
            binding.loadingOverlay.visibility = View.GONE
        }
    }

    // -----------------------------------------------------------------------
    // 站外链接
    // -----------------------------------------------------------------------

    /**
     * tel: / mailto: / weixin:// / alipays:// 以及站外 http(s) 链接交给系统。
     * 必须 try/catch：设备上没装对应 App 时 startActivity 会抛
     * ActivityNotFoundException，不接住就是一次崩溃。
     */
    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "没有应用可以处理 $uri", e)
            Toast.makeText(this, R.string.toast_cannot_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    // -----------------------------------------------------------------------
    // 返回键（需求 3.6）
    // -----------------------------------------------------------------------

    /**
     * 用 OnBackPressedDispatcher 而非重写 onBackPressed()：后者在
     * Android 13+ 的预测式返回手势下已废弃，行为不可靠。
     */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                showExitConfirmDialog()
            }
        }
    }

    private fun showExitConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_exit_title)
            .setMessage(R.string.dialog_exit_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm_exit) { _, _ -> finish() }
            .show()
    }
}
