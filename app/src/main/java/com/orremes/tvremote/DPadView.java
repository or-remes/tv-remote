package com.orremes.tvremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/** A round direction pad: four arrow sectors around an OK button. */
final class DPadView extends View {
    private static final int UP = 0;
    private static final int RIGHT = 1;
    private static final int DOWN = 2;
    private static final int LEFT = 3;
    private static final int CENTER = 4;

    private final KeyCallback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint okFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint okText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF outer = new RectF();
    private final RectF innerRect = new RectF();
    private final Path path = new Path();
    private final int size;
    private int zone = -1;
    private int zoneCode;

    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            callback.onRepeat(zoneCode);
            handler.postDelayed(this, 130);
        }
    };

    DPadView(Context context, KeyCallback callback) {
        super(context);
        this.callback = callback;
        this.size = (int) Ui.dp(context, 196);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Ui.SURFACE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(context, 1));
        stroke.setColor(Ui.STROKE);
        press.setStyle(Paint.Style.FILL);
        press.setColor(Ui.PRESS);
        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeWidth(Ui.dp(context, 2));
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        chevronPaint.setColor(Ui.TEXT);
        okFill.setStyle(Paint.Style.FILL);
        okText.setColor(0xFFFFFFFF);
        okText.setTextAlign(Paint.Align.CENTER);
        okText.setTextSize(Ui.sp(context, 15));
        okText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - stroke.getStrokeWidth();
        float ir = r * 0.40f;

        canvas.drawCircle(cx, cy, r, fill);
        canvas.drawCircle(cx, cy, r, stroke);

        if (zone >= UP && zone <= LEFT) {
            outer.set(cx - r, cy - r, cx + r, cy + r);
            innerRect.set(cx - ir, cy - ir, cx + ir, cy + ir);
            float start = zone == UP ? 225f : zone == RIGHT ? 315f : zone == DOWN ? 45f : 135f;
            path.reset();
            path.arcTo(outer, start, 90f, true);
            path.arcTo(innerRect, start + 90f, -90f, false);
            path.close();
            canvas.drawPath(path, press);
        }

        float d = (r + ir) / 2f;
        float s = r * 0.075f;
        chevron(canvas, cx, cy - d, UP, s);
        chevron(canvas, cx + d, cy, RIGHT, s);
        chevron(canvas, cx, cy + d, DOWN, s);
        chevron(canvas, cx - d, cy, LEFT, s);

        okFill.setColor(zone == CENTER ? Ui.ACCENT : 0xFF2D5BBA);
        canvas.drawCircle(cx, cy, ir * 0.92f, okFill);
        float baseline = cy - (okText.descent() + okText.ascent()) / 2f;
        canvas.drawText("נודר", cx, baseline, okText);
    }

    private void chevron(Canvas c, float x, float y, int dir, float s) {
        path.reset();
        switch (dir) {
            case UP:
                path.moveTo(x - s, y + s * 0.5f);
                path.lineTo(x, y - s * 0.5f);
                path.lineTo(x + s, y + s * 0.5f);
                break;
            case DOWN:
                path.moveTo(x - s, y - s * 0.5f);
                path.lineTo(x, y + s * 0.5f);
                path.lineTo(x + s, y - s * 0.5f);
                break;
            case LEFT:
                path.moveTo(x + s * 0.5f, y - s);
                path.lineTo(x - s * 0.5f, y);
                path.lineTo(x + s * 0.5f, y + s);
                break;
            default:
                path.moveTo(x - s * 0.5f, y - s);
                path.lineTo(x + s * 0.5f, y);
                path.lineTo(x - s * 0.5f, y + s);
                break;
        }
        c.drawPath(path, chevronPaint);
    }

    private int zoneAt(float x, float y) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f;
        float dx = x - cx;
        float dy = y - cy;
        double dist = Math.hypot(dx, dy);
        if (dist > r) {
            return -1;
        }
        if (dist < r * 0.40f) {
            return CENTER;
        }
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        if (angle < 0) {
            angle += 360;
        }
        if (angle >= 315 || angle < 45) {
            return RIGHT;
        }
        if (angle < 135) {
            return DOWN;
        }
        if (angle < 225) {
            return LEFT;
        }
        return UP;
    }

    private static int codeFor(int zone) {
        switch (zone) {
            case UP: return Keys.DPAD_UP;
            case RIGHT: return Keys.DPAD_RIGHT;
            case DOWN: return Keys.DPAD_DOWN;
            case LEFT: return Keys.DPAD_LEFT;
            default: return Keys.DPAD_CENTER;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int z = zoneAt(e.getX(), e.getY());
                if (z < 0) {
                    return false; // outside the circle: let the page scroll
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                zone = z;
                zoneCode = codeFor(z);
                invalidate();
                callback.onPress(zoneCode, this);
                if (z != CENTER) {
                    handler.postDelayed(repeater, 450);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                zone = -1;
                handler.removeCallbacks(repeater);
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(repeater);
    }
}
