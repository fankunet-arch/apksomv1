plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "net.sushisom.sushivip"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.sushisom.sushivip"
        minSdk = 31          // Android 12

        // ⚠️ 不要贸然升到 36。从 targetSdk 36 起，系统会在大屏设备
        // （最小宽度 >= 600dp，即本项目的平板）上**忽略** screenOrientation
        // 的横屏锁定。平板上会变成可随重力旋转，违反需求 3.1。
        // 确需升级时，必须同时在 <activity> 里加上兼容属性退回旧行为。
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // ------------------------------------------------------------------
        // 目标站点地址。改这里即可切换环境，无需动代码。
        //
        // 必须是 https://，原因见 README「为什么必须上 HTTPS」：
        // Chromium 只在安全上下文下提供 getUserMedia，http:// 页面里
        // navigator.mediaDevices 直接是 undefined，网页扫码无法工作。
        //
        // 域名由路由器的 DNS 重写解析到内网主机，公网不存在。
        // 证书 SAN 里同时带了 IP，因此 https://192.168.2.32 也可直接访问。
        // ------------------------------------------------------------------
        buildConfigField("String", "BASE_URL", "\"https://lms.sushisom.net/\"")

        // JS Bridge 域名白名单：只有这些 host 上的页面能拿到 AppBridge，
        // 且 WebView 只允许在这些 host 内部导航（站外链接交给系统浏览器）。
        buildConfigField(
            "String[]", "HOST_WHITELIST",
            "{\"lms.sushisom.net\", \"192.168.2.32\"}"
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式签名请在 Android Studio 里配置，或补一个 signingConfigs 块，
            // 密钥库口令走 local.properties / 环境变量，不要提交进仓库。
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.webkit)
}
