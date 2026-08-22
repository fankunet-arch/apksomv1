# SushiVIP — 极简 WebView 容器 Android 客户端

内网 LMS 站点 `https://lms.sushisom.net` 的原生容器（WebView 套壳）。
需求原文见 [`doc/readme.md`](doc/readme.md)。

> 📋 **要动手部署，请直接看 [`doc/执行说明.md`](doc/执行说明.md)** —— 从服务器配置到真机验收的完整分步手册，含故障速查表和避坑清单。
> 本文档偏技术说明，讲的是「为什么这么做」。
>
> 🌐 **LMS 前端 / 服务端团队请看 [`doc/平台端调整说明.md`](doc/平台端调整说明.md)** —— Web 侧需要做的全部调整，含必须避开的写法和联调清单。
> 桥接封装可直接引入：[`doc/sushivip-bridge.js`](doc/sushivip-bridge.js)

| 项 | 值 |
|---|---|
| applicationId | `net.sushisom.sushivip` |
| 应用名 | SushiVIP |
| minSdk | 31（Android 12） |
| compileSdk / targetSdk | 35 |
| 语言 / 构建 | Kotlin + Gradle Kotlin DSL + 版本目录 |
| 目标地址 | `https://lms.sushisom.net/`（`app/build.gradle.kts` 里的 `BASE_URL`） |
| 屏幕方向 | 固定横屏（充电口朝右） |

---

## 一、为什么必须上 HTTPS

需求文档原本设想站点跑在 `http://` 上，但这与「网页内嵌扫码」直接冲突：

Chromium 只在**安全上下文**（`https://` 或 localhost）下提供
`navigator.mediaDevices`。在 `http://` 页面里它是 `undefined` ——
不是权限被拒，而是 API 根本不存在，原生的 `onPermissionRequest`
连触发的机会都没有，写再多容器代码也没用。

受影响的不止相机，以下能力在 `http://` 下**全部不可用**：

- `navigator.mediaDevices.getUserMedia` — 扫码取流
- `crypto.subtle` — 前端加解密/签名
- Service Worker / PWA 离线缓存
- `navigator.clipboard`、地理定位

内网拿不到公网证书，但可以自签。方案是**自建 CA + APK 内置信任锚点**，
效果等同于一张完全合法的证书：不报 SSL 错误，也不需要
`onReceivedSslError` + `proceed()`（那种做法等于接受任意伪造证书，
TLS 安全性归零，本项目明确拒绝这么做，见 `AppWebViewClient`）。

### 部署步骤

**1. 生成证书**（在服务器或任意一台 Linux 上）

```bash
./tools/gen-intranet-cert.sh lms.sushisom.net 192.168.2.32
```

产出 4 个文件：

| 文件 | 去向 |
|---|---|
| `fullchain.pem` | 宝塔 SSL 的「证书(PEM格式)」文本框 |
| `server.key` | 宝塔 SSL 的「密钥(KEY)」文本框 |
| `ca.crt` | 覆盖到 `app/src/main/res/raw/intranet_ca.crt` |
| `ca.key` | **签发私钥，离线保管，绝不上传服务器、绝不进仓库** |

**2. 宝塔挂证书**

网站 → 站点设置 → SSL → **「其他证书」**标签页，粘贴上面两项，保存后开启「强制HTTPS」。

> 不要用 Let's Encrypt 标签页 —— 它需要走公网 80 端口验证域名，
> `lms.sushisom.net` 由路由器的 DNS 重写解析到内网主机，公网上并不存在，验证必然失败。

**3. 放行端口**：宝塔 → 安全 → 放行 443；`sudo ufw allow 443/tcp`

**4. 验证**

```bash
curl -v --cacert ca.crt https://lms.sushisom.net/
```

**5. 替换 APK 内置 CA**

```bash
cp certs-lms.sushisom.net/ca.crt app/src/main/res/raw/intranet_ca.crt
```

> ⚠️ 仓库里现在放的是一张**占位证书**（CN 为 `PLACEHOLDER - REPLACE WITH REAL INTRANET CA`，
> 其私钥在生成时已被销毁，无法用于签发）。不替换的话 App 会明确报
> 「HTTPS 证书不受信任」并停在错误页 —— 这是刻意设计的失败模式，
> 好过静默降级成不安全连接。

**6. 收紧配置**：证书验证通过后，把
`app/src/main/res/xml/network_security_config.xml` 里的
`cleartextTrafficPermitted="true"` 改成 `false`，彻底关掉明文回退。

---

## 二、关于「跨域限制」

需求 3.2 提到「需放开跨域限制」。这里要澄清一个常见误解：

**原生 WebView 没有任何开关能关闭 CORS。** `setAllowUniversalAccessFromFileURLs`
之类只对 `file://` 页面有效。页面走 http(s) 加载时，跨域策略由 Chromium
内核和服务端的 `Access-Control-Allow-Origin` 响应头共同决定。

本项目已处理的是与之相邻的两件事：

- **混合内容**：`MIXED_CONTENT_ALWAYS_ALLOW`，HTTPS 页面可加载 HTTP 子资源 ✅
- **明文流量**：`network_security_config` 放行（过渡期用）✅

如果确实存在跨域接口，只有两条路：服务端加 CORS 响应头（推荐），
或在原生层用 `shouldInterceptRequest` 代理转发（注意该 API **拿不到
POST 请求体**，只能代理 GET）。

---

## 三、前端对接契约

### 3.1 设备唯一标识

```js
const deviceId = window.AppBridge.getDeviceId();  // 同步返回字符串，保证非空
```

