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

    // ===== 新增：WebView 就绪回调接口 =====
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

        mWebView.setWebViewClient(new WebViewClient());
        mWebView.setWebChromeClient(new WebChromeClient());

        // 通知回调
        if (mWebViewReadyListener != null) {
            mWebViewReadyListener.onWebViewReady(mWebView);
        }

        mWebView.loadUrl(mUrl);
        return mWebView;
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
