package com.bellota.mascota.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import java.util.Random;

/** Ojos vectoriales vivos: expresiones, parpadeo y mirada independientes de clips GIF. */
public class EyePetView extends View {
    public enum Mood { NEUTRAL, HAPPY, THINKING, SURPRISED, SLEEPY, CALM, CURIOUS }
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pupil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private Mood mood = Mood.NEUTRAL;
    private float gazeX, gazeY, blink = 1f;
    private boolean active = true;
    private ValueAnimator blinkAnimator;

    public EyePetView(Context context) {
        super(context);
        white.setColor(0xFFF4F7F5);
        pupil.setColor(0xFF10191B);
        lid.setColor(0xFF080D10);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public Mood getMood() { return mood; }

    public void setMood(Mood next) {
        mood = next == null ? Mood.NEUTRAL : next;
        invalidate();
    }

    public void reactToAttention() {
        setMood(Mood.CURIOUS);
        animateGaze(random.nextFloat() * 0.5f - 0.25f, random.nextFloat() * 0.25f - 0.12f, 420);
        postDelayed(() -> { if (active) setMood(Mood.HAPPY); }, 1000L);
    }

    public void performAutonomousBehavior() {
        if (!active) return;
        int choice = random.nextInt(6);
        if (choice == 0) setMood(Mood.NEUTRAL);
        else if (choice == 1) setMood(Mood.CURIOUS);
        else if (choice == 2) setMood(Mood.THINKING);
        else if (choice == 3) setMood(Mood.HAPPY);
        else if (choice == 4) setMood(Mood.SLEEPY);
        else setMood(Mood.CALM);
        // La mirada descansa la mayor parte del tiempo y hace pequeños vistazos ocasionales.
        if (random.nextInt(3) == 0) {
            animateGaze(random.nextFloat() * 0.7f - 0.35f,
                    random.nextFloat() * 0.45f - 0.22f, 1400 + random.nextInt(1000));
        }
        if (random.nextInt(2) == 0) blinkOnce();
    }

    private void animateGaze(float x, float y, long duration) {
        animate().cancel();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        final float fromX = gazeX, fromY = gazeY;
        animator.setDuration(duration);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float f = (float)a.getAnimatedValue();
            gazeX = fromX + (x - fromX) * f;
            gazeY = fromY + (y - fromY) * f;
            invalidate();
        });
        animator.start();
    }

    private void blinkOnce() {
        if (blinkAnimator != null) blinkAnimator.cancel();
        blinkAnimator = ValueAnimator.ofFloat(1f, 0.08f, 1f);
        blinkAnimator.setDuration(230L);
        blinkAnimator.addUpdateListener(a -> { blink = (float)a.getAnimatedValue(); invalidate(); });
        blinkAnimator.start();
    }

    public void pauseAnimation() { active = false; if (blinkAnimator != null) blinkAnimator.cancel(); }
    public void resumeAnimation() { active = true; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float eyeW = w * 0.39f, eyeH = h * 0.68f;
        float gap = w * 0.08f;
        float centerY = h * 0.5f;
        float left = (w - (eyeW * 2f + gap)) / 2f;
        drawEye(canvas, left, centerY, eyeW, eyeH);
        drawEye(canvas, left + eyeW + gap, centerY, eyeW, eyeH);
    }

    private void drawEye(Canvas canvas, float x, float centerY, float eyeW, float eyeH) {
        float height = eyeH * blink;
        if (mood == Mood.SLEEPY || mood == Mood.CALM) height *= 0.68f;
        if (mood == Mood.SURPRISED) height *= 1.13f;
        RectF shape = new RectF(x, centerY - height / 2f, x + eyeW, centerY + height / 2f);
        canvas.drawRoundRect(shape, eyeW * 0.48f, eyeW * 0.48f, white);
        float radius = Math.min(eyeW, height) * 0.27f;
        float px = x + eyeW * (0.5f + gazeX * 0.42f);
        float py = centerY + gazeY * eyeH * 0.48f;
        if (mood == Mood.THINKING) py -= eyeH * 0.08f;
        canvas.drawCircle(px, py, radius, pupil);
        canvas.drawCircle(px - radius * 0.32f, py - radius * 0.38f, radius * 0.22f, white);
        if (mood == Mood.SLEEPY || mood == Mood.CALM) {
            lid.setColor(0xFF080D10);
            canvas.drawRect(x, shape.top, x + eyeW, shape.top + height * 0.28f, lid);
        }
    }
}
