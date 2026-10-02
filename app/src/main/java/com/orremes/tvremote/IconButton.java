package com.orremes.tvremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.LinearLayout;

/** A small pill button with a drawn media symbol (play, pause, stop, rewind, fast forward). */
final class IconButton extends android.view.View {
    static final int REW = 0;
    static final int PLAY = 1;
    static final int PAUSE = 2;
    static final int STOP = 3;
    static final int FF = 4;

    private final int type;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    IconButton(Context context, int type) {
        super(context);
        this.type = type;
        setClickable(true);
        int m = (int) Ui.dp(context, 3);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) Ui.dp(context, 40), 1f);
        lp.setMargins(m, m, m, m);
        setLayoutParams(lp);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Ui.SURFACE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(context, 1));
        stroke.setColor(Ui.STROKE);
        press.setStyle(Paint.Style.FILL);
        press.setColor(Ui.PRESS);
        icon.setStyle(Paint.Style.FILL);
        icon.setColor(Ui.TEXT);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = stroke.getStrokeWidth();
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float corner = rect.height() / 2f;
        canvas.drawRoundRect(rect, corner, corner, fill);
        if (isPressed()) {
            canvas.drawRoundRect(rect, corner, corner, press);
        }
        canvas.drawRoundRect(rect, corner, corner, stroke);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float s = Ui.dp(getContext(), 6.5f);
        switch (type) {
            case PLAY:
                triangleRight(canvas, cx - s * 0.3f, cy, s);
                break;
            case PAUSE:
                canvas.drawRect(cx - s * 0.8f, cy - s, cx - s * 0.2f, cy + s, icon);
                canvas.drawRect(cx + s * 0.2f, cy - s, cx + s * 0.8f, cy + s, icon);
                break;
            case STOP:
                canvas.drawRect(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f, icon);
                break;
            case FF:
                triangleRight(canvas, cx - s * 0.9f, cy, s * 0.85f);
                triangleRight(canvas, cx + s * 0.1f, cy, s * 0.85f);
                break;
            default: // REW
                triangleLeft(canvas, cx + s * 0.9f, cy, s * 0.85f);
                triangleLeft(canvas, cx - s * 0.1f, cy, s * 0.85f);
                break;
        }
    }

    /** Triangle pointing right whose left edge is at x. */
    private void triangleRight(Canvas c, float x, float cy, float s) {
        path.reset();
        path.moveTo(x, cy - s);
        path.lineTo(x + s * 1.5f, cy);
        path.lineTo(x, cy + s);
        path.close();
        c.drawPath(path, icon);
    }

    /** Triangle pointing left whose right edge is at x. */
    private void triangleLeft(Canvas c, float x, float cy, float s) {
        path.reset();
        path.moveTo(x, cy - s);
        path.lineTo(x - s * 1.5f, cy);
        path.lineTo(x, cy + s);
        path.close();
        c.drawPath(path, icon);
    }
}
