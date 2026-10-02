package com.orremes.tvremote;

import android.view.View;

/** Receives key presses from the custom remote controls. */
interface KeyCallback {
    /** The user pressed a key (called once, on touch down). */
    void onPress(int keyCode, View source);

    /** The user is still holding the key (called repeatedly). */
    void onRepeat(int keyCode);
}
