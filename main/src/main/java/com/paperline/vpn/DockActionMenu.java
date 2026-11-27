package com.paperline.vpn;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/** Keeps the original finger gesture active while the two connection actions are visible. */
final class DockActionMenu extends FrameLayout {
    private final Activity activity;
    private final View trigger, panel, quickConnect, disconnect;
    private final int maximumWidth;
    private final int[] position = new int[2];
    private View hovered;
    private boolean touching;
    private Object backCallback;

    DockActionMenu(Activity activity,View trigger,View panel,View quickConnect,View disconnect,int maximumWidth) {
        super(activity);
        this.activity = activity;
        this.trigger = trigger;
        this.panel = panel;
        this.quickConnect = quickConnect;
        this.disconnect = disconnect;
        this.maximumWidth = maximumWidth;
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setOnClickListener(view -> close());
        panel.setClickable(true);
        if (Build.VERSION.SDK_INT >= 28) panel.setAccessibilityPaneTitle("Connection actions");
        addView(panel,new FrameLayout.LayoutParams(maximumWidth,-2,Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        trigger.setOnTouchListener(this::track);
        trigger.setOnLongClickListener(view -> {
            if (!isAttachedToWindow() || !hasWindowFocus()) return false;
            open();
            return true;
        });
    }

    void setAvailability(boolean canConnect,boolean canDisconnect) {
        quickConnect.setEnabled(canConnect);
        disconnect.setEnabled(canDisconnect);
        if (hovered != null && !hovered.isEnabled()) hovered = null;
        markHovered(hovered);
        if (!canConnect && !canDisconnect) close();
    }

    void close() {
        if (getVisibility() != VISIBLE) return;
        panel.animate().cancel();
        setVisibility(GONE);
        markHovered(null);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback)backCallback);
            backCallback = null;
        }
    }

    private void open() {
        if (getVisibility() == VISIBLE) return;
        markHovered(null);
        setVisibility(VISIBLE);
        if (ValueAnimator.areAnimatorsEnabled()) {
            panel.setAlpha(.55f);
            panel.animate().alpha(1f).setDuration(150).start();
        } else panel.setAlpha(1f);
        if (Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback callback = this::close;
            backCallback = callback;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);
        }
        if (!touching) (quickConnect.isEnabled() ? quickConnect : disconnect).requestFocus();
    }

    @Override protected void onDetachedFromWindow() {
        close();
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int width,int height) {
        int available = Math.max(0,MeasureSpec.getSize(width) - getPaddingLeft() - getPaddingRight());
        panel.getLayoutParams().width = Math.min(maximumWidth,available);
        super.onMeasure(width,height);
    }

    private boolean track(View source,MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                return false;
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                close();
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                touching = false;
                close();
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                source.onTouchEvent(cancel);
                cancel.recycle();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!touching || getVisibility() != VISIBLE) return false;
                markHovered(choiceAt(event));
                return true;
            case MotionEvent.ACTION_UP:
                boolean dragging = touching && getVisibility() == VISIBLE;
                touching = false;
                if (!dragging) return false;
                View choice = choiceAt(event);
                // Let the trigger clear its pressed state after the consumed long press.
                source.onTouchEvent(event);
                if (choice != null) choice.performClick();
                else if (!contains(trigger,event)) close();
                return true;
            default:
                return false;
        }
    }

    private View choiceAt(MotionEvent event) {
        if (quickConnect.isEnabled() && contains(quickConnect,event)) return quickConnect;
        if (disconnect.isEnabled() && contains(disconnect,event)) return disconnect;
        return null;
    }

    private boolean contains(View view,MotionEvent event) {
        view.getLocationOnScreen(position);
        float x = event.getRawX() - position[0], y = event.getRawY() - position[1];
        return x >= 0 && y >= 0 && x < view.getWidth() && y < view.getHeight();
    }

    private void markHovered(View choice) {
        hovered = choice;
        quickConnect.setSelected(choice == quickConnect);
        disconnect.setSelected(choice == disconnect);
        quickConnect.setAlpha(alpha(quickConnect));
        disconnect.setAlpha(alpha(disconnect));
    }

    private float alpha(View choice) {
        if (!choice.isEnabled()) return .42f;
        return hovered == null || hovered == choice ? 1f : .62f;
    }
}
