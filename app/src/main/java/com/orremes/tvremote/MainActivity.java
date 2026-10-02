package com.orremes.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** One screen: connection settings on top, the remote below. */
public class MainActivity extends Activity {
    private static final String PREFS = "tvremote";
    private static final int BG = 0xFF121212;
    private static final int BTN = 0xFF2C2C2E;
    private static final int ACCENT = 0xFF3D7BFF;
    private static final int DANGER = 0xFFC62828;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MUTED = 0xFFB0B0B0;
    private static final int OK_GREEN = 0xFF66BB6A;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Identity identity;
    private RemoteClient remote;
    private PairingClient pairing;
    private Discovery discovery;
    private TextView statusView;
    private EditText ipField;
    private LinearLayout settingsPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        try {
            identity = new Identity(getFilesDir());
        } catch (Exception e) {
            setStatus("שגיאה בהכנת האפליקציה: " + describe(e), false);
            return;
        }
        String ip = prefs.getString("ip", "");
        ipField.setText(ip);
        if (ip.length() == 0) {
            settingsPanel.setVisibility(View.VISIBLE);
            setStatus("הקלד את כתובת ה-IP של הממיר, או לחץ על חיפוש אוטומטי", false);
        } else if (!prefs.getBoolean("paired_" + ip, false)) {
            settingsPanel.setVisibility(View.VISIBLE);
            setStatus("הצימוד עם הממיר עדיין לא בוצע. לחץ 'התחבר'.", false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        String ip = prefs.getString("ip", "");
        if (identity != null && remote == null && pairing == null
                && ip.length() > 0 && prefs.getBoolean("paired_" + ip, false)) {
            startRemote(ip);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        closeRemote();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeRemote();
        closePairing();
        if (discovery != null) {
            discovery.stop();
        }
    }

    // ---------------------------------------------------------------- connection

    private void connect() {
        if (identity == null) {
            return;
        }
        String ip = ipField.getText().toString().trim();
        if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            settingsPanel.setVisibility(View.VISIBLE);
            setStatus("הקלד כתובת IP תקינה של הממיר, למשל 192.168.1.50", false);
            return;
        }
        prefs.edit().putString("ip", ip).apply();
        closeRemote();
        closePairing();
        if (prefs.getBoolean("paired_" + ip, false)) {
            startRemote(ip);
        } else {
            startPairing(ip);
        }
    }

    private void repair() {
        String ip = ipField.getText().toString().trim();
        if (ip.length() > 0) {
            prefs.edit().remove("paired_" + ip).apply();
        }
        connect();
    }

    private void startRemote(final String ip) {
        setStatus("מתחבר לממיר…", false);
        final RemoteClient[] self = new RemoteClient[1];
        self[0] = new RemoteClient(ip, identity, new RemoteClient.Listener() {
            @Override public void onReady() {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (self[0] != remote) {
                            return;
                        }
                        setStatus("מחובר לממיר", true);
                        settingsPanel.setVisibility(View.GONE);
                    }
                });
            }

            @Override public void onClosed(final boolean wasReady, final String reason) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (self[0] != remote) {
                            return;
                        }
                        remote = null;
                        setStatus("מנותק: " + reason
                                + (wasReady ? "" : "\nאם זו הפעם הראשונה או שהצימוד נמחק, לחץ 'צימוד מחדש'."), false);
                        settingsPanel.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
        remote = self[0];
        remote.start();
    }

