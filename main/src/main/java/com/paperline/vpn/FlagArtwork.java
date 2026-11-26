package com.paperline.vpn;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;

/** Lighting follows the existing flag silhouette, including nonrectangular flags. */
final class FlagArtwork {
    private FlagArtwork() {}

    static Bitmap light(Bitmap flag) {
        int width = flag.getWidth(), height = flag.getHeight();
        int pad = Math.max(4,Math.round(height * .065f));
        Bitmap shaded = Bitmap.createBitmap(width + 2 * pad,height + 2 * pad,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(shaded);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new PorterDuffColorFilter(0x26000000,PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(flag,pad + pad * .4f,pad * 1.7f,paint);
        paint.setColorFilter(new PorterDuffColorFilter(0x36041121,PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(flag,pad + pad * .20f,pad * 1.35f,paint);
        paint.setColorFilter(null);

        int face = canvas.saveLayer(pad,pad,pad + width,pad + height,null);
        canvas.drawBitmap(flag,pad,pad,paint);
        paint.setShader(new LinearGradient(pad,pad,pad + width * .75f,pad + height,
                new int[]{0x54ffffff,0x0dffffff,0x00000000,0x45040c1d},
                new float[]{0f,.28f,.52f,1f},Shader.TileMode.CLAMP));
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        canvas.drawRect(pad,pad,pad + width,pad + height,paint);
        canvas.restoreToCount(face);
        return shaded;
    }
}
