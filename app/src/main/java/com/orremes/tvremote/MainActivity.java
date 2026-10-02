package com.orremes.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** One screen: a status line on top and the remote below. */
public class MainActivity extends Activity {
    private static final String PREFS = "tvremote";
    private static final int MAX_RETRIES = 5;

    // Opening apps on the box. YouTube works with a web link; the other two are launched
    // by package name through the Play Store launcher link.
    private static final String LINK_YOUTUBE = "https://www.youtube.com";
    private static final String LINK_NETFLIX = "market://launch?id=com.netflix.ninja";
    private static final String LINK_SPOTIFY = "market://launch?id=com.spotify.tv.android";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Identity identity;
    private RemoteClient remote;
    private PairingClient pairing;
    private Discovery discovery;
    private TextView statusView;
    private EditText ipField;
    private LinearLayout settingsPanel;
    private LinearLayout numberPad;

    private boolean visible;
    private int retries;
    private String currentIp = "";
    private String lastReason = "";

    private final Runnable reconnectTask = new Runnable() {
        @Override public void run() {
            if (visible && remote == null && pairing == null && currentIp.length() > 0) {
                startRemote(currentIp);
            }
        }
    };

    private final KeyCallback keyCallback = new KeyCallback() {
        @Override public void onPress(int keyCode, View source) {
            pressKey(keyCode, source);
        }

        @Override public void onRepeat(int keyCode) {
            sendKeyQuiet(keyCode);
        }
    };

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
        visible = true;
        String ip = prefs.getString("ip", "");
        if (identity != null && remote == null && pairing == null
                && ip.length() > 0 && prefs.getBoolean("paired_" + ip, false)) {
            retries = 0;
            currentIp = ip;
            startRemote(ip);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        closeRemote();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        visible = false;
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
        currentIp = ip;
        retries = 0;
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
        currentIp = ip;
        setStatus("מתחבר לממיר…", false);
        final RemoteClient[] self = new RemoteClient[1];
        self[0] = new RemoteClient(ip, identity, new RemoteClient.Listener() {
            @Override public void onReady() {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (self[0] != remote) {
                            return;
                        }
                        retries = 0;
                        setStatus("מחובר לממיר"
                                + (lastReason.length() > 0 ? "\nהניתוק האחרון: " + lastReason : ""), true);
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
                        lastReason = reason;
                        boolean paired = prefs.getBoolean("paired_" + ip, false);
                        if (visible && paired && retries < MAX_RETRIES) {
                            retries++;
                            setStatus("מתחבר מחדש…", false);
                            ui.postDelayed(reconnectTask, wasReady ? 800 : 1500L * retries);
                        } else {
                            setStatus("מנותק: " + reason
                                    + (wasReady ? "" : "\nאם זו הפעם הראשונה או שהצימוד נמחק, לחץ 'צימוד מחדש'."),
                                    false);
                            settingsPanel.setVisibility(View.VISIBLE);
                        }
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
        ui.removeCallbacks(reconnectTask);
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

    /** Makes sure we are connected; if not, starts reconnecting. */
    private boolean ensureConnected() {
        if (remote != null && remote.isReady()) {
            return true;
        }
        String ip = prefs.getString("ip", "");
        if (identity != null && remote == null && pairing == null
                && ip.length() > 0 && prefs.getBoolean("paired_" + ip, false)) {
            Toast.makeText(this, "מתחבר מחדש… נסה שוב עוד רגע", Toast.LENGTH_SHORT).show();
            retries = 0;
            startRemote(ip);
        } else {
            Toast.makeText(this, "לא מחובר לממיר", Toast.LENGTH_SHORT).show();
            settingsPanel.setVisibility(View.VISIBLE);
        }
        return false;
    }

    private void pressKey(int code, View source) {
        if (!ensureConnected()) {
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
        if (!ensureConnected()) {
            return;
        }
        source.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        remote.openLink(url);
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(14), dp(18), dp(28));
        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // header: status text and a small settings button
        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(Ui.MUTED);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusView.setLayoutParams(statusLp);

        Button settings = pill("הגדרות", Ui.MUTED, Ui.STROKE);
        settings.setTextSize(12);
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(78), dp(34));
        settingsLp.setMargins(dp(8), 0, 0, 0);
        settings.setLayoutParams(settingsLp);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                settingsPanel.setVisibility(
                        settingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(statusView);
        header.addView(settings);
        col.addView(header);

        // connection settings
        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setVisibility(View.GONE);
        settingsPanel.setPadding(0, dp(8), 0, dp(4));

        ipField = new EditText(this);
        ipField.setHint("כתובת IP של הממיר");
        ipField.setHintTextColor(Ui.MUTED);
        ipField.setTextColor(Ui.TEXT);
        ipField.setTextSize(16);
        ipField.setGravity(Gravity.CENTER);
        ipField.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ipField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        settingsPanel.addView(ipField);

        Button search = pill("חיפוש אוטומטי", Ui.TEXT, Ui.STROKE);
        search.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                autoSearch();
            }
        });
        Button connectBtn = pill("התחבר", Ui.TEXT, Ui.ACCENT);
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                connect();
            }
        });
        Button pair = pill("צימוד מחדש", Ui.TEXT, Ui.STROKE);
        pair.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                repair();
            }
        });
        settingsPanel.addView(row(search, connectBtn, pair));
        col.addView(settingsPanel);

        // the remote: always left-to-right so the arrows point the right way
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pad.setPadding(0, dp(12), 0, 0);

        Button power = pill("כיבוי", Ui.DANGER, Ui.DANGER);
        power.setOnClickListener(keyClick(Keys.POWER));
        Button home = pill("בית", Ui.TEXT, Ui.STROKE);
        home.setOnClickListener(keyClick(Keys.HOME));
        Button back = pill("חזרה", Ui.TEXT, Ui.STROKE);
        back.setOnClickListener(keyClick(Keys.BACK));
        pad.addView(row(power, home, back));

        DPadView dpad = new DPadView(this, keyCallback);
        LinearLayout.LayoutParams dpadLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dpadLp.gravity = Gravity.CENTER_HORIZONTAL;
        dpadLp.setMargins(0, dp(18), 0, dp(14));
        pad.addView(dpad, dpadLp);

        // volume rocker, mute, channel rocker
        RockerView volume = new RockerView(this, "ווליום", false,
                Keys.VOLUME_UP, Keys.VOLUME_DOWN, true, keyCallback);
        RockerView channels = new RockerView(this, "ערוץ", true,
                Keys.CHANNEL_UP, Keys.CHANNEL_DOWN, false, keyCallback);
        Button mute = pill("השתקה", Ui.TEXT, Ui.STROKE);
        mute.setOnClickListener(keyClick(Keys.VOLUME_MUTE));
        mute.setLayoutParams(new LinearLayout.LayoutParams(dp(92), dp(42)));

        LinearLayout rockers = new LinearLayout(this);
        rockers.setOrientation(LinearLayout.HORIZONTAL);
        rockers.setGravity(Gravity.CENTER_VERTICAL);
        rockers.setPadding(dp(20), dp(4), dp(20), dp(14));
        rockers.addView(volume);
        rockers.addView(flex());
        rockers.addView(mute);
        rockers.addView(flex());
        rockers.addView(channels);
        pad.addView(rockers);

        // media keys and recording
        pad.addView(row(
                media(IconButton.REW, Keys.MEDIA_REWIND),
                media(IconButton.PLAY, Keys.MEDIA_PLAY),
                media(IconButton.PAUSE, Keys.MEDIA_PAUSE),
                media(IconButton.STOP, Keys.MEDIA_STOP),
                media(IconButton.FF, Keys.MEDIA_FAST_FORWARD)));

        SpannableString recordText = new SpannableString("●  הקלטת תוכנית");
        recordText.setSpan(new ForegroundColorSpan(Ui.DANGER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Button record = pill(recordText, Ui.TEXT, Ui.STROKE);
        record.setOnClickListener(keyClick(Keys.MEDIA_RECORD));
        Button recordings = pill("הקלטות", Ui.TEXT, Ui.STROKE);
        recordings.setOnClickListener(keyClick(Keys.DVR));
        pad.addView(row(record, recordings));

        // apps on the box
        pad.addView(spaceRow(10));
        pad.addView(row(
                app("YouTube", LINK_YOUTUBE),
                app("Netflix", LINK_NETFLIX),
                app("Spotify", LINK_SPOTIFY)));

        // number keys, hidden until needed
        pad.addView(spaceRow(6));
        Button numbersToggle = pill("מקשי ספרות", Ui.MUTED, Ui.STROKE);
        numbersToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                numberPad.setVisibility(numberPad.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        pad.addView(row(numbersToggle));

        numberPad = new LinearLayout(this);
        numberPad.setOrientation(LinearLayout.VERTICAL);
        numberPad.setVisibility(View.GONE);
        for (int r = 0; r < 3; r++) {
            numberPad.addView(row(
                    digit(r * 3 + 1), digit(r * 3 + 2), digit(r * 3 + 3)));
        }
        numberPad.addView(row(flex(), digit(0), flex()));
        pad.addView(numberPad);

        col.addView(pad);
        setContentView(scroll);
    }

    private View.OnClickListener keyClick(final int code) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                pressKey(code, v);
            }
        };
    }

    private View media(int type, int code) {
        IconButton b = new IconButton(this, type);
        b.setOnClickListener(keyClick(code));
        return b;
    }

    private Button digit(int d) {
        Button b = pill(String.valueOf(d), Ui.TEXT, Ui.STROKE);
        b.setOnClickListener(keyClick(Keys.digit(d)));
        return b;
    }

    private Button app(String label, final String url) {
        Button b = pill(label, Ui.TEXT, Ui.STROKE);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openLink(url, v);
            }
        });
        return b;
    }

    /** A slim outlined button that shares the row width with its neighbours. */
    private Button pill(CharSequence label, int textColor, int strokeColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setStateListAnimator(null);
        b.setElevation(0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Ui.SURFACE);
        shape.setCornerRadius(dp(22));
        shape.setStroke(dp(1), strokeColor);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.PRESS), shape, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            r.addView(v);
        }
        return r;
    }

    /** Empty space that takes up its share of a row. */
    private View flex() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
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
        statusView.setTextColor(ok ? Ui.OK_GREEN : Ui.MUTED);
    }

    private int dp(int v) {
        return (int) Ui.dp(this, v);
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return (m != null && m.length() > 0) ? m : e.getClass().getSimpleName();
    }
}
