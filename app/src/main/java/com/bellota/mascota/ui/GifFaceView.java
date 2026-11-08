package com.bellota.mascota.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;

/** Reproductor GIF liviano basado en Movie, disponible desde Android 8.1. */
public class GifFaceView extends View {
    private Movie movie;
    private long startedAt;
    private int fallbackColor = 0xFF20343A;

    public GifFaceView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setGif(InputStream stream) {
        movie = Movie.decodeStream(stream);
        startedAt = android.os.SystemClock.uptimeMillis();
        invalidate();
    }

    public void loadAsset(Context context, String fileName) {
        try {
            setGif(context.getAssets().open("faces/" + fileName));
        } catch (IOException exception) {
            movie = null;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (movie == null) {
            canvas.drawColor(fallbackColor);
            return;
        }
        int duration = Math.max(movie.duration(), 1);
        int elapsed = (int) ((android.os.SystemClock.uptimeMillis() - startedAt) % duration);
        movie.setTime(elapsed);

        float scale = Math.min((float) getWidth() / movie.width(),
                (float) getHeight() / movie.height());
        float x = (getWidth() - movie.width() * scale) / 2f;
        float y = (getHeight() - movie.height() * scale) / 2f;

        canvas.save();
        canvas.translate(x, y);
        canvas.scale(scale, scale);
        movie.draw(canvas, 0, 0);
        canvas.restore();
        postInvalidateDelayed(40);
    }
}
