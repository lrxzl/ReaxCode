package cn.net.xiangxiang.seeker;

import com.termux.R;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.shared.logger.Logger;

import java.util.Random;

public class FloatingWebView extends FrameLayout {

    private String specId = "";

    public void setSpecId(String specId) {
        this.specId = specId == null ? "" : "["+specId+"]";
    }

    public interface OnCloseListener {
        void onClosed(FloatingWebView webView);
    }

    public static final int FILE_CHOOSER_REQUEST_CODE_FLOATING = 10012;
    private static ValueCallback<Uri[]> sPendingFilePathCallback;

    private OnCloseListener onCloseListener;
    private static final float MIN_WIDTH_DP = 260f;
    private static final float MIN_HEIGHT_DP = 120f;
    private static final float DEFAULT_WIDTH_DP = 290f;
    private static final float DEFAULT_HEIGHT_DP = 460f;
    private static final float TITLE_BAR_HEIGHT_DP = 40f;
    private static final float MIN_VISIBLE_DP = 48f;

    private LinearLayout titleBar;
    private TextView titleText;
    private TextView btnMinimize, btnZoomIn, btnZoomOut, btnFullscreen, btnClose, btnSearch, btnRefresh;
    private WebView webView;
    private ImageView resizeHandle;

    private int screenWidth, screenHeight, statusBarHeight;
    private boolean isFullscreen = false;
    private boolean isMinimized = false;

    private int normalX, normalY, normalWidth, normalHeight;

    private float downX, downY;
    private int startX, startY;
    private boolean isMoving = false;
    private boolean isResizing = false;

    private long lastTitleClickTime = 0;
    private static final long DOUBLE_CLICK_INTERVAL = 300;

    // ===== 透明度管理 =====
    private static FloatingWebView sActiveWindow = null;
    private static final float ACTIVE_ALPHA = 1.0f;
    private static final float IDLE_ALPHA = 1.0f;
    private static final float INACTIVE_ALPHA = 0.9f;
    private static final long IDLE_TIMEOUT_MS = 10_000L;
    private final Handler idleHandler = new Handler();
    private final Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            if (sActiveWindow == FloatingWebView.this) {
                setAlpha(IDLE_ALPHA);
            }
        }
    };

    // ===== URL 历史记录 =====
    private static final String PREF_NAME = "floating_webview_history";
    private static final String KEY_HISTORY = "url_history";
    private static final int MAX_HISTORY = 50;

    public FloatingWebView(Context context) {
        super(context);
        init(context);
    }

    public WebView getWebView() {
        return webView;
    }

    public boolean isMinimized() {
        return isMinimized;
    }

    public void setOnCloseListener(OnCloseListener listener) {
        this.onCloseListener = listener;
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void init(Context context) {
        screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        statusBarHeight = getStatusBarHeight();

        // 窗口背景
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dpToPx(8));
        setBackground(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setElevation(dpToPx(8));
            setClipToOutline(true);
        }

        // 标题栏
        titleBar = new LinearLayout(context);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setBackgroundColor(Color.parseColor("#F0F0F0"));
        int titleH = dpToPx(TITLE_BAR_HEIGHT_DP);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, titleH));
        titleBar.setPadding(dpToPx(8), 0, dpToPx(4), 0);

        titleText = new TextView(context);
        titleText.setText("加载中...");
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleText.setTextColor(Color.BLACK);
        titleText.setMaxLines(1);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleBar.addView(titleText, titleParams);

        btnMinimize = createIconButton(context, R.drawable.ic_window_minimize, v -> toggleMinimize());
        btnZoomOut  = createButton(context, "－", v -> zoomOut());
        btnZoomIn   = createButton(context, "＋", v -> zoomIn());
        btnFullscreen = createIconButton(context, R.drawable.ic_window_maximize, v -> toggleFullscreen());
        btnClose    = createIconButton(context, R.drawable.ic_window_close, v -> close());

        btnSearch = createIconButton(context, R.drawable.ic_web_address, v -> {
            // 如果是最小化状态，先恢复窗口
            if (isMinimized) {
                toggleMinimize();
            }
            showAddressDialog();
        });

        // 刷新按钮
        btnRefresh = createIconButton(context, R.drawable.ic_refresh, v -> {
            if (webView != null) {
                webView.reload();
            }
        });

        titleBar.addView(btnSearch);
        titleBar.addView(btnRefresh);
        titleBar.addView(btnMinimize);
