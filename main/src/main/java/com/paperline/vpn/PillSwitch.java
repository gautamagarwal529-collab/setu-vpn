package com.paperline.vpn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.StateSet;
import android.widget.Switch;

/** Monochrome switch artwork with Android's native checked state and dragging. */
final class PillSwitch extends Switch {
    private final Face thumbFace, trackFace;

    PillSwitch(Context context,boolean dark) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        int width = Math.round(38 * density);
        int height = Math.round(27 * density);
        int thumb = Math.round(23 * density);
        int thumbPadding = Math.round(6 * density);
        int trackPadding = thumbPadding + Math.round(2 * density);

        setThumbTintList(null);
        setTrackTintList(null);
        setShowText(false);
        setSplitTrack(false);
        setSwitchMinWidth(width);
        setSwitchPadding(Math.round(10 * density));
        // Switch uses drawable padding to measure thumb travel. The visible circle
        // fills the whole thumb drawable, including that horizontal padding.
        thumbFace = new Face(thumb,thumb,thumbPadding,true,
                0xfffafafa,0xfff5f5f5,0xffdfdfdf,0xff1c1c1c);
        trackFace = new Face(width,height,trackPadding,false,
                0xffa0a0a0,0xff222222,0xff454545,0xffe1e1e1);
        setThumbDrawable(thumbFace);
        setTrackDrawable(trackFace);
        setNight(dark ? 1f : 0f);
    }

    void setNight(float night) {
        thumbFace.setNight(night);
        trackFace.setNight(night);
    }

    private static final class Face extends Drawable {
        private static final int[] CHECKED = {android.R.attr.state_checked};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int width, height, padding, lightOff, lightOn, darkOff, darkOn;
        private final boolean circle;
        private int alpha = 255;
        private float night;

        Face(int width,int height,int padding,boolean circle,int lightOff,int lightOn,int darkOff,int darkOn) {
            this.width = width;
            this.height = height;
            this.padding = padding;
            this.circle = circle;
            this.lightOff = lightOff;
            this.lightOn = lightOn;
            this.darkOff = darkOff;
            this.darkOn = darkOn;
        }

        @Override public int getIntrinsicWidth() { return width; }
        @Override public int getIntrinsicHeight() { return height; }

        @Override public boolean getPadding(Rect insets) {
            insets.set(padding,0,padding,0);
            return padding != 0;
        }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] state) {
            invalidateSelf();
            return true;
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            boolean checked = StateSet.stateSetMatches(CHECKED,getState());
            paint.setColor(ThemeTransition.color(checked ? lightOn : lightOff,checked ? darkOn : darkOff,night));
            paint.setAlpha(alpha);
            if (circle) {
                canvas.drawCircle(bounds.exactCenterX(),bounds.exactCenterY(),
                        Math.min(bounds.width(),bounds.height()) * .5f,paint);
            } else {
                float radius = bounds.height() * .5f;
                canvas.drawRoundRect(bounds.left,bounds.top,bounds.right,bounds.bottom,radius,radius,paint);
            }
        }

        void setNight(float night) {
            this.night = night;
            invalidateSelf();
        }

        @Override public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
