package cn.net.xiangxiang.seeker;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import com.termux.shared.logger.Logger;

/**
 * WebView 统一下载处理器
 *
 * 为项目所有 WebView 提供统一的下载链接处理能力。
 * 内部使用系统 DownloadManager 进行下载（自动携带 Cookie 与 User-Agent，
 * 通知栏显示进度），DownloadManager 失败时兜底交给系统浏览器打开。
 *
 * <p>使用方式：</p>
 * <pre>
 *     DownloadHandler.attach(webView); // 在 WebView 初始化后调用一次即可
 * </pre>
 */
public final class DownloadHandler {

    private static final String LOG_TAG = "DownloadHandler";

    private DownloadHandler() {
        throw new AssertionError("Utility class, do not instantiate");
    }

    /**
     * 为指定 WebView 设置下载监听器。
     *
     * @param webView 目标 WebView
     */
    public static void attach(WebView webView) {
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            Context context = webView.getContext();
            try {
                Logger.logDebug(LOG_TAG, "收到下载请求: " + url);

                // 根据响应头/URL 猜测文件名
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);

                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));

                // 携带 Cookie（保证需要登录态的下载链接可用）
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isEmpty()) {
                    request.addRequestHeader("Cookie", cookie);
                }

                // 携带 User-Agent（部分服务器校验 UA）
                if (userAgent != null && !userAgent.isEmpty()) {
                    request.addRequestHeader("User-Agent", userAgent);
                }

                if (mimetype != null && !mimetype.isEmpty()) {
                    request.setMimeType(mimetype);
                }

                request.setTitle(fileName);
                request.setDescription("正在下载 " + fileName);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);

                DownloadManager dm =
                        (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(context, "开始下载：" + fileName, Toast.LENGTH_SHORT).show();
                } else {
                    // 拿不到 DownloadManager 服务，兜底用浏览器
                    openInBrowser(context, url);
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG,
                        "DownloadManager 处理失败，改用浏览器打开: " + e.getMessage());
                // 兜底：blob:/data: 等特殊协议或异常情况交给系统浏览器
                openInBrowser(context, url);
            }
        });
    }

    /** 用系统浏览器打开链接（兜底方案） */
    private static void openInBrowser(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "无法打开浏览器处理下载: " + e.getMessage());
            Toast.makeText(context, "无法处理该下载链接", Toast.LENGTH_SHORT).show();
        }
    }
}
