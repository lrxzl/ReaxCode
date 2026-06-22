package cn.net.xiangxiang.seeker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 支付宝/微信 H5 支付 scheme 处理器
 * <p>
 * 解决 WebView 无法唤起支付宝、微信等 App 进行支付的问题。
 * H5 支付页面会通过自定义 scheme（如 alipays://、weixin://）或 intent:// URI
 * 跳转到对应 App，但默认 WebViewClient 不会处理这些 scheme，导致跳转失败。
 * <p>
 * 使用方式：在 WebViewClient 的 shouldOverrideUrlLoading 中调用
 * {@link #handlePaymentUrl(Activity, WebView, String)} 即可。
 */
public final class PaymentSchemeHandler {

    private static final String TAG = "PaymentSchemeHandler";

    private PaymentSchemeHandler() {
    }

    /**
     * 判断是否为需要外部处理的支付/应用跳转 URL
     */
    public static boolean shouldIntercept(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        // intent:// URI
        if (lower.startsWith("intent://")) return true;
        // 支付宝
        if (lower.startsWith("alipays://") || lower.startsWith("alipay://")) return true;
        // 微信
        if (lower.startsWith("weixin://") || lower.startsWith("wxpay://")) return true;
        // 淘宝
        if (lower.startsWith("taobao://")) return true;
        // 应用宝 / Google Play
        if (lower.startsWith("market://")) return true;
        // mipay（小米支付）
        if (lower.startsWith("mipay://")) return true;
        // 其他常见的第三方 scheme: mqq、sinaweibo 等也可按需添加
        return false;
    }

    /**
     * 处理支付/应用跳转 URL，尝试通过系统 Intent 打开。
     *
     * @return true 表示已拦截处理（WebView 不应再加载该 URL）；
     *         false 表示未处理（WebView 正常加载）。
     */
    public static boolean handlePaymentUrl(@NonNull Activity activity,
                                           @Nullable WebView webView,
                                           @NonNull String url) {
        if (!shouldIntercept(url)) return false;

        try {
            // intent:// URI 需要先解析
            if (url.toLowerCase().startsWith("intent://")) {
                return handleIntentUri(activity, url);
            }

            // 其他自定义 scheme 直接用 ACTION_VIEW 打开
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            Log.i(TAG, "已唤起外部应用: " + url);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "未找到可处理该 scheme 的应用: " + url, e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "处理 URL 失败: " + url, e);
            return false;
        }
    }

    /**
     * 解析 intent:// URI 并尝试启动。
     * 格式示例: intent://scan/#Intent;scheme=qr;package=com.tencent.mm;end
     */
    private static boolean handleIntentUri(@NonNull Activity activity, @NonNull String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            if (intent == null) return false;

            // 如果 intent 没有指定 Component 但有 Package，检查是否安装
            if (intent.getPackage() != null) {
                Intent launchIntent = activity.getPackageManager()
                        .getLaunchIntentForPackage(intent.getPackage());
                if (launchIntent != null) {
                    activity.startActivity(launchIntent);
                    Log.i(TAG, "已启动应用: " + intent.getPackage());
                    return true;
                }
                // 未安装，尝试跳转应用商店
                try {
                    Intent storeIntent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=" + intent.getPackage()));
                    activity.startActivity(storeIntent);
                    Log.i(TAG, "应用未安装，已跳转应用商店: " + intent.getPackage());
                    return true;
                } catch (ActivityNotFoundException ex) {
                    Log.w(TAG, "无法打开应用商店: " + intent.getPackage());
                    return false;
                }
            }

            // 有完整 Intent 信息，直接尝试启动
            if (intent.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(intent);
                Log.i(TAG, "已通过 intent URI 启动应用");
                return true;
            }

            // 尝试带 FLAG_ACTIVITY_NEW_TASK 再试一次
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "intent:// URI 启动失败: " + url, e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "解析 intent:// URI 失败: " + url, e);
            return false;
        }
    }

    /**
     * 创建一个已集成支付 scheme 处理的 WebViewClient。
     * 自动在 shouldOverrideUrlLoading 中拦截支付跳转，
     * 其余行为由调用方可在子类中继续覆写。
     */
    public static WebViewClient createWebViewClient(@NonNull Activity activity) {
        return new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (handlePaymentUrl(activity, view, url)) {
                    return true;
                }
                return super.shouldOverrideUrlLoading(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    String url = request.getUrl().toString();
                    if (handlePaymentUrl(activity, view, url)) {
                        return true;
                    }
                }
                return super.shouldOverrideUrlLoading(view, request);
            }
        };
    }
}
