package com.termux.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class HomeWebViewFragment extends Fragment {

    private WebView mWebView;
    private String mUrl;

    /** 静音音频后台保活脚本 - 通过播放无声音频保持应用后台活跃 */
    private static final String SILENT_AUDIO_KEEPALIVE_JS =
        "(function() {" +
        "  if (window.__silentAudioKeepAlive) return;" +
        "  window.__silentAudioKeepAlive = true;" +
        "  try {" +
        "    var ctx = new (window.AudioContext || window.webkitAudioContext)();" +
        "    function createSilentBuffer() {" +
        "      var buf = ctx.createBuffer(1, ctx.sampleRate * 2, ctx.sampleRate);" +
        "      return buf;" +
        "    }" +
        "    var source = ctx.createBufferSource();" +
        "    source.buffer = createSilentBuffer();" +
        "    source.loop = true;" +
        "    var gain = ctx.createGain();" +
        "    gain.gain.value = 0;" +
        "    source.connect(gain);" +
        "    gain.connect(ctx.destination);" +
        "    source.start(0);" +
        "    window.__silentAudioSource = source;" +
        "    window.__silentAudioCtx = ctx;" +
        "  } catch(e) {" +
        "    console.warn('[KeepAlive] AudioContext init failed:', e);" +
        "  }" +
        "})();";

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
            injectSilentAudioIfReady();
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
                injectSilentAudioIfReady();
            }
        });
        mWebView.setWebChromeClient(new WebChromeClient());

        // 通知回调
        if (mWebViewReadyListener != null) {
            mWebViewReadyListener.onWebViewReady(mWebView);
        }

        mWebView.loadUrl(mUrl);
        return mWebView;
    }

    /**
     * 注入静音音频保活脚本
     * 在页面加载完成后调用，通过Web Audio API播放无声音频
     */
    private void injectSilentAudioIfReady() {
        if (mWebView != null) {
            mWebView.evaluateJavascript(SILENT_AUDIO_KEEPALIVE_JS, null);
        }
    }

    /**
     * 重新激活静音音频（例如从后台回来时）
     */
    public void resumeSilentAudio() {
        if (mWebView != null) {
            // 先尝试恢复AudioContext，再重新注入
            String resumeJs =
                "(function() {" +
                "  try {" +
                "    if (window.__silentAudioCtx && window.__silentAudioCtx.state === 'suspended') {" +
                "      window.__silentAudioCtx.resume();" +
                "    } else if (!window.__silentAudioKeepAlive) {" +
                "      " + SILENT_AUDIO_KEEPALIVE_JS.replace("(function() {", "").replace("})();", "") +
                "    }" +
                "  } catch(e) {}" +
                "})();";
            mWebView.evaluateJavascript(resumeJs, null);
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
                // 停止静音音频
                try {
                    mWebView.evaluateJavascript(
                        "(function(){" +
                        "  try{" +
                        "    if(window.__silentAudioSource){window.__silentAudioSource.stop();}" +
                        "    if(window.__silentAudioCtx){window.__silentAudioCtx.close();}" +
                        "  }catch(e){}" +
                        "})();", null);
                } catch (Exception e) { /* ignore */ }

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
