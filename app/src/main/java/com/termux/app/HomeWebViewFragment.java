package com.termux.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import cn.net.xiangxiang.seeker.JavaBridgeConstants;
import cn.net.xiangxiang.seeker.WebViewConfig;

public class HomeWebViewFragment extends Fragment {

    private WebView mWebView;
    private String mUrl;

    // ===== WebView 就绪回调接口 =====
    public interface OnWebViewReadyListener {
        void onWebViewReady(WebView webView);
    }

    private OnWebViewReadyListener mWebViewReadyListener;

    public void setOnWebViewReadyListener(OnWebViewReadyListener listener) {
        mWebViewReadyListener = listener;
        // 如果 Fragment 已经有视图，立即触发回调（例如在 Activity 重建后设置 listener 时视图已存在）
        if (mWebView != null && listener != null) {
            listener.onWebViewReady(mWebView);
        }
    }

    public HomeWebViewFragment() {}

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);

        if (getArguments() != null) {
            mUrl = getArguments().getString("url");
        }
        if (mUrl == null) {
            mUrl = "https://seeker-vue.xiangxiang.net.cn";
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (mWebView != null) {
            ViewGroup parent = (ViewGroup) mWebView.getParent();
            if (parent != null) {
                parent.removeView(mWebView);
            }
            // 通知回调（适用于复用 WebView 的情况）
            if (mWebViewReadyListener != null) {
                mWebViewReadyListener.onWebViewReady(mWebView);
            }
            return mWebView;
        }

        mWebView = new WebView(requireContext());
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // ===== 注入桥接 JS，使前端可以调用 callNative/callNativeData =====
                view.loadUrl("javascript:if(typeof onBridgeReady === 'function') onBridgeReady();");
                view.evaluateJavascript(JavaBridgeConstants.BRIDGE_JS, null);
            }
        });
        WebViewConfig.FileChooserHelper fileChooserHelper = new WebViewConfig.FileChooserHelper();
        mWebView.setWebChromeClient(fileChooserHelper.createWebChromeClient(getActivity()));

        // 通知回调
        if (mWebViewReadyListener != null) {
            mWebViewReadyListener.onWebViewReady(mWebView);
        }

        mWebView.loadUrl(mUrl);
        return mWebView;
    }

    /** 后台时注入 - 启动保活音频 */
    private static final String START_AUDIO_JS =
        "(function(){" +
        "  if(window.__kaRunning) return;" +
        "  window.__kaRunning=true;" +
        "  var ctx=new(window.AudioContext||window.webkitAudioContext)();" +
        "  var osc=ctx.createOscillator();" +
        "  osc.type='sine';osc.frequency.value=1;" +
        "  var g=ctx.createGain();g.gain.value=0.00;" +
        "  osc.connect(g);g.connect(ctx.destination);osc.start(0);" +
        "  window.__kaOsc=osc;window.__kaCtx=ctx;" +
        "})();";

    /** 回到前台时注入 - 停止音频 */
    private static final String STOP_AUDIO_JS =
        "(function(){" +
        "  try{" +
        "    if(window.__kaOsc){window.__kaOsc.stop();window.__kaOsc=null;}" +
        "    if(window.__kaCtx){window.__kaCtx.close();window.__kaCtx=null;}" +
        "  }catch(e){}" +
        "  window.__kaRunning=false;" +
        "})();";

    /** 重启音频（关闭再打开，由Service定时调用） */
    private static final String RESTART_AUDIO_JS =
        "(function(){" +
        "  try{" +
        "    if(window.__kaOsc){window.__kaOsc.stop();window.__kaOsc=null;}" +
        "    if(window.__kaCtx){window.__kaCtx.close();window.__kaCtx=null;}" +
        "  }catch(e){}" +
        "  window.__kaRunning=false;" +
        "  var ctx=new(window.AudioContext||window.webkitAudioContext)();" +
        "  var osc=ctx.createOscillator();" +
        "  osc.type='sine';osc.frequency.value=1;" +
        "  var g=ctx.createGain();g.gain.value=0.00;" +
        "  osc.connect(g);g.connect(ctx.destination);osc.start(0);" +
        "  window.__kaOsc=osc;window.__kaCtx=ctx;" +
        "  window.__kaRunning=true;" +
        "})();";

    /** 由Service定时调用 - 重启音频防检测 */
    public void restartKeepAlive() {
        if (mWebView != null) {
            mWebView.evaluateJavascript(RESTART_AUDIO_JS, null);
        }
    }

    /** 后台时调用 - 启动静音音频保活 */
    public void startKeepAlive() {
        if (mWebView != null) {
            mWebView.evaluateJavascript(START_AUDIO_JS, null);
        }
    }

    /** 回到前台时调用 - 停止音频 */
    public void stopKeepAlive() {
        if (mWebView != null) {
            mWebView.evaluateJavascript(STOP_AUDIO_JS, null);
        }
    }

    @Override
    public void onDestroyView() {
        if (mWebView != null) {
            ViewGroup parent = (ViewGroup) mWebView.getParent();
            if (parent != null) {
                parent.removeView(mWebView);
            }
        }
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        if (getActivity() != null && getActivity().isFinishing()) {
            if (mWebView != null) {
                stopKeepAlive();
                mWebView.removeAllViews();
                mWebView.destroy();
                mWebView = null;
            }
        }
        super.onDestroy();
    }

    public WebView getWebView() {
        return mWebView;
    }

    public void loadUrl(String url) {
        mUrl = url;
        if (mWebView != null) {
            mWebView.loadUrl(url);
        }
    }
}