//        titleBar.addView(btnZoomOut);
//        titleBar.addView(btnZoomIn);
        titleBar.addView(btnFullscreen);
        titleBar.addView(btnClose);

        // WebView
        webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onReceivedTitle(WebView view, String title) {
                titleText.setText(title + specId );
            }

            // ===== HTML5 权限请求处理 =====
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                Logger.logDebug("FloatingWebView", "onPermissionRequest: " + request.getOrigin() + " resources: " + java.util.Arrays.toString(request.getResources()));
                // 按需请求运行时权限
                Activity activity = getActivity();
                if (activity instanceof com.termux.app.TermuxActivity) {
                    if (((com.termux.app.TermuxActivity) activity).requestCameraMicPermission(request)) {
                        request.grant(request.getResources());
                    }
                } else {
                    request.grant(request.getResources());
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (sPendingFilePathCallback != null) {
                    sPendingFilePathCallback.onReceiveValue(null);
                }
                sPendingFilePathCallback = filePathCallback;

                Intent intent;
                if (fileChooserParams != null && fileChooserParams.getAcceptTypes() != null
                    && fileChooserParams.getAcceptTypes().length > 0) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    String[] acceptTypes = fileChooserParams.getAcceptTypes();
                    if (acceptTypes.length == 1 && !"".equals(acceptTypes[0])) {
                        intent.setType(acceptTypes[0]);
                    } else if (acceptTypes.length > 1) {
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes);
                    } else {
                        intent.setType("*/*");
                    }
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                } else {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }

                Activity activity = getActivity();
                if (activity != null) {
                    try {
                        activity.startActivityForResult(
                            Intent.createChooser(intent, "选择文件"),
                            FILE_CHOOSER_REQUEST_CODE_FLOATING);
                        return true;
                    } catch (Exception e) {
                        if (sPendingFilePathCallback != null) {
                            sPendingFilePathCallback.onReceiveValue(null);
                            sPendingFilePathCallback = null;
                        }
                    }
                }
                return false;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (PaymentSchemeHandler.handlePaymentUrl(
                    (Activity) context, view, url)) {
                    return true;
                }
                return super.shouldOverrideUrlLoading(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    String url = request.getUrl().toString();
                    if (PaymentSchemeHandler.handlePaymentUrl(
                        (Activity) context, view, url)) {
                        return true;
                    }
                }
                return super.shouldOverrideUrlLoading(view, request);
            }
        });
        webView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                bringToFront();
                requestFocus();
            }
            return false;
        });
        // 内容布局
        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.addView(titleBar);
        contentLayout.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(contentLayout, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        // 右下角缩放把手
        resizeHandle = new ImageView(context);
        ((ImageView) resizeHandle).setImageResource(R.drawable.ic_resize_handle);
        resizeHandle.setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));
        LayoutParams handleParams = new LayoutParams(dpToPx(20), dpToPx(20));
        handleParams.gravity = Gravity.BOTTOM | Gravity.END;
        handleParams.bottomMargin = dpToPx(4);
        handleParams.rightMargin = dpToPx(4);
        addView(resizeHandle, handleParams);

        // 点击任意位置时确保焦点和置顶
        setOnTouchListener((v, event) -> {
            requestFocus();
            bringToFront();
            return false; // 不消费事件，让子View处理
        });

        // 在 webView = new WebView(context); 和 WebSettings 设置之后添加
        webView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                bringToFront();
                requestFocus();
            }
            return false; // 不消费事件，让 WebView 继续处理
        });

        setupTouchHandlers();

        // 初始位置与大小
        int defW = dpToPx(DEFAULT_WIDTH_DP);
        int defH = dpToPx(DEFAULT_HEIGHT_DP);
        int centerX = (screenWidth - defW) / 2;
        int centerY = (screenHeight - defH) / 2;
        Random random = new Random();
        int offsetX = (random.nextInt(51) + 50) * (random.nextBoolean() ? 1 : -1);
        int offsetY = (random.nextInt(51) + 50) * (random.nextBoolean() ? 1 : -1);
        setSizeAndPosition(defW, defH, centerX + offsetX, centerY + offsetY);
        saveNormalState();

        // 初始激活窗口
        activateWindow();
    }

    // ---------- 透明度管理 ----------

    private void activateWindow() {
        // 取消前一个激活窗口的激活状态
        if (sActiveWindow != null && sActiveWindow != this) {
            sActiveWindow.setAlpha(INACTIVE_ALPHA);
            sActiveWindow.idleHandler.removeCallbacks(sActiveWindow.idleRunnable);
        }
        sActiveWindow = this;
        setAlpha(ACTIVE_ALPHA);
        idleHandler.removeCallbacks(idleRunnable);
        idleHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            activateWindow();
        } else if (action == MotionEvent.ACTION_MOVE && sActiveWindow == this) {
            // 连续交互时重置空闲计时器
            idleHandler.removeCallbacks(idleRunnable);
            idleHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS);
        }
        return super.dispatchTouchEvent(ev);
    }

    // ---------- 窗口控制 ----------

    /**
     * 在 Activity.onActivityResult 中调用，处理浮动 WebView 的文件选择结果。
     */
    public static boolean handleFileChooserResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER_REQUEST_CODE_FLOATING || sPendingFilePathCallback == null) {
            return false;
        }

        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri resultUri = data.getData();
            if (resultUri != null) {
                results = new Uri[]{resultUri};
            }
            ClipData clipData = data.getClipData();
            if (clipData != null && clipData.getItemCount() > 0) {
                results = new Uri[clipData.getItemCount()];
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    results[i] = clipData.getItemAt(i).getUri();
                }
            }
        }

        sPendingFilePathCallback.onReceiveValue(results);
        sPendingFilePathCallback = null;
        return true;
    }

    private Activity getActivity() {
        Context context = getContext();
        while (context instanceof Context) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            if (context instanceof android.content.ContextWrapper) {
                context = ((android.content.ContextWrapper) context).getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }

    // ---------- URL 历史记录 ----------

    private void saveUrlToHistory(String url) {
        if (url == null || url.isEmpty()) return;
        android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        java.util.List<String> list = new java.util.ArrayList<>();
        String json = prefs.getString(KEY_HISTORY, "");
        if (!json.isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    list.add(arr.getString(i));
                }
            } catch (Exception e) {
                // ignore
            }
        }
        list.remove(url);
        list.add(0, url);
        while (list.size() > MAX_HISTORY) {
            list.remove(list.size() - 1);
        }
        org.json.JSONArray arr = new org.json.JSONArray(list);
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply();
    }

    private java.util.List<String> getHistoryUrls() {
        java.util.List<String> list = new java.util.ArrayList<>();
        android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_HISTORY, "");
        if (!json.isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    list.add(arr.getString(i));
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return list;
    }

    // ---------- 地址栏对话框（带历史记录自动补全）----------

    private void showAddressDialog() {
        // 获取当前 WebView 的 URL 作为默认值
        String currentUrl = webView.getUrl();
        if (currentUrl == null) currentUrl = "";

        // 使用 AlertDialog + EditText + ListView（历史记录下拉提示）
        android.widget.EditText input = new android.widget.EditText(getContext());
        input.setHint("https://");
        input.setSelectAllOnFocus(true);

        // 获取历史记录
        final java.util.List<String> historyUrls = getHistoryUrls();

        // 创建适配器（使用可变的 ArrayList 副本）
        final android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
            getContext(), android.R.layout.simple_list_item_1, new java.util.ArrayList<>(historyUrls));

        // 对话框内容布局
        LinearLayout dialogContent = new LinearLayout(getContext());
        dialogContent.setOrientation(LinearLayout.VERTICAL);

        dialogContent.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final android.widget.ListView listView = new android.widget.ListView(getContext());
        listView.setAdapter(adapter);

        // 仅当有历史记录时才显示列表
        if (!historyUrls.isEmpty()) {
            LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(200));
            dialogContent.addView(listView, listParams);
        }

        // 输入文本变化时筛选列表
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase().trim();
                adapter.clear();
                for (String url : historyUrls) {
                    if (url.toLowerCase().contains(query)) {
                        adapter.add(url);
                    }
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        // 点击历史记录项时填充输入框
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selectedUrl = adapter.getItem(position);
            if (selectedUrl != null) {
                input.setText(selectedUrl);
                input.setSelection(selectedUrl.length());
            }
        });

        // 设置初始文本（会触发 TextWatcher 进行筛选）
        input.setText(currentUrl);
        input.setSelection(currentUrl.length());

        new android.app.AlertDialog.Builder(getContext())
            .setTitle("输入网址")
            .setView(dialogContent)
            .setPositiveButton("加载", (dialog, which) -> {
                String url = input.getText().toString().trim();
                if (url.isEmpty()) return;
                // 自动补充协议
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }
                loadUrl(url);
                // 加载后确保焦点和层级
                requestFocus();
                bringToFront();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void zoomIn() {
        if (isFullscreen || isMinimized) return;
        int newW = (int) (getWidth() * 1.2);
        int newH = (int) (getHeight() * 1.2);
        newW = Math.max(dpToPx(MIN_WIDTH_DP), Math.min(newW, screenWidth));
        newH = Math.max(dpToPx(MIN_HEIGHT_DP), Math.min(newH, screenHeight));
        setSizeAndPosition(newW, newH, getLeft(), getTop());
        saveNormalState();
    }

    private void zoomOut() {
        if (isFullscreen || isMinimized) return;
        int newW = (int) (getWidth() * 0.8);
        int newH = (int) (getHeight() * 0.8);
        newW = Math.max(dpToPx(MIN_WIDTH_DP), newW);
        newH = Math.max(dpToPx(MIN_HEIGHT_DP), newH);
        setSizeAndPosition(newW, newH, getLeft(), getTop());
        saveNormalState();
    }

    private void toggleMinimize() {
        if (isFullscreen) return;
        if (isMinimized) {
            setSizeAndPosition(normalWidth, normalHeight, getLeft(), getTop());
            webView.setVisibility(VISIBLE);
            resizeHandle.setVisibility(VISIBLE);
            isMinimized = false;
            btnMinimize.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_window_minimize, 0, 0, 0);
        } else {
            saveNormalState();
            webView.setVisibility(GONE);
            resizeHandle.setVisibility(GONE);
            int titleH = dpToPx(TITLE_BAR_HEIGHT_DP);
            setSizeAndPosition(getWidth(), titleH, getLeft(), getTop());
            isMinimized = true;
            btnMinimize.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_window_minimize_expanded, 0, 0, 0);
        }
    }

    private void onTitleDoubleClick() {
        if (isMinimized) {
            toggleMinimize();
            return;
        }
        toggleFullscreen();
    }

    private void toggleFullscreen() {
        if (isMinimized) {
            toggleMinimize(); // 先恢复普通状态
        }
        if (isFullscreen) {
            // 恢复普通尺寸（位置和大小均已保存）
            setSizeAndPosition(normalWidth, normalHeight, normalX, normalY);
            isFullscreen = false;
            btnFullscreen.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_window_maximize, 0, 0, 0);
            resizeHandle.setVisibility(VISIBLE);
        } else {
            if (!isMinimized) saveNormalState();
            // 全屏：从父容器（decorView）获取实际尺寸，避免底部漏空
            ViewGroup parent = (ViewGroup) getParent();
            int actualWidth = (parent != null && parent.getWidth() > 0) ? parent.getWidth() : screenWidth;
            int actualHeight = (parent != null && parent.getHeight() > 0) ? parent.getHeight() : screenHeight;
            setSizeAndPosition(actualWidth, actualHeight - statusBarHeight, 0, statusBarHeight);
            isFullscreen = true;
            btnFullscreen.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_window_restore, 0, 0, 0);
            resizeHandle.setVisibility(GONE);
        }
    }

    private void close() {
        // 清理透明度管理
        idleHandler.removeCallbacks(idleRunnable);
        if (sActiveWindow == this) {
            sActiveWindow = null;
        }

        if (onCloseListener != null) {
            onCloseListener.onClosed(this);
        }
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) {
            parent.removeView(this);
        }
        webView.destroy();
    }

    public void loadUrl(String url) {
        saveUrlToHistory(url);
        webView.loadUrl(url);
    }

    // ---------- 触摸事件 ----------

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchHandlers() {
        // 标题栏拖拽 + 双击检测
        titleBar.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = getLeft();
                    startY = getTop();
                    isMoving = true;

                    // 双击检测
                    long now = System.currentTimeMillis();
                    if (now - lastTitleClickTime < DOUBLE_CLICK_INTERVAL) {
                        onTitleDoubleClick();
                        isMoving = false;
                        lastTitleClickTime = 0;
                        return true;
                    }
                    lastTitleClickTime = now;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!isMoving || isFullscreen) return false;
                    int dx = (int) (event.getRawX() - downX);
                    int dy = (int) (event.getRawY() - downY);
                    int newX = startX + dx;
                    int newY = startY + dy;

                    // 允许部分移出屏幕，但至少保留 48dp 可见
                    int minVisible = dpToPx(MIN_VISIBLE_DP);
                    int maxX = screenWidth - minVisible;
                    int maxY = screenHeight - minVisible;
                    int minX = -getWidth() + minVisible;
                    int minY = -getHeight() + minVisible;
                    newX = Math.max(minX, Math.min(newX, maxX));
                    newY = Math.max(minY, Math.min(newY, maxY));

                    setSizeAndPosition(getWidth(), getHeight(), newX, newY);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isMoving && !isFullscreen) {
                        saveNormalState();
                    }
                    isMoving = false;
                    return true;
            }
            return false;
        });

        // 右下角缩放把手
        resizeHandle.setOnTouchListener((v, event) -> {
            if (isFullscreen || isMinimized) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = getWidth();
                    startY = getHeight();
                    isResizing = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!isResizing) return false;
                    int newW = startX + (int) (event.getRawX() - downX);
                    int newH = startY + (int) (event.getRawY() - downY);
                    newW = Math.max(dpToPx(MIN_WIDTH_DP), Math.min(newW, screenWidth - getLeft()));
                    newH = Math.max(dpToPx(MIN_HEIGHT_DP), Math.min(newH, screenHeight - getTop()));
                    setSizeAndPosition(newW, newH, getLeft(), getTop());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isResizing = false;
                    saveNormalState();
                    return true;
            }
            return false;
        });
    }

    // ---------- 工具方法 ----------

    private void saveNormalState() {
        if (!isFullscreen && !isMinimized) {
            normalX = getLeft();
            normalY = getTop();
            normalWidth = getWidth();
            normalHeight = getHeight();
        }
    }

    private void setSizeAndPosition(int width, int height, int left, int top) {
        LayoutParams params = (LayoutParams) getLayoutParams();
        if (params == null) {
            params = new LayoutParams(width, height);
            params.gravity = Gravity.TOP | Gravity.START;
        }
        params.width = width;
        params.height = height;
        params.leftMargin = left;
        params.topMargin = top;
        setLayoutParams(params);
        // 每次布局变化后都置顶
        bringToFront();
    }

    private int getStatusBarHeight() {
        int resId = getContext().getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) return getContext().getResources().getDimensionPixelSize(resId);
        return 0;
    }

    @SuppressLint("NewApi")
    private TextView createButton(Context context, String symbol, View.OnClickListener listener) {
        TextView btn = new TextView(context);
        btn.setText(symbol);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        btn.setTextColor(Color.DKGRAY);
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundColor(Color.TRANSPARENT);
        int size = dpToPx(36);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.gravity = Gravity.CENTER_VERTICAL;
        btn.setLayoutParams(params);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(listener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int[] attrs = new int[]{android.R.attr.selectableItemBackgroundBorderless};
            android.content.res.TypedArray ta = context.obtainStyledAttributes(attrs);
            btn.setBackground(ta.getDrawable(0));
            ta.recycle();
        }
        return btn;
    }

    @SuppressLint("NewApi")
    private TextView createIconButton(Context context, int drawableRes, View.OnClickListener listener) {
        TextView btn = new TextView(context);
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundColor(Color.TRANSPARENT);
        int size = dpToPx(36);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.gravity = Gravity.CENTER_VERTICAL;
        btn.setLayoutParams(params);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(listener);
        // 设置图标作为 compound drawable（左侧）
        android.graphics.drawable.Drawable icon = context.getResources().getDrawable(drawableRes);
        icon.setBounds(0, 0, dpToPx(20), dpToPx(20));
        btn.setCompoundDrawables(icon, null, null, null);
        btn.setCompoundDrawablePadding(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int[] attrs = new int[]{android.R.attr.selectableItemBackgroundBorderless};
            android.content.res.TypedArray ta = context.obtainStyledAttributes(attrs);
            btn.setBackground(ta.getDrawable(0));
            ta.recycle();
        }
        return btn;
    }

    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
            getResources().getDisplayMetrics());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        idleHandler.removeCallbacks(idleRunnable);
        if (sActiveWindow == this) {
            sActiveWindow = null;
        }
        webView.destroy();
    }
}
