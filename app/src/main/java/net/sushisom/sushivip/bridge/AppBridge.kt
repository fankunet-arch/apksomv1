package net.sushisom.sushivip.bridge

import android.content.Context
import android.webkit.JavascriptInterface
import net.sushisom.sushivip.device.DeviceIdProvider

/**
 * 注入网页的 JS 桥接对象。
 *
 * 前端调用：
 *     const id = window.AppBridge.getDeviceId();
 *
 * 【安全约束】按需求文档 3.4 节，本对象**只暴露获取设备 ID 这一个能力**，
 * 不得添加任何与之无关的系统级调用。原因：addJavascriptInterface 注入的
 * 对象对页面内**所有**框架可见，包括第三方 iframe —— 每多暴露一个方法，
 * 就多一份被页面内不受控内容滥用的面。
 *
 * 补充防护见 AppWebViewClient：WebView 只允许在白名单域名内导航，
 * 站外链接一律转交系统浏览器，因此本对象不会出现在非受信页面上。
 *
 * 注意：@JavascriptInterface 注解不可省略，且 release 包必须配 ProGuard
 * keep 规则（见 app/proguard-rules.pro），否则方法会被 R8 裁掉。
 */
class AppBridge(private val context: Context) {

    companion object {
        /** 注入到 window 上的对象名，需与前端约定一致 */
        const val NAME = "AppBridge"
    }

    /**
     * 返回设备唯一标识。
     * 正常情况下是 64 位（16 个十六进制字符）的 ANDROID_ID；
     * 取不到时返回本地生成并持久化的 32 位十六进制 UUID。
     * 保证非空。
     */
    @JavascriptInterface
    fun getDeviceId(): String = DeviceIdProvider.getDeviceId(context)
}
