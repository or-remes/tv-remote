package com.orremes.tvremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/** A tall rounded rocker with an upper and a lower half (volume, channels). */
final class RockerView extends View {
    private final KeyCallback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String label;
    private final boolean chevrons;
    private final int upKey;
    private final int downKey;
    private final boolean repeat;
    private final int width;
    private final int height;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint symbol = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final Path chevron = new Path();
    private int half = -1; // 0 = upper, 1 = lower
    private int activeKey;

    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            callback.onRepeat(activeKey);
            handler.postDelayed(this, 130);
        }
    };

    RockerView(Context context, String label, boolean chevrons, int upKey, int downKey,
               boolean repeat, KeyCallback callback) {
        super(context);
        this.callback = callback;
        this.label = label;
        this.chevrons = chevrons;
        this.upKey = upKey;
        this.downKey = downKey;
        this.repeat = repeat;
        this.width = (int) Ui.dp(context, 64);
        this.height = (int) Ui.dp(context, 144);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Ui.SURFACE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(context, 1));
        stroke.setColor(Ui.STROKE);
        press.setStyle(Paint.Style.FILL);
        press.setColor(Ui.PRESS);
        symbol.setColor(Ui.TEXT);
        symbol.setTextAlign(Paint.Align.CENTER);
        symbol.setTextSize(Ui.sp(context, 22));
        symbol.setStyle(Paint.Style.STROKE);
        symbol.setStrokeWidth(Ui.dp(context, 2));
        symbol.setStrokeCap(Paint.Cap.ROUND);
        symbol.setStrokeJoin(Paint.Join.ROUND);
        labelPaint.setColor(Ui.MUTED);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(Ui.sp(context, 10));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = stroke.getStrokeWidth();
        float w = getWidth();
        float h = getHeight();
        rect.set(inset, inset, w - inset, h - inset);
        float corner = (w - 2 * inset) / 2f;

        canvas.drawRoundRect(rect, corner, corner, fill);

        if (half >= 0) {
            clip.reset();
            clip.addRoundRect(rect, corner, corner, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            float top = half == 0 ? 0 : h / 2f;
            canvas.drawRect(0, top, w, top + h / 2f, press);
            canvas.restore();
        }
        canvas.drawRoundRect(rect, corner, corner, stroke);

        float cx = w / 2f;
        float upY = h * 0.22f;
        float downY = h * 0.78f;
        if (chevrons) {
            drawChevron(canvas, cx, upY, true);
            drawChevron(canvas, cx, downY, false);
        } else {
            float s = Ui.dp(getContext(), 6);
            canvas.drawLine(cx - s, upY, cx + s, upY, symbol); // minus bar of the plus
            canvas.drawLine(cx, upY - s, cx, upY + s, symbol);
            canvas.drawLine(cx - s, downY, cx + s, downY, symbol);
        }
        float baseline = h / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f;
        canvas.drawText(label, cx, baseline, labelPaint);
    }

    private void drawChevron(Canvas c, float x, float y, boolean up) {
        float s = Ui.dp(getContext(), 7);
        chevron.reset();
        if (up) {
            chevron.moveTo(x - s, y + s * 0.5f);
            chevron.lineTo(x, y - s * 0.5f);
            chevron.lineTo(x + s, y + s * 0.5f);
        } else {
            chevron.moveTo(x - s, y - s * 0.5f);
            chevron.lineTo(x, y + s * 0.5f);
            chevron.lineTo(x + s, y - s * 0.5f);
        }
        c.drawPath(chevron, symbol);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                half = e.getY() < getHeight() / 2f ? 0 : 1;
                activeKey = half == 0 ? upKey : downKey;
                invalidate();
                callback.onPress(activeKey, this);
                if (repeat) {
                    handler.postDelayed(repeater, 450);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                half = -1;
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
