package cn.net.xiangxiang.reaction.frontend;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.appcompat.app.AlertDialog;

import com.termux.shared.logger.Logger;

/**
 * WebView 统一配置
 *
 * 为项目所有 WebView 提供统一的设置、文件选择支持、Intent URL 提取等。
 *
 * <p>使用方式：</p>
 * <pre>
 * // 1. 在 onCreate 中提取 URL
 * String url = WebViewConfig.getUrlFromIntent(getIntent(), "https://default.url");
 *
 * // 2. 配置 WebSettings
 * WebViewConfig.configureSettings(mWebView.getSettings());
 *
 * // 3. 设置文件选择功能（需要当前 Activity）
 * WebViewConfig.FileChooserHelper fileChooser = new WebViewConfig.FileChooserHelper();
 * mWebView.setWebChromeClient(fileChooser.createWebChromeClient());
 *
 * // 4. 在 onActivityResult 中转发结果
 * if (fileChooser.onActivityResult(requestCode, resultCode, data)) return;
 * </pre>
 */
public final class WebViewConfig {

    private static final String LOG_TAG = "WebViewConfig";

    /** 文件选择请求码 */
    public static final int FILE_CHOOSER_REQUEST_CODE = 10010;

    private WebViewConfig() {
        throw new AssertionError("Utility class, do not instantiate");
    }

    // ==== WebSettings 统一配置 ====

    /**
     * 统一配置 WebSettings。
     * 包含：JavaScript、DOM存储、文件访问、视口适配、缓存等
     */
    @SuppressLint("SetJavaScriptEnabled")
    public static void configureSettings(WebSettings settings) {
        // 启用JavaScript
        settings.setJavaScriptEnabled(true);

        // 允许DOM存储
        settings.setDomStorageEnabled(true);

        // 允许文件访问（用于加载本地资源和上传文件）
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // 允许从file:// URL访问其他file:// URL
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        // 适配移动端
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // 启用缓存
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Android 5.0+ 支持混合内容
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        Logger.logDebug(LOG_TAG, "WebSettings配置完成");
    }

    // ==== Intent URL 提取 ====

    /**
     * 从 Intent 获取要加载的 URL。
     * 优先获取 data URI，其次获取指定 extra key 的 URL，最后返回默认值。
     *
     * @param intent       当前 Activity 的 Intent
     * @param defaultUrl   默认 URL（可为 null）
     * @return 要加载的 URL
     */
    public static String getUrlFromIntent(Intent intent, String defaultUrl) {
        if (intent == null) {
            return defaultUrl;
        }

        // 1. 从 Intent data URI 获取
        Uri dataUri = intent.getData();
        if (dataUri != null) {
            String url = dataUri.toString();
            Logger.logDebug(LOG_TAG, "从Intent data获取URL: " + url);
            return url;
        }

        // 2. 从 EXTRA_TEXT 获取
        String[] extraKeys = {"url", "address", Intent.EXTRA_TEXT};
        for (String key : extraKeys) {
            if (intent.hasExtra(key)) {
                String value = intent.getStringExtra(key);
                if (value != null && !value.isEmpty()) {
                    if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("file://")) {
                        Logger.logDebug(LOG_TAG, "从Intent extra '" + key + "' 获取URL: " + value);
                        return value;
                    }
                    if (!value.contains("://")) {
                        value = "https://" + value;
                        Logger.logDebug(LOG_TAG, "补全URL协议头: " + value);
                        return value;
                    }
                }
            }
        }

