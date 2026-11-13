package com.bellota.mascota.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Random;

/**
 * Capa transparente a pantalla completa donde Bellota juega sola: pelota que rebota,
 * burbujas, una mosca molesta y estrellas fugaces. Solo dibuja mientras hay un juego activo
 * (unos pocos círculos por cuadro), así que casi no consume.
 */
public class PlayfieldView extends View {
    public enum Game { NONE, BALL, BUBBLES, FLY, STARS }

    public interface Listener {
        /** Punto de la pantalla que la mascota debería mirar. */
        void onAttention(float x, float y);
        void onEvent(Game game, String event);
        void onFinished(Game game);
    }

    private static final int MAX_BUBBLES = 9;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Random random = new Random();
    private Listener listener;
    private Game game = Game.NONE;
    private boolean ending;
    private long lastNs;
    private float t;
    private float duration;
    private float focusX = -1f;
    private float focusY = -1f;
    private float attX;
    private float attY;

    // pelota
    private float bx, by, vx, vy, squash, restT;
    // burbujas
    private final boolean[] bubOn = new boolean[MAX_BUBBLES];
    private final float[] bubX = new float[MAX_BUBBLES];
    private final float[] bubY = new float[MAX_BUBBLES];
    private final float[] bubR = new float[MAX_BUBBLES];
    private final float[] bubV = new float[MAX_BUBBLES];
    private final float[] bubPh = new float[MAX_BUBBLES];
    private final float[] bubPopY = new float[MAX_BUBBLES];
    private final float[] bubPopT = new float[MAX_BUBBLES];
    private float nextBubble;
    // mosca
    private float fx, fy, ftx, fty, nextTarget, closeCooldown, caughtT;
    private boolean flyCaught;
    // estrellas
    private float sx, sy, svx, svy, starT;
    private int starsLeft;
    private final float[] trailX = new float[10];
    private final float[] trailY = new float[10];
    private int trailN;

