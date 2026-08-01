package cn.net.xiangxiang.seeker;

import java.util.LinkedHashMap;

public class WebViewConstants {

    // >>> 新增：控制台捕获注入脚本
    static final String CONSOLE_CAPTURE_JS =
        "(function(){" +
            "  if (window.__captureConsole) { return; }" +
            "  var nativeConsole = {" +
            "    log: console.log.bind(console)," +
            "    warn: console.warn.bind(console)," +
            "    error: console.error.bind(console)," +
            "    info: console.info.bind(console)," +
            "    debug: console.debug.bind(console)" +
            "  };" +
            "  function formatArg(arg) {" +
            "    if (arg === null) return 'null';" +
            "    if (arg === undefined) return 'undefined';" +
            "    if (arg instanceof Error) return arg.stack || (arg.name + ': ' + arg.message);" +
            "    if (typeof arg === 'object') { try { return JSON.stringify(arg); } catch(e) { return String(arg); } }" +
            "    return String(arg);" +
            "  }" +
            "  window.__captureConsole = { lines: [], nativeConsole: nativeConsole };" +
            "  function capture(level, args) {" +
            "    var formatted = Array.prototype.map.call(args, formatArg).join(' ');" +
            "    var prefix = '';" +
            "    if (level === 'warn') prefix = '[WARN] ';" +
            "    else if (level === 'error') prefix = '[ERROR] ';" +
            "    else if (level === 'debug') prefix = '[DEBUG] ';" +
            "    window.__captureConsole.lines.push({" +
            "      level: level," +
            "      text: prefix + formatted," +
            "      time: new Date().toISOString()" +
            "    });" +
            "    nativeConsole[level].apply(console, args);" +
            "  }" +
            "  console.log = function() { capture('log', arguments); };" +
            "  console.warn = function() { capture('warn', arguments); };" +
            "  console.error = function() { capture('error', arguments); };" +
            "  console.info = function() { capture('info', arguments); };" +
            "  console.debug = function() { capture('debug', arguments); };" +
            "  window.__getConsoleLogs = function() { return window.__captureConsole.lines; };" +
            "  window.__clearConsoleLogs = function() { window.__captureConsole.lines = []; };" +
            "})();";
    // <<< 新增结束
}
