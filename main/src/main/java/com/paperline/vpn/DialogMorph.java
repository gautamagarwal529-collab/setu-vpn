package com.paperline.vpn;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Animates the actual alert contents inside a transparent window that spans the screen. */
final class DialogMorph {
    private static final PathInterpolator EXPAND = new PathInterpolator(.19f,.79f,.24f,1f);
    private static final PathInterpolator COLLAPSE = new PathInterpolator(.32f,0f,.20f,1f);
    private final Activity activity;
    private final Set<Surface> surfaces = new LinkedHashSet<>();

    DialogMorph(Activity activity) { this.activity = activity; }

    static final class Origin {
        private final WeakReference<View> view;
        private final RectF initial;

        Origin(View anchor,RectF initial) {
            view = new WeakReference<>(anchor);
            this.initial = initial;
        }

        RectF currentBounds() {
            RectF visible = visibleBounds(view.get());
            return visible == null ? new RectF(initial) : visible;
        }
    }

    static Origin origin(View view) {
        RectF bounds = visibleBounds(view);
        return bounds == null ? null : new Origin(view,bounds);
    }

    private static RectF visibleBounds(View view) {
        if (view == null || !view.isShown() || !view.isAttachedToWindow()) return null;
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible) || visible.isEmpty()) return null;
        int[] location = new int[2];
        view.getRootView().getLocationOnScreen(location);
        visible.offset(location[0],location[1]);
        return new RectF(visible);
    }

    boolean show(AlertDialog dialog,Origin origin,boolean animate,int fill,int stroke,Runnable style) {
        Window window = dialog.getWindow();
        if (origin == null || window == null || activity.isFinishing() || activity.isDestroyed()) return false;
        dialog.create();
        View contentRoot = window.findViewById(android.R.id.content);
        if (!(contentRoot instanceof FrameLayout)) return false;
        FrameLayout holder = (FrameLayout) contentRoot;
        if (holder.getChildCount() != 1) return false;
        style.run();

        float dim = window.getAttributes().dimAmount;
        View contents = holder.getChildAt(0);
        holder.removeViewAt(0);
        Surface surface = new Surface(dialog,contents,origin,fill,stroke,dim,
                animate && ValueAnimator.areAnimatorsEnabled());
        holder.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        configureWindow(window);
        window.setWindowAnimations(0);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.setLayout(-1,-1);
        window.setGravity(Gravity.CENTER);
        surfaces.add(surface);
        dialog.show();
        return true;
    }

    private void configureWindow(Window window) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowManager.LayoutParams params = window.getAttributes();
            params.setFitInsetsTypes(0);
            window.setAttributes(params);
        } else {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        syncBarAppearance(window);
    }

    void syncBarAppearance(Window window) {
        int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        int appearance = activity.getWindow().getDecorView().getSystemUiVisibility() & mask;
        View decor = window.getDecorView();
        decor.setSystemUiVisibility((decor.getSystemUiVisibility() & ~mask) | appearance);
    }

    void close(AlertDialog dialog,Origin origin,Runnable after) {
        if (!dialog.isShowing()) return;
        Surface surface = surfaceFor(dialog);
        if (surface == null) dismiss(dialog,after);
        else surface.close(origin,after);
    }

    void bindPalette(AlertDialog dialog,ThemeTransition colors) {
        Surface surface = surfaceFor(dialog);
        if (surface == null) return;
        colors.bind(surface,night -> {
            GlassTheme light = ThemeTransition.LIGHT, dark = ThemeTransition.DARK;
            surface.fill = ThemeTransition.color(light.dialog,dark.dialog,night);
            surface.stroke = ThemeTransition.color(light.divider,dark.divider,night);
            surface.invalidate();
        });
    }

    private Surface surfaceFor(AlertDialog dialog) {
        for (Surface surface : surfaces) if (surface.dialog == dialog) return surface;
        return null;
    }

    void dispose() {
        for (Surface surface : new ArrayList<>(surfaces)) surface.dialog.dismiss();
        surfaces.clear();
    }

    private void dismiss(AlertDialog dialog,Runnable after) {
        dialog.dismiss();
        if (after != null && !activity.isFinishing() && !activity.isDestroyed()) after.run();
    }

    private final class Surface extends FrameLayout {
        private final AlertDialog dialog;
        private final View contents;
        private final RectF source = new RectF(), destination = new RectF(), shape = new RectF();
        private final int[] location = new int[2];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int fill, stroke;
        private final float dim, panelRadius;
        private final RectF strokeBounds = new RectF();
        private Origin origin;
        private boolean entering, closing;
        private float progress;
        private ValueAnimator motion;
        private boolean trackOutsideTap;

        Surface(AlertDialog dialog,View contents,Origin origin,int fill,int stroke,float dim,boolean entering) {
            super(dialog.getContext());
            this.dialog = dialog;
            this.contents = contents;
            this.origin = origin;
            this.fill = fill;
            this.stroke = stroke;
            this.dim = dim;
            this.entering = entering;
            panelRadius = dp(28);
            progress = entering ? 0f : 1f;
            setWillNotDraw(false);
            setClipChildren(false);
            setPadding(dp(18),dp(14),dp(18),dp(14));
            setOnApplyWindowInsetsListener((view,insets) -> {
                applyWindowInsets(insets);
                return insets;
            });
            addView(contents,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
            contents.setAlpha(progress);
        }

        private void applyWindowInsets(WindowInsets insets) {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int bottom = Math.max(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom);
                setPadding(dp(18) + bars.left,dp(14) + bars.top,dp(18) + bars.right,dp(14) + bottom);
            } else {
                setPadding(dp(18) + insets.getStableInsetLeft(),dp(14) + insets.getStableInsetTop(),
                        dp(18) + insets.getStableInsetRight(),dp(14) + insets.getStableInsetBottom());
            }
        }

        @Override protected void onMeasure(int width,int height) {
            int available = Math.max(0,MeasureSpec.getSize(width) - getPaddingLeft() - getPaddingRight());
            contents.getLayoutParams().width = Math.min(dp(560),available);
            super.onMeasure(width,height);
        }

        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
            super.onLayout(changed,left,top,right,bottom);
            destination.set(contents.getLeft(),contents.getTop(),contents.getRight(),contents.getBottom());
            updateSource(entering ? origin.initial : origin.currentBounds());
            if (entering) {
                entering = false;
                animateTo(1f,290,EXPAND,null);
            }
            drawProgress(progress);
        }

        private void updateSource(RectF bounds) {
            getLocationOnScreen(location);
            source.set(bounds);
            source.offset(-location[0],-location[1]);
            if (!RectF.intersects(source,new RectF(0,0,getWidth(),getHeight()))) {
                float x = Math.max(0,Math.min(getWidth(),source.centerX()));
                float y = Math.max(0,Math.min(getHeight(),source.centerY()));
                source.offset(x - source.centerX(),y - source.centerY());
            }
        }

        void close(Origin nextOrigin,Runnable after) {
            if (closing) return;
            closing = true;
            if (nextOrigin != null) origin = nextOrigin;
            if (!ValueAnimator.areAnimatorsEnabled() || progress == 0f || destination.isEmpty()) {
                dismiss(dialog,after);
                return;
            }
            updateSource(origin.currentBounds());
            animateTo(0f,265,COLLAPSE,() -> dismiss(dialog,after));
        }

        private void animateTo(float end,int duration,PathInterpolator curve,Runnable complete) {
            animate(progress,end,duration,curve,this::drawProgress,complete);
        }

        private void animate(float start,float end,int duration,PathInterpolator curve,
                             Consumer<Float> update,Runnable complete) {
            stopAnimation();
            motion = ValueAnimator.ofFloat(start,end);
            motion.setDuration(duration);
            motion.setInterpolator(curve);
            motion.addUpdateListener(animation -> update.accept((Float) animation.getAnimatedValue()));
            motion.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    motion = null;
                    if (complete != null) complete.run();
                }
            });
            motion.start();
        }

        private void stopAnimation() {
            if (motion == null) return;
            motion.removeAllListeners();
            motion.removeAllUpdateListeners();
            motion.cancel();
            motion = null;
        }

        private void drawProgress(float value) {
            progress = value;
            shape.set(lerp(source.left,destination.left,value),lerp(source.top,destination.top,value),
                    lerp(source.right,destination.right,value),lerp(source.bottom,destination.bottom,value));
            float reveal = smoothStep((value - .40f) / .60f);
            contents.setAlpha(reveal);
            if (!destination.isEmpty()) {
                float scale = Math.min(shape.width() / destination.width(),shape.height() / destination.height());
                contents.setScaleX(scale);
                contents.setScaleY(scale);
                contents.setTranslationX(shape.centerX() - destination.centerX());
                contents.setTranslationY(shape.centerY() - destination.centerY());
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (progress <= 0f || destination.isEmpty()) return;
            canvas.drawColor(Color.argb(Math.round(255 * dim * progress),0,0,0));
            float radius = lerp(Math.min(source.width(),source.height()) * .5f,panelRadius,progress);
            float faceAlpha = smoothStep(progress / .26f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            paint.setAlpha(Math.round(Color.alpha(fill) * faceAlpha));
            canvas.drawRoundRect(shape,radius,radius,paint);
            float line = dp(1);
            strokeBounds.set(shape);
            strokeBounds.inset(line * .5f,line * .5f);
            if (!strokeBounds.isEmpty()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(line);
                paint.setColor(stroke);
                paint.setAlpha(Math.round(Color.alpha(stroke) * faceAlpha));
                canvas.drawRoundRect(strokeBounds,Math.max(0f,radius - line * .5f),
                        Math.max(0f,radius - line * .5f),paint);
            }
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            return motion != null || closing || super.dispatchTouchEvent(event);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                trackOutsideTap = !destination.contains(event.getX(),event.getY());
            if (event.getActionMasked() == MotionEvent.ACTION_UP && trackOutsideTap
                    && !destination.contains(event.getX(),event.getY())) close(null,null);
            return true;
        }

        @Override protected void onDetachedFromWindow() {
            stopAnimation();
            surfaces.remove(this);
            super.onDetachedFromWindow();
        }
    }

    private int dp(int value) { return Math.round(activity.getResources().getDisplayMetrics().density * value); }
    private static float lerp(float a,float b,float t) { return a + (b - a) * t; }

    private static float smoothStep(float t) {
        t = Math.max(0f,Math.min(1f,t));
        return t * t * (3f - 2f * t);
    }
}