        // 3. 返回默认值
        Logger.logDebug(LOG_TAG, "使用默认URL: " + defaultUrl);
        return defaultUrl;
    }

    // ==== 文件选择功能 ====

    /**
     * 文件选择辅助类。
     * 每个 Activity 实例化一个，用于处理 WebView 的文件选择。
     *
     * <p>提供的 WebChromeClient 同时保留了 onConsoleMessage 日志功能。</p>
     */
    public static class FileChooserHelper {

        private ValueCallback<Uri[]> mFilePathCallback;

        /**
         * 创建带文件选择功能的 WebChromeClient。
         * 同时保留 JS 控制台日志输出。
         */
        public WebChromeClient createWebChromeClient() {
            return new WebChromeClient() {
                @Override
                public void onConsoleMessage(String message, int lineNumber, String sourceID) {
                    Logger.logDebug(LOG_TAG, "JS控制台: " + message +
                        " (行:" + lineNumber + " 源:" + sourceID + ")");
                }

                @Override
                public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                    new AlertDialog.Builder(view.getContext())
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setCancelable(false)
                        .show();
                    return true;
                }

                @Override
                public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                    new AlertDialog.Builder(view.getContext())
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                        .setCancelable(false)
                        .show();
                    return true;
                }

                @Override
                public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                    EditText input = new EditText(view.getContext());
                    if (defaultValue != null) {
                        input.setText(defaultValue);
                    }
                    int padding = (int) (16 * view.getContext().getResources().getDisplayMetrics().density);
                    FrameLayout container = new FrameLayout(view.getContext());
                    container.addView(input);
                    container.setPadding(padding, padding / 2, padding, 0);

                    new AlertDialog.Builder(view.getContext())
                        .setMessage(message)
                        .setView(container)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm(input.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                        .setCancelable(false)
                        .show();
                    return true;
                }

                // Android 5.0+
                @Override
                public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                                 FileChooserParams fileChooserParams) {
                    if (mFilePathCallback != null) {
                        mFilePathCallback.onReceiveValue(null);
                    }
                    mFilePathCallback = filePathCallback;

                    Intent intent = createFileChooserIntent(fileChooserParams);
                    Activity activity = getActivityFromWebView(webView);
                    if (activity != null) {
                        try {
                            activity.startActivityForResult(
                                Intent.createChooser(intent, "选择文件"),
                                FILE_CHOOSER_REQUEST_CODE);
                            Logger.logDebug(LOG_TAG, "文件选择器已启动");
                            return true;
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "启动文件选择器失败: " + e.getMessage());
                            mFilePathCallback.onReceiveValue(null);
                            mFilePathCallback = null;
                        }
                    }
                    return false;
                }
            };
        }

        /**
         * 在 Activity.onActivityResult 中调用，处理文件选择回调。
         *
         * @param requestCode 请求码
         * @param resultCode  结果码
         * @param data        返回的 Intent 数据
         * @return true 表示已处理
         */
        public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
            if (requestCode != FILE_CHOOSER_REQUEST_CODE) {
                return false;
            }

            if (mFilePathCallback == null) {
                Logger.logDebug(LOG_TAG, "文件选择回调为空，忽略");
                return true;
            }

            Logger.logDebug(LOG_TAG, "文件选择结果: resultCode=" + resultCode);

            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri resultUri = data.getData();
                if (resultUri != null) {
                    results = new Uri[]{resultUri};
                    Logger.logDebug(LOG_TAG, "选中的文件URI: " + resultUri);
                }

                // 处理多文件选择（ClipData）
                ClipData clipData = data.getClipData();
                if (clipData != null && clipData.getItemCount() > 0) {
                    results = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        results[i] = clipData.getItemAt(i).getUri();
                    }
                    Logger.logDebug(LOG_TAG, "选中的多个文件: " + clipData.getItemCount() + "个");
                }
            }

            mFilePathCallback.onReceiveValue(results);
            mFilePathCallback = null;
            return true;
        }

        // ---- 私有辅助 ----

        /**
         * 创建文件选择 Intent，尊重 FileChooserParams 的建议。
         */
        private Intent createFileChooserIntent(WebChromeClient.FileChooserParams params) {
            Intent intent;
            if (params != null && params.getAcceptTypes() != null && params.getAcceptTypes().length > 0) {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                String[] acceptTypes = params.getAcceptTypes();
                if (acceptTypes.length == 1 && !"".equals(acceptTypes[0])) {
                    intent.setType(acceptTypes[0]);
                } else if (acceptTypes.length > 1) {
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes);
                }
            } else {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
            }
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                params != null && params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
            return intent;
        }

        /**
         * 从 WebView 获取所在的 Activity。
         */
        private Activity getActivityFromWebView(WebView webView) {
            if (webView == null) return null;
            android.content.Context context = webView.getContext();
            while (context instanceof android.content.ContextWrapper) {
                if (context instanceof Activity) {
                    return (Activity) context;
                }
                context = ((android.content.ContextWrapper) context).getBaseContext();
            }
            return null;
        }
    }
}

