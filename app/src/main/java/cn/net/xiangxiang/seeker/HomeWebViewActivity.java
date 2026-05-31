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

import com.termux.app.TermuxActivity;

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
public class HomeWebViewActivity extends AppCompatActivity {

    private static final String LOG_TAG = "HomeWebViewActivity";

    /** WebView实例 */
    private WebView mWebView;

    /** Termux管理器 */
    private TermuxManager mTermuxManager;

    /** JS桥接接口 */
    private JavaBridge mJavaBridge;

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
        mJavaBridge = new JavaBridge(frontendJavaTools);

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

        // 加载assets中的home.html
//        mWebView.loadUrl("file:///android_asset/dist/index.html");
        runOnUiThread(() -> {
//            mWebView.loadUrl("file:///android_asset/home.html");
//            mWebView.loadUrl("http://192.168.1.129:8084/");
            mWebView.loadUrl("https://seeker-vue.xiangxiang.net.cn");
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
        settings.setDomStorageEnabled(true);

        // 允许文件访问（用于加载本地资源）
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
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Logger.logDebug(LOG_TAG, "页面加载完成: " + url);
                // 页面加载完成后通知JS端
                view.loadUrl("javascript:if(typeof onBridgeReady === 'function') onBridgeReady();");

            }

            @Override
            public void onReceivedError(WebView view, int errorCode, 
                                        String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Logger.logError(LOG_TAG, "页面加载错误: " + description);
                Toast.makeText(HomeWebViewActivity.this, 
                    "页面加载失败: " + description, Toast.LENGTH_SHORT).show();
            }
        });

        // 设置WebChromeClient处理JS对话框等
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onConsoleMessage(String message, int lineNumber, String sourceID) {
                Logger.logDebug(LOG_TAG, "JS控制台: " + message + 
                    " (行:" + lineNumber + " 源:" + sourceID + ")");
            }
        });
        
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
