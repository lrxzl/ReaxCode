package cn.net.xiangxiang.seeker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.R;

/**
 * WebView前台服务 - 保持WebView在后台运行
 * <p>
 * 职责：
 * 1. 以前台服务方式运行，显示通知栏通知
 * 2. 保持WebView进程不被系统杀死
 * 3. 提供打开/关闭WebViewActivity的快捷方式
 * </p>
 */
public class WebViewService extends Service {

    private static final String LOG_TAG = "WebViewService";
    private static final String CHANNEL_ID = "webview_service_channel";
    private static final int NOTIFICATION_ID = 9527;

    public static final String ACTION_START = "cn.net.xiangxiang.seeker.ACTION_WEBVIEW_SERVICE_START";
    public static final String ACTION_STOP = "cn.net.xiangxiang.seeker.ACTION_WEBVIEW_SERVICE_STOP";

    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        public final WebViewService service = WebViewService.this;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        setupNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 启动前台服务
        startForeground(NOTIFICATION_ID, buildNotification());

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "WebView 服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持WebView在后台运行");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        // 点击通知打开WebViewActivity
        Intent openIntent = new Intent(this, WebViewActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 停止服务的Action
        Intent stopIntent = new Intent(this, WebViewService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WebView 运行中")
            .setContentText("点击打开WebView")
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "停止", stopPendingIntent)
            .build();
    }

    /**
     * 启动WebView服务
     */
    public static void startService(Context context) {
        Intent intent = new Intent(context, WebViewService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * 停止WebView服务
     */
    public static void stopService(Context context) {
        Intent intent = new Intent(context, WebViewService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }
}
