# ---------------------------------------------------------------------------
# JS Bridge 必须保留：@JavascriptInterface 方法是被 WebView 反射调用的，
# R8 看不到调用点，不加这条规则 release 包里 getDeviceId() 会被裁掉，
# 表现为网页调用时报 "not a function"。
# ---------------------------------------------------------------------------
-keepclassmembers class net.sushisom.sushivip.bridge.AppBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