- 正常返回 16 个十六进制字符的 `ANDROID_ID`
- 取不到时返回本地生成并持久化的 32 位十六进制 UUID

**注意**：`ANDROID_ID` 按「应用签名 + 用户 + 设备」派生，因此
**debug 包和 release 包在同一台设备上拿到的 ID 不同**，联调时别被这个绕进去。
恢复出厂、多用户、应用分身也会改变它 —— 实现里已做本地缓存兜底
（见 `DeviceIdProvider`），同一次安装内保持稳定。

按需求 3.4 的安全约束，`AppBridge` **只暴露这一个方法**，不要往里加东西。

### 3.2 扫码

站点上了 HTTPS 之后，直接用标准 Web API 即可，容器已配好：

```js
const stream = await navigator.mediaDevices.getUserMedia({
  video: { facingMode: 'environment' }
});
```

容器侧已处理：Manifest 声明 CAMERA、运行时权限动态申请、
`onPermissionRequest` 放行、以及 `mediaPlaybackRequiresUserGesture = false`
（**少这一项视频流不会自动播放，取景框全黑且无任何报错**）。

权限被「拒绝且不再询问」时，容器会弹框引导用户跳转系统设置页。

### 3.3 拍照上传

```html
<input type="file" accept="image/*" capture="environment">
```

走的是与扫码**完全不同**的链路（`onShowFileChooser` → 系统相机），
不需要安全上下文。容器已实现：

- 带 `capture` → 直接拉起系统相机
- 不带 `capture` → 弹出「相机 / 相册·文件」二选一
- 支持 `multiple` 多选

> 这个方法是 WebView 套壳最容易漏的一环：不实现的话，用户点击上传按钮
> **完全没有反应**，无报错、无提示。

### 3.4 导航行为

| 场景 | 容器行为 |
|---|---|
| 白名单域名内的 http(s) 链接 | 留在 WebView 内 |
| 站外 http(s) 链接 | 转交系统浏览器 |
| `tel:` `mailto:` `weixin://` `alipays://` 等 | 转交系统处理（已 try/catch，未安装对应 App 不会崩溃） |
| 返回键，且 `canGoBack()` 为 true | 网页后退 |
| 返回键，已在首页 | 弹「确认退出」对话框 |

白名单在 `app/build.gradle.kts` 的 `HOST_WHITELIST` 里，
目前是 `lms.sushisom.net` 和 `192.168.2.32`。

---

## 四、构建

```bash
./gradlew assembleDebug     # 产物：app/build/outputs/apk/debug/
./gradlew assembleRelease   # 需先配置签名
```

需要 JDK 17 与 Android SDK Platform 35。

- **AGP / Gradle 版本**：`gradle/libs.versions.toml` 里锁的是 AGP 8.9.1 + Gradle 8.14.3。
  这是一个确定可用的稳定组合。用较新的 Android Studio 打开时，
  可直接用 **AGP Upgrade Assistant** 一键升级到与你的 Studio 匹配的版本。
- **不要贸然把 `targetSdk` 升到 36**：从 targetSdk 36 起，系统会在大屏设备
  （最小宽度 ≥ 600dp，即本项目的平板）上**忽略 `screenOrientation` 的横屏锁定**，
  平板会变成可随重力旋转，违反需求 3.1。
- **release 签名**未配置。请在 Android Studio 里生成 keystore，
  口令走 `local.properties` 或环境变量，**不要提交进仓库**（`.gitignore` 已排除 `*.jks` / `*.keystore`）。

### 屏幕方向

`AndroidManifest.xml` 里锁的是 `android:screenOrientation="landscape"`，
对应设备从自然竖屏方向逆时针转 90°，即**充电口朝右**，符合需求。

但「自然方向」由 OEM 决定，部分平板出厂即以横屏为自然方向。
**真机实测若方向反了，把这一行改成 `reverseLandscape` 即可**，不需要动任何代码。

### 图标

品牌 logo 目前是**矢量复刻版**（原始 PNG 未入库）。拿到原图后：

```bash
cp <原始logo>.png doc/logo.png
python3 tools/gen_launcher_icons.py doc/logo.png
```

会重新生成全部密度的切图。脚本已处理自适应图标的安全区
（logo 缩到画布 60%，避免被各家启动器的遮罩裁掉）。

---

## 五、代码结构

```
app/src/main/
├── AndroidManifest.xml              权限声明、方向锁定、FileProvider
├── assets/error.html                内置错误页（自包含，断网可渲染）
├── java/net/sushisom/sushivip/
│   ├── MainActivity.kt              容器主体：WebView 配置、权限、生命周期
│   ├── bridge/AppBridge.kt          JS 桥接，仅暴露 getDeviceId()
│   ├── device/DeviceIdProvider.kt   ANDROID_ID + 本地缓存兜底
│   ├── network/NetworkChecker.kt    加载前的网络前置校验
│   └── web/
│       ├── AppWebViewClient.kt      导航控制、错误拦截、SSL 策略
│       ├── AppWebChromeClient.kt    相机权限请求、文件选择入口
│       └── FileChooserDelegate.kt   拍照/相册/文件选择的完整实现
└── res/
    ├── raw/intranet_ca.crt          内网根 CA（当前为占位证书，需替换）
    └── xml/network_security_config.xml
```

## 六、工具脚本

| 脚本 | 用途 |
|---|---|
| `tools/gen-intranet-cert.sh` | 生成内网自签 CA 与服务器证书 |
| `tools/gen_launcher_icons.py` | 生成各密度启动器图标（需 `pip install pillow numpy`） |
