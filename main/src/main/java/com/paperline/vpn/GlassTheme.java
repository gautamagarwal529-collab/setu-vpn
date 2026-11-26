package com.paperline.vpn;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

final class GlassTheme {
    enum Surface { PANEL, CONTROL, TRACK, SELECTED, PRIMARY, DOCK, ORB }

    final boolean dark;
    final int base, ink, muted, divider, accent, connected, primaryText, meterTrack, dialog;
    private final int highlight, lowlight;

    GlassTheme(boolean dark) {
        this.dark = dark;
        base = dark ? Color.BLACK : 0xffedf3fc;
        ink = dark ? 0xfff3f6fe : 0xff101a2f;
        muted = dark ? 0xffa5adc0 : 0xff43516c;
        divider = dark ? 0xff242933 : 0x22394d74;
        accent = dark ? 0xff9dbbff : 0xff294eac;
        connected = dark ? 0xff73e4bd : 0xff12664f;
        primaryText = dark ? 0xffe1ebff : Color.WHITE;
        meterTrack = dark ? 0xff192132 : 0xffd6e0f2;
        dialog = dark ? 0xff101217 : 0xfff4f8ff;
        highlight = dark ? 0x43ffffff : 0xeaffffff;
        lowlight = dark ? 0x251c2940 : 0x4c677eae;
    }

    Drawable backdrop() {
        return dark ? new ColorDrawable(Color.BLACK) : new Backdrop();
    }

    Glass surface(Surface kind,float radius,float rim) {
        int top, bottom, edge = highlight, glint = dark ? 0x1affffff : 0x9effffff;
        switch (kind) {
            case PRIMARY:
                top = dark ? 0xff293f79 : 0xff315cc9;
                bottom = dark ? 0xff13213e : 0xff203e93;
                edge = dark ? 0x857caaff : 0xa6c9e0ff;
                glint = dark ? 0x24adc5ff : 0x54b2d6ff;
                break;
            case SELECTED:
                top = dark ? 0xfa282e40 : 0xfffcfdff;
                bottom = dark ? 0xff111723 : 0xffe4edff;
                break;
            case TRACK:
                top = dark ? 0xd1101117 : 0xa9ccdbf5;
                bottom = dark ? 0xdd07090d : 0x8bdae8fb;
                break;
            case DOCK:
                top = dark ? 0xfd141820 : 0xf3f6fbff;
                bottom = dark ? 0xfe06080b : 0xe2e1edff;
                break;
            case ORB:
                top = dark ? 0xfa272f45 : 0xeafcfdff;
                bottom = dark ? 0xff10182c : 0xbfb5c8fb;
                glint = dark ? 0x49b5c7ff : 0xf2ffffff;
                break;
            case CONTROL:
                top = dark ? 0xee181c26 : 0xd8ffffff;
                bottom = dark ? 0xd7030508 : 0x8fdae7ff;
                break;
            default:
                top = dark ? 0xc7141923 : 0xdafcfeff;
                bottom = dark ? 0xe5000000 : 0x9be0ebff;
                break;
        }
        return new Glass(radius,rim,top,bottom,edge,lowlight,glint);
    }

    Drawable bottomBar(float radius,int rim) {
        int top = dark ? 0xf6161c27 : 0xf2f9fcff;
        int bottom = dark ? 0xfd090b10 : 0xf8e5edf9;
        GradientDrawable dock = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});
        dock.setCornerRadii(topCorners(radius));
        dock.setStroke(rim,dark ? 0x523c4b65 : 0xe5ffffff);
        return dock;
    }

    private static float[] topCorners(float radius) {
        return new float[]{radius,radius,radius,radius,0f,0f,0f,0f};
    }

    static final class Glass extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF face = new RectF();
        private final float radius, rim;
        private final int top, bottom, highlight, lowlight, glint;
        private Shader body, edge, reflection;
        private int alpha = 255;

        Glass(float radius,float rim,int top,int bottom,int highlight,int lowlight,int glint) {
            this.radius = radius;
            this.rim = rim;
            this.top = top;
            this.bottom = bottom;
            this.highlight = highlight;
            this.lowlight = lowlight;
            this.glint = glint;
        }

        @Override protected void onBoundsChange(Rect bounds) {
            float w = bounds.width(), h = bounds.height();
            face.set(bounds);
            face.inset(rim * 0.55f,rim * 0.55f);
            if (w <= 0 || h <= 0) return;
            body = new LinearGradient(bounds.left,bounds.top,bounds.left + w * .18f,bounds.bottom,top,bottom,Shader.TileMode.CLAMP);
            edge = new LinearGradient(bounds.left,bounds.top,bounds.right,bounds.bottom,
                    new int[]{highlight,lowlight,highlight},new float[]{0f,.58f,1f},Shader.TileMode.CLAMP);
            reflection = new RadialGradient(bounds.left + w * .79f,bounds.top - h * .20f,Math.max(w * .39f,h * .56f),
                    glint,Color.TRANSPARENT,Shader.TileMode.CLAMP);
        }

        @Override public void draw(Canvas canvas) {
            if (face.isEmpty() || body == null) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(body);
            paint.setAlpha(alpha);
            canvas.drawRoundRect(face,radius,radius,paint);
            paint.setShader(reflection);
            canvas.drawRoundRect(face,radius,radius,paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(rim);
            paint.setShader(edge);
            paint.setAlpha(alpha);
            canvas.drawRoundRect(face,radius,radius,paint);
            paint.setShader(null);
        }

        @Override public void getOutline(Outline outline) {
            outline.setRoundRect(getBounds(),radius);
            outline.setAlpha(1f);
        }

        @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class Backdrop extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader[] layers;

        @Override protected void onBoundsChange(Rect bounds) {
            float w = bounds.width(), h = bounds.height();
            if (w <= 0 || h <= 0) return;
            layers = new Shader[]{
                    new LinearGradient(0,0,w * .60f,h,0xffeaf0ff,0xfff6faff,Shader.TileMode.CLAMP),
                    wash(w * .86f,h * .10f,Math.max(w * .70f,h * .34f),0xffcbc7fb),
                    wash(w * .16f,h * .27f,Math.max(w * .77f,h * .39f),0xffb5e5f4),
                    wash(w * .93f,h * .67f,Math.max(w * .71f,h * .37f),0xffdfd3fa)
            };
        }

        private static RadialGradient wash(float x,float y,float radius,int color) {
            return new RadialGradient(x,y,radius,color,Color.TRANSPARENT,Shader.TileMode.CLAMP);
        }

        @Override public void draw(Canvas canvas) {
            if (layers == null) return;
            for (Shader layer : layers) {
                paint.setShader(layer);
                canvas.drawRect(getBounds(),paint);
            }
            paint.setShader(null);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    static final class SearchIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SearchIcon(int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setTintColor(int color) { paint.setColor(color); invalidateSelf(); }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float unit = Math.min(b.width(),b.height()) / 20f;
            float x = b.left, y = b.top;
            paint.setStrokeWidth(unit * 1.65f);
            canvas.drawCircle(x + 8 * unit,y + 8 * unit,5.2f * unit,paint);
            canvas.drawLine(x + 12f * unit,y + 12f * unit,x + 17 * unit,y + 17 * unit,paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
