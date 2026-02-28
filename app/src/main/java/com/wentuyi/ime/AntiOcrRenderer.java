package com.wentuyi.ime;
import android.graphics.*;
import java.util.Random;
public class AntiOcrRenderer {
    private static final Random random = new Random();
    public static Bitmap generateImage(String text, int noiseLevel) {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(40);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setColor(Color.rgb(51, 51, 51)); 
        String[] lines = text.split("\n");
        int padding = 40;
        int lineHeight = 60;
        int width = 800;
        int height = Math.max(100, lines.length * lineHeight + padding * 2);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(247, 247, 247));
        if (noiseLevel > 0) {
            Paint noisePaint = new Paint();
            for (int i = 0; i < noiseLevel * 50; i++) {
                noisePaint.setColor(Color.rgb(random.nextInt(255), random.nextInt(255), random.nextInt(255)));
                noisePaint.setAlpha(random.nextInt(50) + 30);
                canvas.drawCircle(random.nextInt(width), random.nextInt(height), random.nextInt(3), noisePaint);
            }
        }
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            float startX = padding;
            float startY = padding + (i + 1) * lineHeight - 15;
            canvas.save();
            canvas.rotate((random.nextFloat() - 0.5f) * (noiseLevel / 100f) * 3f, startX, startY);
            float currentX = startX;
            for (char c : line.toCharArray()) {
                float charOffset = (random.nextFloat() - 0.5f) * (noiseLevel / 100f) * 6.0f;
                canvas.drawText(String.valueOf(c), currentX, startY + charOffset, paint);
                currentX += paint.measureText(String.valueOf(c)) + (random.nextFloat() * 4);
            }
            canvas.restore();
        }
        return bitmap;
    }
}
