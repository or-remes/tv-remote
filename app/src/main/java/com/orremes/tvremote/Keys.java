package com.orremes.tvremote;

/** Android key codes understood by Android TV boxes. */
final class Keys {
    private Keys() {}

    static final int HOME = 3;
    static final int BACK = 4;
    static final int DPAD_UP = 19;
    static final int DPAD_DOWN = 20;
    static final int DPAD_LEFT = 21;
    static final int DPAD_RIGHT = 22;
    static final int DPAD_CENTER = 23;
    static final int VOLUME_UP = 24;
    static final int VOLUME_DOWN = 25;
    static final int POWER = 26;
    static final int MENU = 82;
    static final int MEDIA_STOP = 86;
    static final int MEDIA_REWIND = 89;
    static final int MEDIA_FAST_FORWARD = 90;
    static final int MEDIA_PLAY = 126;
    static final int MEDIA_PAUSE = 127;
    static final int MEDIA_RECORD = 130;
    static final int VOLUME_MUTE = 164;
    static final int INFO = 165;
    static final int CHANNEL_UP = 166;
    static final int CHANNEL_DOWN = 167;
    static final int GUIDE = 172;
    static final int DVR = 173;

    /** Digit keys: KEYCODE_0 is 7, KEYCODE_1 is 8, and so on. */
    static int digit(int d) {
        return 7 + d;
    }
}
