package net.sushisom.sushivip.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import net.sushisom.sushivip.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 处理网页 <input type="file"> 的文件选择与拍照上传。
 *
 * 有两条铁律，违反任意一条都会让上传功能「点了没反应」并且再也无法恢复：
 *
 *  1. onShowFileChooser 返回 true 之后，filePathCallback **必须且只能被调用
 *     一次**。用户取消也要回调 null。漏掉的话 WebView 会认为选择器仍然开着，
 *     该 <input> 之后永远不再响应点击。
 *  2. 新请求到来时若还有未完成的旧回调，要先把旧的以 null 结掉。
 *
 * 另一个隐蔽的坑：应用一旦在 Manifest 里声明了 CAMERA 权限，
 * MediaStore.ACTION_IMAGE_CAPTURE 就**要求该权限已被授予**，否则抛
 * SecurityException。所以拍照路径同样要走运行时权限申请。
 *
 * @param ensureCameraPermission 申请 CAMERA 运行时权限，结果通过回调返回
 */
class FileChooserDelegate(
    private val activity: ComponentActivity,
    private val ensureCameraPermission: (onResult: (Boolean) -> Unit) -> Unit
) {

    companion object {
        private const val TAG = "FileChooserDelegate"
        private const val CAPTURE_DIR = "captures"
    }

    private var pendingCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCaptureUri: Uri? = null

    /**
     * 统一的结果接收口。系统相机与文件选择器共用一个 launcher：
     * 相机返回的 Intent data 为 null（图片写在我们指定的 URI 里），
     * 据此区分两种来源。
     */
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            deliver(null)
            return@registerForActivityResult
        }

        val data = result.data
        val uris = if (data == null || (data.data == null && data.clipData == null)) {
            // 来自系统相机
            pendingCaptureUri?.let { arrayOf(it) }
        } else {
            // 来自文件/相册选择器，parseResult 同时处理单选与多选
            FileChooserParams.parseResult(result.resultCode, data)
        }
        deliver(uris)
    }

    fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: FileChooserParams
    ): Boolean {
        // 铁律 2：先结掉上一个悬空回调
        pendingCallback?.onReceiveValue(null)
        pendingCallback = callback
        pendingCaptureUri = null

        val wantsImage = params.acceptTypes.any {
            it.startsWith("image/") || it == "*/*" || it.isEmpty()
        }

        // <input type="file" accept="image/*" capture> —— 前端明确要求直接开相机
        if (params.isCaptureEnabled && wantsImage) {
            launchCamera()
            return true
        }

        val contentIntent = params.createIntent()
        if (!wantsImage) {
            // 非图片类型，直接给文件选择器，不掺相机
            launchOrFail(contentIntent)
            return true
        }

        // 图片类型：给一个「相机 + 相册/文件」的二选一
        ensureCameraPermission { granted ->
            val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                putExtra(Intent.EXTRA_INTENT, contentIntent)
                putExtra(Intent.EXTRA_TITLE, activity.getString(R.string.app_name))
                if (granted) {
                    createCameraIntent()?.let {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it))
                    }
                }
            }
            launchOrFail(chooser)
        }
        return true
    }

    private fun launchCamera() {
        ensureCameraPermission { granted ->
            if (!granted) {
                Toast.makeText(activity, R.string.toast_camera_denied, Toast.LENGTH_SHORT).show()
                deliver(null)
                return@ensureCameraPermission
            }
            val intent = createCameraIntent()
            if (intent == null) {
                Toast.makeText(activity, R.string.toast_no_camera_app, Toast.LENGTH_SHORT).show()
                deliver(null)
                return@ensureCameraPermission
            }
            launchOrFail(intent)
        }
    }

    /**
     * 在应用私有缓存里建一个临时文件，通过 FileProvider 授权给系统相机写入。
     * 用 FileProvider 而非 file:// 是强制要求：Android 7 起直接传 file:// URI
     * 会抛 FileUriExposedException。
     */
    private fun createCameraIntent(): Intent? = runCatching {
        val dir = File(activity.cacheDir, CAPTURE_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "IMG_$stamp.jpg")

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        )
        pendingCaptureUri = uri

        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }.onFailure { Log.e(TAG, "创建拍照 Intent 失败", it) }.getOrNull()

    private fun launchOrFail(intent: Intent) {
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "没有可处理该 Intent 的应用", e)
            Toast.makeText(activity, R.string.toast_cannot_open_link, Toast.LENGTH_SHORT).show()
            deliver(null)
        }
    }

    /** 铁律 1：有且仅有这一处消费 pendingCallback */
    private fun deliver(uris: Array<Uri>?) {
        pendingCallback?.onReceiveValue(uris)
        pendingCallback = null
        pendingCaptureUri = null
    }

    /** Activity 销毁时兜底，避免回调悬空 */
    fun cancelPending() = deliver(null)
}
