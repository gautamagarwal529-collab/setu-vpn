package com.paperline.vpn;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A top-edge gesture and its glass refresh indicator. */
final class GlassRefreshScroll extends ScrollView {
    private static final int ACCESSIBILITY_REFRESH = 0x7f0a4741;
    private static final OvershootInterpolator RELEASE = new OvershootInterpolator(.85f);
    private static final OvershootInterpolator REBOUND = new OvershootInterpolator(1.2f);
    private static final DecelerateInterpolator IMPACT = new DecelerateInterpolator();
    private final float density;
    private final int slop, threshold;
    private final Runnable refresh;
    private final ThemeTransition colors;
    private final LinearLayout indicator;
    private final RefreshMark mark;
    private final TextView label;
    private int pointer = -1;
    private float startX, startY, previousY, distance, edgeOffset;
    private int edgeVelocity;
    private long flingStarted;
    private boolean eligible, dragging, refreshing;
    private ValueAnimator settle, spin, edgeMotion;

    GlassRefreshScroll(Context context,ThemeTransition colors,Runnable refresh) {
        super(context);
        this.refresh = refresh;
        this.colors = colors;
        GlassTheme light = ThemeTransition.LIGHT, dark = ThemeTransition.DARK;
        density = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        threshold = dp(67);
        setOverScrollMode(OVER_SCROLL_NEVER);

        indicator = new LinearLayout(context);
        indicator.setGravity(Gravity.CENTER_VERTICAL);
        indicator.setPadding(dp(12),0,dp(14),0);
        ThemeTransition.Blended background = new ThemeTransition.Blended(
                light.surface(GlassTheme.Surface.DOCK,dp(20),density),
                dark.surface(GlassTheme.Surface.DOCK,dp(20),density),false);
        colors.background(indicator,background,background::setNight);
        colors.bind(indicator,night -> indicator.setElevation(dp(5) * (1f - night)));
        indicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        indicator.setVisibility(INVISIBLE);
        mark = new RefreshMark(context);
        colors.bind(mark,night -> mark.invalidate());
        indicator.addView(mark,new LinearLayout.LayoutParams(dp(23),dp(25)));
        label = new TextView(context);
        colors.bind(label,night -> label.setTextColor(ThemeTransition.color(light.ink,dark.ink,night)));
        label.setTextSize(13);
        label.setSingleLine(true);
        label.setPadding(dp(7),0,0,0);
        indicator.addView(label,new LinearLayout.LayoutParams(-2,-2));
    }

