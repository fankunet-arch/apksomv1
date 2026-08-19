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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import net.sushisom.sushivip.bridge.AppBridge
import net.sushisom.sushivip.databinding.ActivityMainBinding
import net.sushisom.sushivip.network.NetworkChecker
import net.sushisom.sushivip.web.AppWebChromeClient
import net.sushisom.sushivip.web.AppWebViewClient
import net.sushisom.sushivip.web.FileChooserDelegate
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val ERROR_PAGE = "file:///android_asset/error.html"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileChooserDelegate: FileChooserDelegate

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
        keepScreenOn()

        fileChooserDelegate = FileChooserDelegate(this) { onResult ->
            requestCameraPermission(onResult)
        }

        setupWebView()
        setupBackNavigation()

        // 需求 3.5：加载前先做网络前置校验，无网络直接阻断，不让 WebView
        // 去撞一个必然失败的请求（那样会先白屏几秒再报错，体验更差）。
        loadTargetUrlOrPromptNoNetwork()
    }

    override fun onDestroy() {
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

    /** 扫码/看板场景不希望屏幕自动熄灭 */
    private fun keepScreenOn() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

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
     * 这里加载打包在 assets 里的极简错误页，把错误码和原因通过 query 传进去。
     */
    private fun showErrorPage(code: Int, description: String, failingUrl: String) {
        hideLoading()
        val url = buildString {
            append(ERROR_PAGE)
            append("?code=").append(code)
            append("&msg=").append(URLEncoder.encode(description, "UTF-8"))
            append("&url=").append(URLEncoder.encode(failingUrl, "UTF-8"))
        }
        binding.webView.loadUrl(url)
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
