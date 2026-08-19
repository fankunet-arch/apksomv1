package net.sushisom.sushivip.web

import android.net.Uri
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * 承接网页发起的「需要原生配合」的请求，主要是两类：
 *
 *  1. 相机取流（扫码）—— onPermissionRequest
 *     网页调 navigator.mediaDevices.getUserMedia({video:true}) 时触发。
 *     ⚠️ 前提：页面必须运行在 https:// 下。http:// 属于非安全上下文，
 *        Chromium 根本不提供 navigator.mediaDevices，这个回调不会被触发。
 *
 *  2. 文件选择 / 拍照上传 —— onShowFileChooser
 *     网页里的 <input type="file"> 被点击时触发。
 *     ⚠️ 不实现这个方法，用户点击上传按钮将**毫无反应**（无报错、无提示），
 *        这是 WebView 套壳最常见的坑。
 *
 * 注意这两条是完全独立的链路：<input type="file" capture> 走的是系统相机
 * App，不经过 onPermissionRequest，也不需要安全上下文。
 */
class AppWebChromeClient(
    private val onCameraPermissionNeeded: (PermissionRequest) -> Unit,
    private val onProgress: (Int) -> Unit,
    private val onFileChooser: (
        callback: ValueCallback<Array<Uri>>,
        params: FileChooserParams
    ) -> Boolean
) : WebChromeClient() {

    companion object {
        private const val TAG = "AppWebChromeClient"
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val wanted = request.resources
        Log.i(TAG, "网页请求设备权限: ${wanted.joinToString()} origin=${request.origin}")

        // 只放行相机。麦克风、屏幕捕获、受保护媒体一律拒绝 —— 需求里不需要，
        // 多放行一项就是多一份风险面。
        val cameraRequested = wanted.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        if (!cameraRequested) {
            request.deny()
            return
        }

        // 交给 Activity：先确认 Android 层的 CAMERA 运行时权限已授予，
        // 再对这个 PermissionRequest 调 grant()。两层权限缺一不可。
        onCameraPermissionNeeded(request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        Log.i(TAG, "网页撤回了权限请求 origin=${request.origin}")
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        onProgress(newProgress)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean = onFileChooser(filePathCallback, fileChooserParams)
}
