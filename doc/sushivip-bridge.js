/**
 * SushiVIP 容器桥接封装
 * ---------------------------------------------------------------------------
 * 把原生 AppBridge 包一层，解决三件事：
 *   1. 同一份前端代码要能同时跑在「容器内」和「PC 浏览器」（开发调试）
 *   2. 原生接口调用失败时有明确的降级路径，而不是抛异常炸掉整个页面
 *   3. 提供 diagnose()，联调时一行命令看清所有关键状态
 *
 * 用法：
 *   <script src="/static/sushivip-bridge.js"></script>
 *   const { id, source } = SushiVIP.getDeviceId();
 *
 * 直接引入即可，无依赖，支持在 <head> 里同步加载。
 */
(function (global) {
  'use strict';

  // 版本号可能带构建类型后缀：debug 包是 "1.0.0-debug"，release 包是 "1.0.0"。
  // 这里必须用 [\w.-] 而不是 [\d.]，否则 "-debug" 会被截掉，两种包看起来一模一样 ——
  // 而它们的 ANDROID_ID 是不同的，混用会导致设备档案全部对不上号。
  var UA_PATTERN = /SushiVIP\/([\w.-]+)/;
  var WEB_ID_KEY = 'sushivip.web.deviceId';

  /**
   * 是否运行在 SushiVIP 原生容器内，以及容器版本与构建类型。
   *
   * @returns {{inContainer: boolean, version: string|null,
   *            buildType: 'debug'|'release'|null, isDebug: boolean}}
   */
  function container() {
    var m = UA_PATTERN.exec(global.navigator.userAgent || '');
    if (!m) {
      return { inContainer: false, version: null, buildType: null, isDebug: false };
    }
    var isDebug = /-debug$/.test(m[1]);
    return {
      inContainer: true,
      version: m[1],                              // "1.0.0" 或 "1.0.0-debug"
      buildType: isDebug ? 'debug' : 'release',
      isDebug: isDebug
    };
  }

  /**
   * 获取设备唯一标识。
   *
   * @returns {{id: string, source: 'native'|'browser'}}
   *   source === 'native'  —— 来自 Android ANDROID_ID，可作为设备身份依据
   *   source === 'browser' —— 浏览器本地生成，**仅供开发调试，不可入库**
   *
   * 注意：ANDROID_ID 按「应用签名 + 用户 + 设备」派生，
   * debug 包与 release 包在同一台设备上取到的值**不同**。
   * 后端正式建档必须使用 release 包采集的 ID。
   */
  function getDeviceId() {
    var bridge = global.AppBridge;
    if (bridge && typeof bridge.getDeviceId === 'function') {
      try {
        var id = bridge.getDeviceId();
        if (id) return { id: String(id), source: 'native' };
      } catch (e) {
        // 桥接存在但调用失败，落到浏览器兜底，不让页面崩掉
        if (global.console) console.warn('[SushiVIP] getDeviceId 调用失败', e);
      }
    }
    return { id: webFallbackId(), source: 'browser' };
  }

  function webFallbackId() {
    var v = null;
    try { v = global.localStorage.getItem(WEB_ID_KEY); } catch (e) { /* 隐私模式 */ }
    if (v) return v;

    var rnd;
    if (global.crypto && global.crypto.randomUUID) {
      rnd = global.crypto.randomUUID();
    } else {
      rnd = Date.now().toString(16) + Math.random().toString(16).slice(2);
    }
    v = 'web-' + rnd;
    try { global.localStorage.setItem(WEB_ID_KEY, v); } catch (e) { /* 忽略 */ }
    return v;
  }

  /**
   * 相机是否可用。
   * 在 http:// 下恒为 false —— 不是权限问题，而是 Chromium 只在安全上下文
   * 提供 navigator.mediaDevices，非安全上下文下它直接是 undefined。
   */
  function cameraSupported() {
    return !!(global.isSecureContext &&
              global.navigator.mediaDevices &&
              global.navigator.mediaDevices.getUserMedia);
  }

  /**
   * 打开后置摄像头取流。调用方负责把 stream 挂到 <video> 上并 play()。
   * <video> 必须带 autoplay playsinline muted，否则部分场景不会起播。
   */
  function openCamera(constraints) {
    if (!cameraSupported()) {
      return Promise.reject(new Error(
        global.isSecureContext
          ? '当前环境不支持相机 API'
          : '页面不在安全上下文（HTTPS）下，相机 API 不可用'
      ));
    }
    return global.navigator.mediaDevices.getUserMedia(
      constraints || { video: { facingMode: 'environment' } }
    );
  }

  /** 联调用：一行看清所有关键状态 */
  function diagnose() {
    var c = container();
    var info = {
      '运行在容器内': c.inContainer,
      '容器版本': c.version,
      '包类型': c.buildType,
      '安全上下文(HTTPS)': global.isSecureContext,
      'AppBridge 可用': !!(global.AppBridge && global.AppBridge.getDeviceId),
      '相机 API 可用': cameraSupported(),
      'crypto.subtle 可用': !!(global.crypto && global.crypto.subtle),
      '视口': global.innerWidth + ' x ' + global.innerHeight,
      'UA': global.navigator.userAgent
    };
    try {
      info['设备ID'] = getDeviceId();
    } catch (e) {
      info['设备ID'] = '获取失败: ' + e.message;
    }
    if (global.console && console.table) console.table(info);

    // debug 包与 release 包由不同签名派生 ANDROID_ID，取到的设备 ID 不同。
    // 拿 debug 包采集的 ID 去建生产档案，换正式包后会全部失效。
    if (c.isDebug && info['设备ID'] && info['设备ID'].source === 'native' && global.console) {
      console.warn('[SushiVIP] 当前是 debug 包，设备 ID 与正式包不同，请勿用于正式建档');
    }
    return info;
  }

  global.SushiVIP = {
    container: container,
    getDeviceId: getDeviceId,
    cameraSupported: cameraSupported,
    openCamera: openCamera,
    diagnose: diagnose
  };
})(window);
