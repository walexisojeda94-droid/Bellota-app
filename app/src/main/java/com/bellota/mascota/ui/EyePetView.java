package com.bellota.mascota.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
    private boolean lightTheme;
    private int autonomousScene;
    private ValueAnimator blinkAnimator;
    private ValueAnimator gazeAnimator;
    private final Handler animationHandler = new Handler(Looper.getMainLooper());
    private final Runnable idleAnimation = new Runnable() {
        @Override public void run() {
            if (!active || !isAttachedToWindow()) return;
            if (random.nextInt(100) < 82) {
                animateGaze(random.nextFloat() * 0.62f - 0.31f,
                        random.nextFloat() * 0.38f - 0.19f,
                        650L + random.nextInt(850));
            }
            if (random.nextInt(100) < 48) blinkOnce();
            animationHandler.postDelayed(this, 2200L + random.nextInt(2600));
        }
    };

    public EyePetView(Context context) {
        super(context);
        white.setColor(0xFFF4F7F5);
        pupil.setColor(0xFF10191B);
        lid.setColor(lightTheme?0xFFF2F5F3:0xFF080D10);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public Mood getMood() { return mood; }
    public void setLightTheme(boolean light) { lightTheme=light; lid.setColor(light?0xFFF2F5F3:0xFF080D10); invalidate(); }

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
        int choice = random.nextInt(10);
        if (choice == 0) setMood(Mood.NEUTRAL);
        else if (choice == 1) setMood(Mood.CURIOUS);
        else if (choice == 2) setMood(Mood.THINKING);
        else if (choice == 3) setMood(Mood.HAPPY);
        else if (choice == 4) setMood(Mood.SLEEPY);
        else if (choice == 5) setMood(Mood.CALM);
        else if (choice == 6) { setMood(Mood.CURIOUS); autonomousScene=1; }
        else if (choice == 7) { setMood(Mood.HAPPY); autonomousScene=2; }
        else if(choice==8){ setMood(Mood.CURIOUS); autonomousScene=4; }
        else { setMood(Mood.SLEEPY); autonomousScene=3; }
        // La mirada descansa la mayor parte del tiempo y hace pequeños vistazos ocasionales.
        if (random.nextInt(3) == 0) {
            animateGaze(random.nextFloat() * 0.7f - 0.35f,
                    random.nextFloat() * 0.45f - 0.22f, 1400 + random.nextInt(1000));
        }
        if (random.nextInt(2) == 0) blinkOnce();
        postDelayed(() -> { if(active){autonomousScene=0;invalidate();} },6500L);
    }

    /** Juego breve: Bellota persigue un punto luminoso con la mirada. */
    public void playAutonomousGame() {
        if (!active) return;
        autonomousScene=2;
        setMood(Mood.HAPPY);
        final float[][] targets={{-0.34f,-0.12f},{0.30f,-0.18f},{0.22f,0.16f},{-0.28f,0.12f},{0f,0f}};
        for(int i=0;i<targets.length;i++) {
            final float tx=targets[i][0], ty=targets[i][1];
            postDelayed(() -> { if(active) animateGaze(tx,ty,520L); },i*620L);
        }
        postDelayed(() -> { if(active) { autonomousScene=0; setMood(Mood.CURIOUS); invalidate(); } },3900L);
    }

    private void animateGaze(float x, float y, long duration) {
        if (gazeAnimator != null) gazeAnimator.cancel();
        gazeAnimator = ValueAnimator.ofFloat(0f, 1f);
        final float fromX = gazeX, fromY = gazeY;
        gazeAnimator.setDuration(duration);
        gazeAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        gazeAnimator.addUpdateListener(a -> {
            float f = (float)a.getAnimatedValue();
            gazeX = fromX + (x - fromX) * f;
            gazeY = fromY + (y - fromY) * f;
            invalidate();
        });
        gazeAnimator.start();
    }

    private void blinkOnce() {
        if (blinkAnimator != null) blinkAnimator.cancel();
        blinkAnimator = ValueAnimator.ofFloat(1f, 0.08f, 1f);
        blinkAnimator.setDuration(230L);
        blinkAnimator.addUpdateListener(a -> { blink = (float)a.getAnimatedValue(); invalidate(); });
        blinkAnimator.start();
    }

    private void scheduleIdleAnimation() {
        animationHandler.removeCallbacks(idleAnimation);
        if (active && isAttachedToWindow()) animationHandler.postDelayed(idleAnimation, 1200L);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        active = true;
        scheduleIdleAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        active = false;
        animationHandler.removeCallbacks(idleAnimation);
        if (blinkAnimator != null) blinkAnimator.cancel();
        if (gazeAnimator != null) gazeAnimator.cancel();
        super.onDetachedFromWindow();
    }

    public void pauseAnimation() {
        active = false;
        animationHandler.removeCallbacks(idleAnimation);
        if (blinkAnimator != null) blinkAnimator.cancel();
        if (gazeAnimator != null) gazeAnimator.cancel();
    }

    public void resumeAnimation() {
        active = true;
        scheduleIdleAnimation();
    }

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
            lid.setColor(lightTheme?0xFFF2F5F3:0xFF080D10);
            canvas.drawRect(x, shape.top, x + eyeW, shape.top + height * 0.28f, lid);
        }
        if(autonomousScene==1){ Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(lightTheme?0xFF8A6D2F:0xFFFFD166); canvas.drawCircle(x+eyeW*0.5f,centerY-eyeH*0.62f,eyeW*0.045f,p); }
        else if(autonomousScene==2){ Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(0xFF00BFA6); float bx=x+eyeW*0.5f+(float)Math.sin(System.currentTimeMillis()/380.0)*eyeW*0.22f; canvas.drawCircle(bx,centerY+eyeH*0.67f,eyeW*0.10f,p); postInvalidateDelayed(180L); }
        else if(autonomousScene==3){ Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(lightTheme?0xFF52616B:0xFFB7C8D0); p.setTextSize(eyeW*0.16f); canvas.drawText("z",x+eyeW*0.86f,centerY-eyeH*0.45f,p); }
        else if(autonomousScene==4){ Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(lightTheme?0xFF52616B:0xFFE7EDF0); float sy=centerY-eyeH*0.62f; canvas.drawCircle(x+eyeW*0.25f,sy,eyeW*0.055f,p); canvas.drawCircle(x+eyeW*0.38f,sy-eyeW*0.025f,eyeW*0.045f,p); canvas.drawCircle(x+eyeW*0.72f,sy+eyeW*0.025f,eyeW*0.05f,p); }
    }
}
