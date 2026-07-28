package cn.net.xiangxiang.seeker;

import java.util.concurrent.atomic.AtomicInteger;

public class JavaBridgeConstants {

    // ---- 浮动 WebView 管理 ----
    public static final int MAX_WEB_VIEW_COUNT = 5;
    public static final AtomicInteger floatingWebViewIdSeq = new AtomicInteger(0);

    public static final String BRIDGE_JS =
        "(function(){" +
            "if(window.__jbBridgeReady) return;" +
            "window.__jbBridgeReady = true;" +
            "window.__jbCallbacks = {};" +
            "window.__jbCallbackSeq = 0;" +

            "function JavaBridgeError(resp) {" +
            "  this.name = 'JavaBridgeError';" +
            "  this.message = resp.error || 'Unknown error';" +
            "  this.errorType = resp.errorType;" +
            "  this.errorDetails = resp.errorDetails;" +
            "  this.data = resp.data;" +
            "}" +
            "JavaBridgeError.prototype = new Error();" +
            "JavaBridgeError.prototype.constructor = JavaBridgeError;" +

            "window.__handleJavaBridgeCallback = function(callbackId, response) {" +
            "  var entry = window.__jbCallbacks[callbackId];" +
            "  if (entry) {" +
            "    delete window.__jbCallbacks[callbackId];" +
            "    if (response.success) {" +
            "      entry.resolve(response);" +
            "    } else {" +
            "      entry.reject(new JavaBridgeError(response));" +
            "    }" +
            "  }" +
            "};" +

            "window.callNative = function(methodName, params, timeoutMs) {" +
            "  timeoutMs = timeoutMs || (1000 * 60 * 30);" +
            "  return new Promise(function(resolve, reject) {" +
            "    var callbackId = 'jb_' + (++window.__jbCallbackSeq) + '_' + Date.now();" +
            "    var timer = setTimeout(function() {" +
            "      delete window.__jbCallbacks[callbackId];" +
            "      reject(new Error('JavaBridge timeout: ' + methodName + ' (' + timeoutMs + 'ms)'));" +
            "    }, timeoutMs);" +
            "    window.__jbCallbacks[callbackId] = {" +
            "      resolve: function(r) { clearTimeout(timer); resolve(r); }," +
            "      reject:  function(e) { clearTimeout(timer); reject(e); }" +
            "    };" +
            "    window.JavaBridge.invokeMethodAsync(methodName, JSON.stringify(params || []), callbackId);" +
            "  });" +
            "};" +

            "window.callNativeData = function(methodName, params, timeoutMs) {" +
            "  return window.callNative(methodName, params, timeoutMs).then(function(r) { return r.data; });" +
            "};" +
            "})();";
}