    View indicator() { return indicator; }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) track(event);
        if (action == MotionEvent.ACTION_MOVE && startPull(event)) return true;
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) track(event);
        if (action == MotionEvent.ACTION_MOVE && (dragging || startPull(event))) {
            if (!followPull(event)) finishPull(false);
            return true;
        }
        if (dragging && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer))) {
            finishPull(action == MotionEvent.ACTION_UP && followPull(event) && distance >= threshold);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) return scrollWithRebound(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            eligible = false;
            if (edgeOffset != 0f) rebound(edgeOffset,false);
        }
        return dragging || super.onTouchEvent(event);
    }

    private void track(MotionEvent event) {
        eligible = !refreshing && !canScrollVertically(-1);
        pointer = event.getPointerId(0);
        startX = event.getX();
        startY = event.getY();
        previousY = startY;
        edgeVelocity = 0;
        clearEdgeMotion();
    }

    private boolean startPull(MotionEvent event) {
        if (!eligible) return false;
        int index = event.findPointerIndex(pointer);
        if (index < 0 || canScrollVertically(-1)) {
            eligible = false;
            return false;
        }
        float dx = Math.abs(event.getX(index) - startX);
        float dy = event.getY(index) - startY;
        if (dy < -slop || dx > Math.max(slop,dy)) eligible = false;
        if (!eligible || dy <= slop) return false;
        dragging = true;
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.onTouchEvent(cancel);
        cancel.recycle();
        if (settle != null) settle.cancel();
        followPull(event);
        return true;
    }

    private boolean followPull(MotionEvent event) {
        int index = event.findPointerIndex(pointer);
        if (index < 0) return false;
        float pull = Math.max(0f,event.getY(index) - startY - slop);
        position(dp(126) * pull / (pull + dp(102)));
        return true;
    }

    private void finishPull(boolean commit) {
        dragging = false;
        eligible = false;
        if (commit) refresh.run();
        else settleTo(0f);
    }

    private boolean scrollWithRebound(MotionEvent event) {
        int before = getScrollY();
        boolean handled = super.onTouchEvent(event);
        int index = event.findPointerIndex(pointer);
        if (index < 0 || refreshing || !ValueAnimator.areAnimatorsEnabled()) return handled;
        float y = event.getY(index);
        float delta = y - previousY;
        previousY = y;
        int direction = delta < 0f ? 1 : -1;
        boolean vertical = Math.abs(y - startY) > Math.max(slop,Math.abs(event.getX(index) - startX));
        if (!canScrollVertically(direction) && vertical) {
            float leftover = delta + getScrollY() - before;
            float limit = dp(28);
            float stretch = edgeOffset + leftover * .26f * (1f - Math.min(1f,Math.abs(edgeOffset) / limit));
            setEdgeOffset(Math.max(-limit,Math.min(limit,stretch)));
        } else if (edgeOffset != 0f) clearEdgeMotion();
        return handled;
    }

    @Override public void fling(int velocityY) {
        edgeVelocity = velocityY;
        flingStarted = SystemClock.uptimeMillis();
        super.fling(velocityY);
        checkFlingEdge();
    }

    @Override protected void onScrollChanged(int x,int y,int oldX,int oldY) {
        super.onScrollChanged(x,y,oldX,oldY);
        checkFlingEdge();
    }

    private void checkFlingEdge() {
        if (edgeVelocity == 0 || refreshing || dragging || !ValueAnimator.areAnimatorsEnabled()) return;
        long elapsed = SystemClock.uptimeMillis() - flingStarted;
        if (elapsed > 1450 || Math.abs(edgeVelocity) < dp(720)) {
            edgeVelocity = 0;
            return;
        }
        int direction = edgeVelocity > 0 ? 1 : -1;
        if (canScrollVertically(direction)) return;
        float strength = Math.max(dp(8),Math.min(dp(32),Math.abs(edgeVelocity) / 125f) * (1f - elapsed / 1800f));
        edgeVelocity = 0;
        rebound(-direction * strength,true);
    }

    private void rebound(float peak,boolean impact) {
        if (edgeMotion != null) edgeMotion.cancel();
        float start = edgeOffset;
        float kick = impact ? .22f : 0f;
        edgeMotion = ValueAnimator.ofFloat(0f,1f);
        edgeMotion.setInterpolator(new LinearInterpolator());
        edgeMotion.setDuration(impact ? 405 : 335);
        edgeMotion.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            float offset = progress < kick ? start + (peak - start) * IMPACT.getInterpolation(progress / kick)
                    : peak * (1f - REBOUND.getInterpolation((progress - kick) / (1f - kick)));
            setEdgeOffset(offset);
        });
        edgeMotion.start();
    }

    private void clearEdgeMotion() {
        if (edgeMotion != null) edgeMotion.cancel();
        if (edgeOffset != 0f) setEdgeOffset(0f);
    }

    private void setEdgeOffset(float offset) {
        edgeOffset = offset;
        translateContent();
    }

    private void translateContent() {
        if (getChildCount() != 0) getChildAt(0).setTranslationY(distance + edgeOffset);
    }

    void setRefreshing(boolean active) {
        if (refreshing == active) return;
        refreshing = active;
        if (active) {
            dragging = false;
            eligible = false;
            edgeVelocity = 0;
            clearEdgeMotion();
        }
        updateSpin();
        settleTo(active ? dp(54) : 0f);
    }

    private void settleTo(float end) {
        if (settle != null) settle.cancel();
        if (!isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled() || distance == end) {
            position(end);
            return;
        }
        settle = ValueAnimator.ofFloat(distance,end);
        settle.setDuration(330);
        settle.setInterpolator(RELEASE);
        settle.addUpdateListener(animation -> position(Math.max(0f,(float) animation.getAnimatedValue())));
        settle.start();
    }

    private void position(float offset) {
        distance = offset;
        translateContent();
        float progress = Math.min(1f,offset / threshold);
        indicator.setVisibility(offset > 0f ? VISIBLE : INVISIBLE);
        indicator.setTranslationY(Math.max(0f,offset - dp(44)));
        indicator.setAlpha(Math.max(0f,Math.min(1f,(progress - .16f) * 2.2f)));
        indicator.setScaleX(.76f + .24f * progress);
        indicator.setScaleY(.66f + .34f * progress);
        String status = refreshing ? "Updating servers…" : progress >= 1f ? "Release to refresh" : "Pull to refresh";
        if ((refreshing || dragging || offset == 0f) && !status.contentEquals(label.getText())) label.setText(status);
        mark.progress = progress;
        mark.invalidate();
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACCESSIBILITY_REFRESH,"Refresh server directory"));
    }

    @Override public boolean performAccessibilityAction(int action,Bundle arguments) {
        if (action != ACCESSIBILITY_REFRESH) return super.performAccessibilityAction(action,arguments);
        refresh.run();
        return true;
    }

    private void updateSpin() {
        if (spin != null) {
            spin.cancel();
            spin = null;
        }
        if (!refreshing || !isAttachedToWindow() || getWindowVisibility() != VISIBLE
                || !ValueAnimator.areAnimatorsEnabled()) return;
        spin = ValueAnimator.ofFloat(0f,360f);
        spin.setInterpolator(new LinearInterpolator());
        spin.setDuration(820);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.addUpdateListener(animation -> {
            mark.angle = (float) animation.getAnimatedValue();
            mark.invalidate();
        });
        spin.start();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateSpin();
    }

    @Override protected void onDetachedFromWindow() {
        if (settle != null) settle.cancel();
        if (spin != null) spin.cancel();
        clearEdgeMotion();
        super.onDetachedFromWindow();
    }

    private int dp(float value) { return Math.round(density * value); }

    private final class RefreshMark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF ring = new RectF();
        private float progress, angle;

        RefreshMark(Context context) {
            super(context);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            float x = getWidth() * .5f, y = getHeight() * .5f, radius = dp(8);
            ring.set(x - radius,y - radius,x + radius,y + radius);
            paint.setStyle(Paint.Style.STROKE);
            GlassTheme light = ThemeTransition.LIGHT, dark = ThemeTransition.DARK;
            paint.setColor(ThemeTransition.color(light.meterTrack,dark.meterTrack,colors.amount()));
            canvas.drawOval(ring,paint);
            paint.setColor(ThemeTransition.color(light.accent,dark.accent,colors.amount()));
            canvas.drawArc(ring,refreshing ? angle - 90f : -90f,refreshing ? 105f : 360f * progress,false,paint);
            if (!refreshing) {
                float direction = progress >= 1f ? -1f : 1f;
                float tip = y + direction * dp(3);
                canvas.drawLine(x,y - direction * dp(4),x,tip,paint);
                canvas.drawLine(x,tip,x - dp(3),y,paint);
                canvas.drawLine(x,tip,x + dp(3),y,paint);
            }
        }
    }
}
