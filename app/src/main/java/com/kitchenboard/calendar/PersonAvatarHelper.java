package com.kitchenboard.calendar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/** Utility for creating circular avatar bitmaps (photo or initials fallback). */
public final class PersonAvatarHelper {

    private PersonAvatarHelper() { }

    /**
     * Returns a circular bitmap for the given person.
     * If the person has a photo it is used; otherwise the first letter of their name
     * is rendered in white on the person's color background.
     *
     * @param context  Android context (used for density).
     * @param person   The person whose avatar to create.
     * @param sizeDp   Desired output size in dp.
     * @return         A square circular bitmap of size {@code sizeDp×sizeDp} dp.
     */
    @NonNull
    public static Bitmap createAvatarBitmap(@NonNull Context context, @NonNull Person person,
            int sizeDp) {
        int sizePx = Math.round(sizeDp * context.getResources().getDisplayMetrics().density);
        return createAvatarBitmapPx(person, sizePx);
    }

    /**
     * Returns a circular bitmap for the given person at the specified pixel size.
     * Photo takes priority over initials.
     */
    @NonNull
    public static Bitmap createAvatarBitmapPx(@NonNull Person person, int sizePx) {
        // Try to load person photo first
        String imagePath = person.getImagePath();
        if (imagePath != null && new File(imagePath).exists()) {
            Bitmap bmp = BitmapFactory.decodeFile(imagePath);
            if (bmp != null) {
                Bitmap circular = toCircularBitmap(bmp);
                if (circular.getWidth() != sizePx) {
                    circular = Bitmap.createScaledBitmap(circular, sizePx, sizePx, true);
                }
                return circular;
            }
        }
        // Fallback: initials on color background
        return createInitialsBitmap(person.getName(), person.getColor(), sizePx);
    }

    /**
     * Creates a circular bitmap showing the first letter of {@code name} in white
     * on a colored background determined by {@code colorHex}.
     */
    @NonNull
    public static Bitmap createInitialsBitmap(@NonNull String name, @Nullable String colorHex,
            int sizePx) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Background circle
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int bgColor = Color.GRAY;
        if (colorHex != null) {
            try {
                bgColor = Color.parseColor(colorHex);
            } catch (IllegalArgumentException ignored) { }
        }
        bgPaint.setColor(bgColor);
        canvas.drawOval(new RectF(0, 0, sizePx, sizePx), bgPaint);

        // Initials text
        String initial = name != null && !name.isEmpty()
                ? name.substring(0, 1).toUpperCase() : "?";
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(sizePx * 0.45f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // Center vertically: baseline offset
        float textY = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(initial, sizePx / 2f, textY, textPaint);

        return bmp;
    }

    /**
     * Crops a rectangular bitmap to a circle, center-aligned.
     * Produces a square bitmap whose side equals {@code min(source.width, source.height)}.
     */
    @NonNull
    static Bitmap toCircularBitmap(@NonNull Bitmap source) {
        int size = Math.min(source.getWidth(), source.getHeight());
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Rect srcRect = new Rect(
                (source.getWidth() - size) / 2, (source.getHeight() - size) / 2,
                (source.getWidth() + size) / 2, (source.getHeight() + size) / 2);
        RectF dstRect = new RectF(0, 0, size, size);
        canvas.drawOval(dstRect, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(source, srcRect, dstRect, paint);
        return output;
    }
}
