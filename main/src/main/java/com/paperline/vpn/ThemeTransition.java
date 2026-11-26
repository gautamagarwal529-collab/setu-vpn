package com.paperline.vpn;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.List;

final class ThemeTransition {
    static final GlassTheme LIGHT = new GlassTheme(false);
    static final GlassTheme DARK = new GlassTheme(true);
    private static final int PALETTE_BINDINGS = 0x7f0a4742;
    private static final PathInterpolator EASE = new PathInterpolator(.24f,.31f,.25f,1f);

    interface Binding { void apply(float night); }

    private static final class Bindings {
        Binding background;
        final List<Binding> foreground = new ArrayList<>();
    }

    private float night, targetNight;
    private ValueAnimator motion;

    void initialize(boolean dark) { night = dark ? 1f : 0f; }
    float amount() { return night; }

    void bind(View view,Binding binding) {
        bindings(view).foreground.add(binding);
        binding.apply(motion == null ? night : targetNight);
    }

    void background(View view,Drawable drawable,Binding binding) {
        bindings(view).background = binding;
        binding.apply(motion == null ? night : targetNight);
        view.setBackground(drawable);
    }

    private static Bindings bindings(View view) {
        Bindings existing = (Bindings) view.getTag(PALETTE_BINDINGS);
        if (existing != null) return existing;
        Bindings added = new Bindings();
        view.setTag(PALETTE_BINDINGS,added);
        return added;
    }

    void animate(boolean dark,View home,View settings,Runnable complete) {
        cancel();
        float target = dark ? 1f : 0f;
        targetNight = target;
        List<Binding> visible = new ArrayList<>(), hidden = new ArrayList<>();
        Rect bounds = new Rect();
        collect(home,true,bounds,visible,hidden);
        collect(settings,true,bounds,visible,hidden);
        if (!ValueAnimator.areAnimatorsEnabled() || night == target) {
            night = target;
            apply(visible);
            apply(hidden);
            complete.run();
            return;
        }

        int[] updated = {0};
        float start = night;
        motion = ValueAnimator.ofFloat(0f,1f);
        motion.setInterpolator(EASE);
        motion.setDuration(Math.round(345 * Math.max(.48f,Math.abs(target - start))));
        motion.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            night = start + (target - start) * progress;
            apply(visible);
            // Off-screen controls need only the final palette. Spread those writes over the fade.
            int to = (int) (hidden.size() * animation.getCurrentPlayTime() / Math.max(1,animation.getDuration()));
            for (int end = Math.min(to,hidden.size()); updated[0] < end; updated[0]++)
                hidden.get(updated[0]).apply(target);
        });
        motion.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                motion = null;
                night = target;
                for (; updated[0] < hidden.size(); updated[0]++) hidden.get(updated[0]).apply(target);
                complete.run();
            }
        });
        motion.start();
    }

    void cancel() {
        if (motion == null) return;
        motion.removeAllListeners();
        motion.removeAllUpdateListeners();
        motion.cancel();
        motion = null;
    }

    private void apply(List<Binding> bindings) {
        for (int i = 0; i < bindings.size(); i++) bindings.get(i).apply(night);
    }

    private static void collect(View view,boolean parentVisible,Rect bounds,List<Binding> visible,List<Binding> hidden) {
        if (view == null) return;
        boolean showing = parentVisible && view.getVisibility() == View.VISIBLE && view.getGlobalVisibleRect(bounds);
        List<Binding> destination = showing ? visible : hidden;
        Bindings node = (Bindings) view.getTag(PALETTE_BINDINGS);
        if (node != null) {
            if (node.background != null) destination.add(node.background);
            destination.addAll(node.foreground);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                collect(group.getChildAt(i),showing,bounds,visible,hidden);
        }
    }

    static int color(int light,int dark,float night) {
        if (night <= 0f) return light;
        if (night >= 1f) return dark;
        return Color.argb(mix(Color.alpha(light),Color.alpha(dark),night),
                mix(Color.red(light),Color.red(dark),night),
                mix(Color.green(light),Color.green(dark),night),
                mix(Color.blue(light),Color.blue(dark),night));
    }

    private static int mix(int light,int dark,float night) { return Math.round(light + (dark - light) * night); }

    /** The gradients are cached at their true bounds and reused through the transition. */
    static final class Blended extends Drawable {
        private final Drawable light, dark;
        private final boolean opaque;
        private float night;
        private int alpha = 255;

        Blended(Drawable light,Drawable dark,boolean opaque) {
            this.light = light;
            this.dark = dark;
            this.opaque = opaque;
        }

        void setNight(float night) {
            if (this.night == night) return;
            this.night = night;
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            light.setBounds(getBounds());
            dark.setBounds(getBounds());
            if (night < 1f) draw(canvas,light,opaque ? alpha : Math.round(alpha * (1f - night)));
            if (night > 0f) draw(canvas,dark,Math.round(alpha * night));
        }

        private void draw(Canvas canvas,Drawable drawable,int opacity) {
            drawable.setAlpha(opacity);
            drawable.draw(canvas);
        }

        @Override public void getOutline(Outline outline) {
            Drawable drawable = night == 1f ? dark : light;
            drawable.setBounds(getBounds());
            drawable.getOutline(outline);
        }

        @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
        @Override public int getAlpha() { return alpha; }
        @Override public void setColorFilter(ColorFilter filter) {
            light.setColorFilter(filter);
            dark.setColorFilter(filter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
