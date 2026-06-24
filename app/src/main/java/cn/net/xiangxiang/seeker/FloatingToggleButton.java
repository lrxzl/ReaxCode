package cn.net.xiangxiang.seeker;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

public class FloatingToggleButton extends FrameLayout {

    public interface OnToggleClickListener {
        void onToggleClick(boolean isShowingWebView);
    }

    private static final float BUTTON_SIZE_DP = 48f;
    private static final float MARGIN_DP = 16f;
    private static final int CLICK_THRESHOLD_DP = 10;

    private TextView iconView;
    private OnToggleClickListener listener;
    private boolean isShowingWebView = true;

    private float downX, downY;
    private int startX, startY;
    private boolean isMoving = false;

    private int screenWidth, screenHeight;

    public FloatingToggleButton(Context context) {
        super(context);
        init(context);
    }

    public void setOnToggleClickListener(OnToggleClickListener listener) {
        this.listener = listener;
    }

    public void updateState(boolean showingWebView) {
        this.isShowingWebView = showingWebView;
        updateIcon();
    }

    private void updateIcon() {
        iconView.setText(isShowingWebView ? ">" : "H");
        iconView.setContentDescription(isShowingWebView ? "切换到终端" : "切换到主页");
    }

    private void init(Context context) {
        screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        screenHeight = context.getResources().getDisplayMetrics().heightPixels;

        int buttonSize = dpToPx(BUTTON_SIZE_DP);
        int margin = dpToPx(MARGIN_DP);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#CC333333"));
        setBackground(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setElevation(dpToPx(6));
        }

        iconView = new TextView(context);
        iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        iconView.setTextColor(Color.WHITE);
        iconView.setGravity(Gravity.CENTER);
        iconView.setFocusable(false);
        iconView.setClickable(false);
        addView(iconView, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        updateIcon();

        LayoutParams params = new LayoutParams(buttonSize, buttonSize);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.rightMargin = margin;
        params.bottomMargin = margin;
        setLayoutParams(params);

        setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = getLeft();
                    startY = getTop();
                    isMoving = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.abs(dx) > CLICK_THRESHOLD_DP || Math.abs(dy) > CLICK_THRESHOLD_DP) {
                        isMoving = true;
                    }
                    if (isMoving) {
                        int newX = startX + (int) dx;
                        int newY = startY + (int) dy;
                        newX = Math.max(0, Math.min(newX, screenWidth - getWidth()));
                        newY = Math.max(0, Math.min(newY, screenHeight - getHeight()));
                        LayoutParams p = (LayoutParams) getLayoutParams();
                        p.leftMargin = newX;
                        p.topMargin = newY;
                        p.gravity = Gravity.TOP | Gravity.START;
                        setLayoutParams(p);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!isMoving && listener != null) {
                        listener.onToggleClick(isShowingWebView);
                    }
                    isMoving = false;
                    return true;
            }
            return false;
        });
    }

    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
            getResources().getDisplayMetrics());
    }
}