    private void startPairing(final String ip) {
        setStatus("מתחבר לממיר כדי לבצע צימוד. ודא שהטלוויזיה והממיר דולקים…", false);
        final PairingClient pc = new PairingClient(ip, identity);
        pairing = pc;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    pc.begin();
                    ui.post(new Runnable() {
                        @Override public void run() {
                            askForCode(ip, pc);
                        }
                    });
                } catch (final Exception e) {
                    pc.close();
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (pairing == pc) {
                                pairing = null;
                            }
                            settingsPanel.setVisibility(View.VISIBLE);
                            setStatus("הצימוד נכשל: " + describe(e), false);
                        }
                    });
                }
            }
        }, "pairing").start();
    }

    private void askForCode(final String ip, final PairingClient pc) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(6)});
        input.setGravity(Gravity.CENTER);
        input.setTextSize(24);
        input.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        new AlertDialog.Builder(this)
                .setTitle("קוד צימוד")
                .setMessage("על מסך הטלוויזיה מופיע קוד בן 6 תווים (ספרות ואותיות A-F). הקלד אותו כאן.")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("אישור", (dialog, which) -> {
                    final String code = input.getText().toString().trim();
                    setStatus("מאמת את הקוד…", false);
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                pc.finish(code);
                                pc.close();
                                prefs.edit().putBoolean("paired_" + ip, true).apply();
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        if (pairing == pc) {
                                            pairing = null;
                                        }
                                        Toast.makeText(MainActivity.this, "הצימוד הצליח", Toast.LENGTH_SHORT).show();
                                        startRemote(ip);
                                    }
                                });
                            } catch (final Exception e) {
                                pc.close();
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        if (pairing == pc) {
                                            pairing = null;
                                        }
                                        settingsPanel.setVisibility(View.VISIBLE);
                                        setStatus("הצימוד נכשל: " + describe(e)
                                                + "\nלחץ 'צימוד מחדש' כדי לנסות שוב.", false);
                                    }
                                });
                            }
                        }
                    }, "pairing-finish").start();
                })
                .setNegativeButton("ביטול", (dialog, which) -> {
                    pc.close();
                    if (pairing == pc) {
                        pairing = null;
                    }
                    setStatus("הצימוד בוטל", false);
                })
                .show();
    }

    private void closeRemote() {
        if (remote != null) {
            remote.close();
            remote = null;
        }
    }

    private void closePairing() {
        if (pairing != null) {
            pairing.close();
            pairing = null;
        }
    }

    private void autoSearch() {
        if (discovery != null) {
            discovery.stop();
        }
        setStatus("מחפש ממיר ברשת…", false);
        final Discovery d = new Discovery(this, new Discovery.Callback() {
            @Override public void onFound(final String host) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        ipField.setText(host);
                        setStatus("נמצא ממיר בכתובת " + host + ". לחץ 'התחבר'.", false);
                    }
                });
            }

            @Override public void onFail(final String message) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        setStatus(message, false);
                    }
                });
            }
        });
        discovery = d;
        d.start();
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                d.timeout();
            }
        }, 10000);
    }

    // ---------------------------------------------------------------- keys

    private void pressKey(int code, View source) {
        if (remote == null || !remote.isReady()) {
            Toast.makeText(this, "לא מחובר לממיר", Toast.LENGTH_SHORT).show();
            settingsPanel.setVisibility(View.VISIBLE);
            return;
        }
        source.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        remote.sendKey(code);
    }

    private void sendKeyQuiet(int code) {
        RemoteClient r = remote;
        if (r != null && r.isReady()) {
            r.sendKey(code);
        }
    }

    private void openLink(String url, View source) {
        if (remote == null || !remote.isReady()) {
            Toast.makeText(this, "לא מחובר לממיר", Toast.LENGTH_SHORT).show();
            return;
        }
        source.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        remote.openLink(url);
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(14), dp(10), dp(28));
        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("שלט לממיר", 22, WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(title);

        statusView = text("", 14, MUTED);
        statusView.setPadding(dp(8), dp(4), dp(8), dp(8));
        col.addView(statusView);

        // connection settings
        Button toggle = button("⚙ הגדרות חיבור", BTN);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                settingsPanel.setVisibility(
                        settingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        LinearLayout toggleRow = row(toggle);
        col.addView(toggleRow);

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setVisibility(View.GONE);

        ipField = new EditText(this);
        ipField.setHint("כתובת IP של הממיר");
        ipField.setHintTextColor(MUTED);
        ipField.setTextColor(WHITE);
        ipField.setGravity(Gravity.CENTER);
        ipField.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ipField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        settingsPanel.addView(ipField);

        Button search = button("חיפוש אוטומטי", BTN);
        search.setTextSize(14);
        search.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                autoSearch();
            }
        });
        Button connectBtn = button("התחבר", ACCENT);
        connectBtn.setTextSize(14);
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                connect();
            }
        });
        Button pair = button("צימוד מחדש", BTN);
        pair.setTextSize(14);
        pair.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                repair();
            }
        });
        settingsPanel.addView(row(search, connectBtn, pair));
        col.addView(settingsPanel);

        // the remote itself: always left-to-right so the arrows point the right way
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pad.setPadding(0, dp(8), 0, 0);

        pad.addView(row(
                key("🏠 בית", BTN, Keys.HOME, false),
                key("↩ חזרה", BTN, Keys.BACK, false),
                key("☰ תפריט", BTN, Keys.MENU, false)));

        pad.addView(spaceRow(10));
        pad.addView(row(spacer(), key("▲", BTN, Keys.DPAD_UP, true), spacer()));
        pad.addView(row(
                key("◀", BTN, Keys.DPAD_LEFT, true),
                key("OK", ACCENT, Keys.DPAD_CENTER, false),
                key("▶", BTN, Keys.DPAD_RIGHT, true)));
        pad.addView(row(spacer(), key("▼", BTN, Keys.DPAD_DOWN, true), spacer()));

        pad.addView(spaceRow(10));
        pad.addView(row(
                key("מדריך", BTN, Keys.GUIDE, false),
                key("מידע", BTN, Keys.INFO, false),
                key("הקלטות", BTN, Keys.DVR, false)));

        pad.addView(spaceRow(10));
        pad.addView(row(
                key("⏪", BTN, Keys.MEDIA_REWIND, false),
                key("▶", BTN, Keys.MEDIA_PLAY, false),
                key("⏸", BTN, Keys.MEDIA_PAUSE, false),
                key("⏹", BTN, Keys.MEDIA_STOP, false),
                key("⏩", BTN, Keys.MEDIA_FAST_FORWARD, false)));
        pad.addView(row(key("⏺  הקלטת תוכנית", DANGER, Keys.MEDIA_RECORD, false)));

        pad.addView(spaceRow(10));
        pad.addView(row(
                key("ווליום +", BTN, Keys.VOLUME_UP, true),
                key("ווליום −", BTN, Keys.VOLUME_DOWN, true),
                key("השתקה", BTN, Keys.VOLUME_MUTE, false)));
        pad.addView(row(
                key("ערוץ ▲", BTN, Keys.CHANNEL_UP, false),
                key("ערוץ ▼", BTN, Keys.CHANNEL_DOWN, false),
                key("⏻ כיבוי", DANGER, Keys.POWER, false)));

        pad.addView(spaceRow(10));
        pad.addView(row(
                app("YouTube", "https://www.youtube.com"),
                app("Netflix", "https://www.netflix.com"),
                app("Spotify", "https://open.spotify.com")));

        pad.addView(spaceRow(10));
        pad.addView(row(
                key("1", BTN, Keys.digit(1), false),
                key("2", BTN, Keys.digit(2), false),
                key("3", BTN, Keys.digit(3), false)));
        pad.addView(row(
                key("4", BTN, Keys.digit(4), false),
                key("5", BTN, Keys.digit(5), false),
                key("6", BTN, Keys.digit(6), false)));
        pad.addView(row(
                key("7", BTN, Keys.digit(7), false),
                key("8", BTN, Keys.digit(8), false),
                key("9", BTN, Keys.digit(9), false)));
        pad.addView(row(spacer(), key("0", BTN, Keys.digit(0), false), spacer()));

        col.addView(pad);
        setContentView(scroll);
    }

    private Button key(String label, int color, final int code, boolean repeat) {
        final Button b = button(label, color);
        if (!repeat) {
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pressKey(code, v);
                }
            });
            return b;
        }
        // press and hold repeats the key (volume, arrows)
        final Runnable loop = new Runnable() {
            @Override public void run() {
                sendKeyQuiet(code);
                ui.postDelayed(this, 130);
            }
        };
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        pressKey(code, v);
                        ui.postDelayed(loop, 450);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        ui.removeCallbacks(loop);
                        return true;
                    default:
                        return true;
                }
            }
        });
        return b;
    }

    private Button app(String label, final String url) {
        Button b = button(label, BTN);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openLink(url, v);
            }
        });
        return b;
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(WHITE);
        b.setTextSize(16);
        b.setPadding(dp(2), 0, dp(2), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(14));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), shape, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            r.addView(v);
        }
        return r;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, dp(56), 1f));
        return v;
    }

    private View spaceRow(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return v;
    }

    private void setStatus(String message, boolean ok) {
        statusView.setText(message);
        statusView.setTextColor(ok ? OK_GREEN : MUTED);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return (m != null && m.length() > 0) ? m : e.getClass().getSimpleName();
    }
}
