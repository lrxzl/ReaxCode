package cn.net.xiangxiang.seeker;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.widget.FrameLayout;
import android.widget.TextView;

public class FloatingToggleButton extends FrameLayout {

    public interface OnToggleClickListener {
        void onToggleClick(boolean isShowingWebView);
    }

    private static final float BUTTON_SIZE_DP = 48f;
    private static final float MARGIN_DP = 8f;
    private static final int CLICK_THRESHOLD_DP = 10;
    private static final int SNAP_ANIM_DURATION_MS = 250;

    private TextView iconView;
    private OnToggleClickListener listener;
    private boolean isShowingWebView = true;

    private float downX, downY;
    private int startX, startY;
    private boolean isMoving = false;

    private int screenWidth, screenHeight;
    private int buttonSize;
    private int halfHiddenOffset;
    private boolean isSnappedToEdge = false;

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

    @SuppressLint("ClickableViewAccessibility")
    private void init(Context context) {
        screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        buttonSize = dpToPx(BUTTON_SIZE_DP);
        halfHiddenOffset = buttonSize / 2;

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
                        isSnappedToEdge = false;
                        int newX = startX + (int) dx;
                        int newY = startY + (int) dy;
                        newX = Math.max(0, Math.min(newX, screenWidth - buttonSize));
                        newY = Math.max(0, Math.min(newY, screenHeight - buttonSize));
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
                    snapToEdge();
                    return true;
            }
            return false;
        });
    }

    public void snapToEdge() {
        snapToEdge(false);
    }

    public void snapToEdge(boolean centerVertically) {
        int currentCenterX = getLeft() + buttonSize / 2;
        boolean snapRight = currentCenterX >= screenWidth / 2;
        int targetX = snapRight ? screenWidth - halfHiddenOffset : -halfHiddenOffset;

        LayoutParams p = (LayoutParams) getLayoutParams();
        int fromX = p.leftMargin;
        int fromY = p.topMargin;
        int targetY = centerVertically ? (screenHeight - buttonSize) / 2 : fromY;

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(SNAP_ANIM_DURATION_MS);
        anim.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            LayoutParams lp = (LayoutParams) getLayoutParams();
            lp.leftMargin = fromX + (int) ((targetX - fromX) * fraction);
            lp.topMargin = fromY + (int) ((targetY - fromY) * fraction);
            lp.gravity = Gravity.TOP | Gravity.START;
            setLayoutParams(lp);
        });
        anim.start();
    }

    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
            getResources().getDisplayMetrics());
    }
}