    public PlayfieldView(Context context) {
        super(context);
        setVisibility(INVISIBLE);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    /** Centro de los ojos, para que la mosca vuele cerca de ellos. */
    public void setFocus(float x, float y) { focusX = x; focusY = y; }

    public boolean isRunning() { return game != Game.NONE; }

    public Game currentGame() { return game; }

    public void start(Game g) {
        if (g == null || g == Game.NONE || getWidth() == 0 || getHeight() == 0) return;
        float w = getWidth(), h = getHeight(), u = Math.min(w, h);
        game = g;
        ending = false;
        t = 0f;
        lastNs = 0L;
        attX = w / 2f;
        attY = h / 2f;
        switch (g) {
            case BALL:
                duration = 9f;
                bx = w * (0.2f + 0.6f * random.nextFloat());
                by = -u * 0.05f;
                vx = (random.nextBoolean() ? 1f : -1f) * w * (0.18f + 0.22f * random.nextFloat());
                vy = 0f;
                squash = 0f;
                restT = 0f;
                break;
            case BUBBLES:
                duration = 9f;
                for (int i = 0; i < MAX_BUBBLES; i++) bubOn[i] = false;
                nextBubble = 0f;
                break;
            case FLY:
                duration = 9.5f;
                fx = random.nextBoolean() ? -u * 0.05f : w + u * 0.05f;
                fy = h * 0.5f * random.nextFloat();
                ftx = fx;
                fty = fy;
                nextTarget = 0f;
                closeCooldown = 0f;
                flyCaught = false;
                caughtT = 0f;
                break;
            case STARS:
                duration = 5.4f;
                starsLeft = 3;
                starT = 99f;
                trailN = 0;
                break;
            default:
                break;
        }
        setVisibility(VISIBLE);
        invalidate();
    }

    /** Detiene el juego. Con notify=true avisa al listener (fin normal). */
    public void stop(boolean notify) {
        if (game == Game.NONE) return;
        Game finished = game;
        game = Game.NONE;
        setVisibility(INVISIBLE);
        if (notify && listener != null) listener.onFinished(finished);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (game == Game.NONE) return;
        long now = System.nanoTime();
        float dt = lastNs == 0L ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        t += dt;
        switch (game) {
            case BALL: stepBall(c, dt); break;
            case BUBBLES: stepBubbles(c, dt); break;
            case FLY: stepFly(c, dt); break;
            case STARS: stepStars(c, dt); break;
            default: break;
        }
        if (listener != null) listener.onAttention(attX, attY);
        if (t >= duration) {
            if (!ending) {
                ending = true;
                post(new Runnable() { @Override public void run() { stop(true); } });
            }
        } else {
            postInvalidateOnAnimation();
        }
    }

    // ---- Pelota ---------------------------------------------------------------

    private void stepBall(Canvas c, float dt) {
        float w = getWidth(), h = getHeight(), u = Math.min(w, h);
        float r = u * 0.045f;
        float floor = h - r - h * 0.05f;
        vy += h * 1.7f * dt;
        bx += vx * dt;
        by += vy * dt;
        if (bx < r) { bx = r; vx = Math.abs(vx) * 0.9f; }
        else if (bx > w - r) { bx = w - r; vx = -Math.abs(vx) * 0.9f; }
        if (by >= floor) {
            by = floor;
            if (Math.abs(vy) > h * 0.28f) { squash = 1f; emit("ball_bounce"); }
            vy = -vy * 0.78f;
            if (Math.abs(vy) < h * 0.12f) vy = 0f;
            vx *= 1f - Math.min(1f, 0.6f * dt);
            if (vy == 0f) restT += dt; else restT = 0f;
            if (restT > 0.8f) { // la patea de nuevo para que siga rebotando
                vy = -h * (0.85f + 0.4f * random.nextFloat());
                vx = (random.nextBoolean() ? 1f : -1f) * w * (0.12f + 0.2f * random.nextFloat());
                restT = 0f;
            }
        } else {
            restT = 0f;
        }
        squash = Math.max(0f, squash - dt * 6f);
        float heightFactor = Math.max(0f, Math.min(1f, (floor - by) / (h * 0.6f)));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        float sw = r * (1.7f - 0.8f * heightFactor);
        oval.set(bx - sw, floor + r * 0.8f, bx + sw, floor + r * 1.15f);
        c.drawOval(oval, paint);
        float rx = r * (1f + 0.3f * squash), ry = r * (1f - 0.3f * squash);
        float cy = by + (r - ry);
        paint.setColor(0xFFFF7043);
        oval.set(bx - rx, cy - ry, bx + rx, cy + ry);
        c.drawOval(oval, paint);
        paint.setColor(0x88FFFFFF);
        c.drawCircle(bx - rx * 0.35f, cy - ry * 0.35f, r * 0.22f, paint);
        attX = bx;
        attY = by;
    }

    // ---- Burbujas -------------------------------------------------------------

    private void stepBubbles(Canvas c, float dt) {
        float w = getWidth(), h = getHeight(), u = Math.min(w, h);
        nextBubble -= dt;
        if (t < duration - 2.5f && nextBubble <= 0f) {
            spawnBubble(w, h, u);
            nextBubble = 0.45f + 0.5f * random.nextFloat();
        }
        float topY = Float.MAX_VALUE, topX = attX;
        for (int i = 0; i < MAX_BUBBLES; i++) {
            if (!bubOn[i]) continue;
            if (bubPopT[i] >= 0f) {
                bubPopT[i] += dt * 4f;
                if (bubPopT[i] > 1f) { bubOn[i] = false; continue; }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(u * 0.006f);
                paint.setColor(((int) (200 * (1f - bubPopT[i])) << 24) | 0x0000BFA6);
                c.drawCircle(bubX[i], bubY[i], bubR[i] * (1f + bubPopT[i] * 0.6f), paint);
                continue;
            }
            bubY[i] -= bubV[i] * dt;
            bubPh[i] += dt * 2f;
            float x = bubX[i] + (float) Math.sin(bubPh[i]) * u * 0.03f;
            if (bubY[i] < bubPopY[i]) { bubPopT[i] = 0f; emit("bubble_pop"); }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x2200BFA6);
            c.drawCircle(x, bubY[i], bubR[i], paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(u * 0.005f);
            paint.setColor(0xAA00BFA6);
            c.drawCircle(x, bubY[i], bubR[i], paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x99FFFFFF);
            c.drawCircle(x - bubR[i] * 0.35f, bubY[i] - bubR[i] * 0.35f, bubR[i] * 0.16f, paint);
            if (bubY[i] < topY) { topY = bubY[i]; topX = x; }
        }
        if (topY != Float.MAX_VALUE) {
            float a = Math.min(1f, dt * 6f);
            attX += (topX - attX) * a;
            attY += (topY - attY) * a;
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void spawnBubble(float w, float h, float u) {
        for (int i = 0; i < MAX_BUBBLES; i++) {
            if (bubOn[i]) continue;
            bubOn[i] = true;
            bubR[i] = u * (0.03f + 0.05f * random.nextFloat());
            bubX[i] = w * (0.1f + 0.8f * random.nextFloat());
            bubY[i] = h + bubR[i];
            bubV[i] = h * (0.14f + 0.16f * random.nextFloat());
            bubPh[i] = random.nextFloat() * 6.28f;
            bubPopY[i] = h * (0.12f + 0.5f * random.nextFloat());
            bubPopT[i] = -1f;
            return;
        }
    }

    // ---- Mosca ----------------------------------------------------------------

    private void stepFly(Canvas c, float dt) {
        float w = getWidth(), h = getHeight(), u = Math.min(w, h);
        float cx = focusX >= 0f ? focusX : w / 2f;
        float cy = focusY >= 0f ? focusY : h / 2f;
        if (!flyCaught) {
            nextTarget -= dt;
            closeCooldown -= dt;
            boolean landing = t > duration - 2f;
            if (landing) {
                ftx = cx;
                fty = cy - u * 0.1f;
            } else if (nextTarget <= 0f) {
                if (random.nextInt(100) < 55) {
                    double angle = random.nextDouble() * Math.PI * 2;
                    float rad = u * (0.14f + 0.16f * random.nextFloat());
                    ftx = cx + (float) Math.cos(angle) * rad * 1.6f;
                    fty = cy + (float) Math.sin(angle) * rad;
                } else {
                    ftx = w * (0.08f + 0.84f * random.nextFloat());
                    fty = h * (0.1f + 0.8f * random.nextFloat());
                }
                nextTarget = 0.22f + 0.5f * random.nextFloat();
            }
            float a = 1f - (float) Math.exp(-dt * (landing ? 3f : 6f + 6f * random.nextFloat()));
            fx += (ftx - fx) * a;
            fy += (fty - fy) * a;
            float dist = (float) Math.hypot(fx - cx, fy - cy);
            if (!landing && dist < u * 0.2f && closeCooldown <= 0f) {
                emit("fly_close");
                closeCooldown = 1.6f;
            }
            if (landing && (dist < u * 0.14f || t > duration - 1.1f)) {
                flyCaught = true;
                caughtT = 0f;
                duration = Math.min(duration, t + 0.7f);
                emit("fly_caught");
            }
        } else {
            caughtT += dt * 2.5f;
        }
        float jx = (random.nextFloat() - 0.5f) * u * 0.012f;
        float jy = (random.nextFloat() - 0.5f) * u * 0.012f;
        if (!flyCaught) {
            float rx = u * 0.012f, ry = u * 0.008f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66B0BEC5);
            boolean up = ((int) (t * 40f)) % 2 == 0;
            float wy = up ? -ry * 2.2f : -ry * 1.2f;
            oval.set(fx + jx - rx * 1.6f, fy + jy + wy - ry, fx + jx - rx * 0.1f, fy + jy + wy + ry);
            c.drawOval(oval, paint);
            oval.set(fx + jx + rx * 0.1f, fy + jy + wy - ry, fx + jx + rx * 1.6f, fy + jy + wy + ry);
            c.drawOval(oval, paint);
            paint.setColor(0xFF78909C);
            oval.set(fx + jx - rx, fy + jy - ry, fx + jx + rx, fy + jy + ry);
            c.drawOval(oval, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(u * 0.005f);
            paint.setColor(((int) (200 * Math.max(0f, 1f - caughtT)) << 24) | 0x0078909C);
            c.drawCircle(fx, fy, u * (0.02f + 0.05f * caughtT), paint);
            paint.setStyle(Paint.Style.FILL);
        }
        attX = fx;
        attY = fy;
    }

    // ---- Estrellas fugaces ----------------------------------------------------

    private void stepStars(Canvas c, float dt) {
        float w = getWidth(), h = getHeight(), u = Math.min(w, h);
        starT += dt;
        if (starsLeft > 0 && starT > 1.7f && t > 0.2f) {
            starsLeft--;
            starT = 0f;
            trailN = 0;
            boolean fromLeft = random.nextBoolean();
            sx = fromLeft ? w * (0.05f + 0.25f * random.nextFloat()) : w * (0.7f + 0.25f * random.nextFloat());
            sy = h * (0.03f + 0.1f * random.nextFloat());
            svx = (fromLeft ? 1f : -1f) * w * (0.55f + 0.2f * random.nextFloat());
            svy = h * (0.5f + 0.15f * random.nextFloat());
            emit("star");
        }
        if (starT < 1.4f) {
            sx += svx * dt;
            sy += svy * dt;
            for (int i = trailX.length - 1; i > 0; i--) { trailX[i] = trailX[i - 1]; trailY[i] = trailY[i - 1]; }
            trailX[0] = sx;
            trailY[0] = sy;
            trailN = Math.min(trailX.length, trailN + 1);
            paint.setStyle(Paint.Style.FILL);
            float headR = u * 0.012f;
            for (int i = trailN - 1; i >= 0; i--) {
                int alpha = (int) (200 * (1f - i / (float) trailX.length));
                paint.setColor((alpha << 24) | 0x00FFD166);
                c.drawCircle(trailX[i], trailY[i], headR * (1f - i * 0.08f), paint);
            }
            attX = sx;
            attY = sy;
        }
    }

    private void emit(String event) {
        if (listener != null) listener.onEvent(game, event);
    }
}
