package cn.net.xiangxiang.seeker;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.shared.logger.Logger;

import cn.net.xiangxiang.reaction.frontend.tools.FrontendJavaTools;
import cn.net.xiangxiang.reaction.frontend.WebViewConfig;


/**
 * HomeWebView活动 - 替代Termux的首页
 * <p>
 * 职责：
 * 1. 加载内置的home.html作为首页界面
 * 2. 注入JavaBridgeCommandInterface到WebView中
 * 3. 提供WebView与Termux命令行交互的桥梁
 * </p>
 *
 * 前端通过 window.JavaBridge 对象调用Java方法：
 * <pre>
 * // 执行命令
 * var result = JSON.parse(window.JavaBridge.exec("ls -la"));
 * console.log(result.stdout);
 * </pre>
 */
public class WebViewActivity extends AppCompatActivity {

private static final String LOG_TAG = "WebViewActivity";

    /** WebView实例 */
    private WebView mWebView;

    /** Termux管理器 */


    private TermuxManager mTermuxManager;

    /** JS桥接接口 */
    private JavaBridge mJavaBridge;

    /** WebView 文件选择辅助 */
    private WebViewConfig.FileChooserHelper mFileChooser;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.logDebug(LOG_TAG, "onCreate");

        // 初始化TermuxManager
        mTermuxManager = TermuxManager.getInstance();
        mTermuxManager.init(this);

        FrontendJavaTools frontendJavaTools = new FrontendJavaTools();

        // 初始化JavaBridge
        mJavaBridge = new JavaBridge(this, frontendJavaTools, null);

        // 创建WebView并设置ContentView
        mWebView = new WebView(this);
        configureWebView(mWebView);
        
        // 注入JavaBridge到WebView，JS端通过 window.JavaBridge 调用
        mWebView.addJavascriptInterface(mJavaBridge, "JavaBridge");

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        setContentView(mWebView, params);

        // 优先使用 Intent 传入的 URL（如 NewWindowInterceptor 拦截的 URL），否则使用默认地址
        final String DEFAULT_URL = "https://seeker-vue.xiangxiang.net.cn";
        final String loadUrl = WebViewConfig.getUrlFromIntent(getIntent(), DEFAULT_URL);
        runOnUiThread(() -> {
            mWebView.loadUrl(loadUrl);
        });
        // mWebView.loadUrl("https://seeker-vue.xiangxiang.net.cn");
//         mWebView.loadUrl("http://192.168.1.129:8084/");

    }

    /**
     * 配置WebView设置
     * @param webView 要配置的WebView实例
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView webView) {
        WebSettings settings = webView.getSettings();

        // 启用JavaScript
        settings.setJavaScriptEnabled(true);

        // 允许DOM存储
        WebViewConfig.configureSettings(settings);

        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // 允许从file:// URL访问其他file:// URL（同源策略放宽）
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        // 适配移动端
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // 启用缓存
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 设置WebViewClient处理页面加载
        // webView.setWebViewClient(new WebViewConfig.NewWindowInterceptor());
        // 创建文件选择辅助
        mFileChooser = new WebViewConfig.FileChooserHelper();

        webView.setWebChromeClient(mFileChooser.createWebChromeClient());
        
        Logger.logDebug(LOG_TAG, "WebView配置完成");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Logger.logDebug(LOG_TAG, "onResume");
        if (mWebView != null) {
            mWebView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logger.logDebug(LOG_TAG, "onPause");
        if (mWebView != null) {
            mWebView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Logger.logDebug(LOG_TAG, "onDestroy");
        
        // 销毁所有session
        if (mTermuxManager != null) {
            mTermuxManager.destroyAllSessions();
        }
        
        // 清理WebView
        if (mWebView != null) {
            mWebView.removeAllViews();
            mWebView.destroy();
            mWebView = null;
        }
    }

    @Override
    public void onBackPressed() {
        // 如果WebView可以回退，则回退页面，否则退出Activity
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
