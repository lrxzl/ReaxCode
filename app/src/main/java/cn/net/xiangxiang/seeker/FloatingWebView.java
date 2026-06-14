package cn.net.xiangxiang.seeker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingWebView extends FrameLayout {

    public interface OnCloseListener {
        void onClosed(FloatingWebView webView);
    }

    private OnCloseListener onCloseListener;
    private static final float MIN_WIDTH_DP = 200f;
    private static final float MIN_HEIGHT_DP = 120f;
    private static final float DEFAULT_WIDTH_DP = 240f;
    private static final float DEFAULT_HEIGHT_DP = 400f;
    private static final float TITLE_BAR_HEIGHT_DP = 40f;
    private static final float MIN_VISIBLE_DP = 48f;

    private LinearLayout titleBar;
    private TextView titleText;
    private TextView btnMinimize, btnZoomIn, btnZoomOut, btnFullscreen, btnClose, btnSearch;
    private WebView webView;
    private View resizeHandle;

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

    public FloatingWebView(Context context) {
        super(context);
        init(context);
    }

    public WebView getWebView() {
        return webView;
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

        btnMinimize = createButton(context, "▁", v -> toggleMinimize());
        btnZoomOut  = createButton(context, "－", v -> zoomOut());
        btnZoomIn   = createButton(context, "＋", v -> zoomIn());
        btnFullscreen = createButton(context, "🗖", v -> toggleFullscreen());
        btnClose    = createButton(context, "✕", v -> close());

        btnSearch = createButton(context, "🔍", v -> {
            // 如果是最小化状态，先恢复窗口
            if (isMinimized) {
                toggleMinimize();
            }
            showAddressDialog();
        });
        titleBar.addView(btnSearch);

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
        settings.setSupportZoom(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                titleText.setText(title);
            }
        });

        webView.setWebViewClient(new WebViewClient());
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
        resizeHandle = new View(context);
        resizeHandle.setBackgroundColor(Color.parseColor("#A0A0A0"));
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
        setSizeAndPosition(defW, defH, centerX, centerY);
        saveNormalState();
    }

    // ---------- 窗口控制 ----------

    // ---------- 新增地址栏对话框方法 ----------
    private void showAddressDialog() {
        // 获取当前 WebView 的 URL 作为默认值
        String currentUrl = webView.getUrl();
        if (currentUrl == null) currentUrl = "";

        // 使用 AlertDialog + EditText
        android.widget.EditText input = new android.widget.EditText(getContext());
        input.setText(currentUrl);
        input.setHint("https://");
        input.setSelectAllOnFocus(true);

        new android.app.AlertDialog.Builder(getContext())
            .setTitle("输入网址")
            .setView(input)
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
            btnMinimize.setText("▁");
        } else {
            saveNormalState();
            webView.setVisibility(GONE);
            resizeHandle.setVisibility(GONE);
            int titleH = dpToPx(TITLE_BAR_HEIGHT_DP);
            setSizeAndPosition(getWidth(), titleH, getLeft(), getTop());
            isMinimized = true;
            btnMinimize.setText("⬒");
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
            btnFullscreen.setText("🗖");
            resizeHandle.setVisibility(VISIBLE);
        } else {
            if (!isMinimized) saveNormalState();
            // 全屏：避开状态栏，从 (0, statusBarHeight) 开始
            setSizeAndPosition(screenWidth, screenHeight - statusBarHeight, 0, statusBarHeight);
            isFullscreen = true;
            btnFullscreen.setText("🗗");
            resizeHandle.setVisibility(GONE);
        }
    }

    private void close() {
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

    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
            getResources().getDisplayMetrics());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        webView.destroy();
    }
}
