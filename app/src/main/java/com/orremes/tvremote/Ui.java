package com.orremes.tvremote;

import android.content.Context;
import android.util.TypedValue;

/** Shared colours and size helpers. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0E0F12;
    static final int SURFACE = 0xFF1A1C21;
    static final int STROKE = 0xFF2E313A;
    static final int TEXT = 0xFFE8EAED;
    static final int MUTED = 0xFF8A8F98;
    static final int ACCENT = 0xFF4C8DFF;
    static final int DANGER = 0xFFE5484D;
    static final int OK_GREEN = 0xFF66BB6A;
    static final int PRESS = 0x33FFFFFF;

    static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }
}
