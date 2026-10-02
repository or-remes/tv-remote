package com.orremes.tvremote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import javax.net.ssl.SSLSocket;

/**
 * A live connection to an Android TV box (port 6466) that sends key presses.
 * The box must already be paired with this phone.
 */
public final class RemoteClient implements Closeable {
    static final int PORT = 6466;
    private static final int FEATURES = 622;
    private static final int DIRECTION_SHORT = 3;

    public interface Listener {
        /** The box accepted us; keys can be sent. */
        void onReady();

        /** The connection ended or never started. */
        void onClosed(boolean wasReady, String reason);
    }

    private final String host;
    private final Identity identity;
    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile SSLSocket socket;
    private volatile OutputStream out;
    private volatile boolean closed;
    private volatile boolean ready;

    public RemoteClient(String host, Identity identity, Listener listener) {
        this.host = host;
        this.identity = identity;
        this.listener = listener;
    }

    public void start() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                runLoop();
            }
        }, "remote-reader");
        t.setDaemon(true);
        t.start();
    }

    private void runLoop() {
        String reason = "החיבור נסגר";
        try {
            SSLSocket s = identity.connect(host, PORT, 8000);
            socket = s;
            InputStream in = s.getInputStream();
            out = s.getOutputStream();
            // Until the box has greeted us, give up after 10 seconds. Once connected the box
            // pings regularly, so silence for 30 seconds means the link is dead.
            s.setSoTimeout(10000);

            while (!closed) {
                Map<Integer, List<Object>> m = Pb.parse(Pb.readFrame(in));

                if (m.containsKey(1)) {
                    sendConfigure();
                } else if (m.containsKey(2)) {
                    sendRaw(new Pb.Writer()
                            .msg(2, new Pb.Writer().varint(1, FEATURES))
                            .toBytes());
                    if (!ready) {
                        ready = true;
                        s.setSoTimeout(30000);
                        listener.onReady();
                    }
                } else if (m.containsKey(8)) {
                    byte[] ping = Pb.firstBytes(m, 8);
                    long val = ping == null ? 0 : Pb.firstLong(Pb.parse(ping), 1, 0);
                    sendRaw(new Pb.Writer()
                            .msg(9, new Pb.Writer().varint(1, val))
                            .toBytes());
                } else if (m.containsKey(3)) {
                    reason = "הממיר החזיר שגיאה";
                }
            }
        } catch (SocketTimeoutException e) {
            reason = ready ? "הממיר הפסיק להגיב" : "הממיר לא ענה. ייתכן שצריך צימוד";
        } catch (IOException e) {
            reason = ready ? "החיבור נותק" : "לא הצלחתי להתחבר. ייתכן שצריך צימוד";
        } catch (RuntimeException e) {
            reason = "שגיאה: " + e.getMessage();
        } finally {
            boolean wasReady = ready;
            ready = false;
            closeQuietly();
            if (!closed) {
                closed = true;
                listener.onClosed(wasReady, reason);
            }
        }
    }

    private void sendConfigure() throws IOException {
        Pb.Writer device = new Pb.Writer()
                .string(1, "Phone")
                .string(2, "Android")
                .varint(3, 1)
                .string(4, "1")
                .string(5, "com.orremes.tvremote")
                .string(6, "1.0.0");
        sendRaw(new Pb.Writer()
                .msg(1, new Pb.Writer().varint(1, FEATURES).msg(2, device))
                .toBytes());
    }

    private void sendRaw(byte[] message) throws IOException {
        OutputStream o = out;
        if (o == null) {
            throw new IOException("Not connected");
        }
        synchronized (this) {
            Pb.writeFrame(o, message);
        }
    }

    public boolean isReady() {
        return ready && !closed;
    }

    /** Sends a key press (runs on a background thread). */
    public void sendKey(final int keyCode) {
        final byte[] msg = new Pb.Writer()
                .msg(10, new Pb.Writer().varint(1, keyCode).varint(2, DIRECTION_SHORT))
                .toBytes();
        post(msg);
    }

    /** Asks the box to open a link, which launches the matching app if it is installed. */
    public void openLink(String url) {
        post(new Pb.Writer().msg(90, new Pb.Writer().string(1, url)).toBytes());
    }

    private void post(final byte[] msg) {
        try {
            writer.execute(new Runnable() {
                @Override public void run() {
                    try {
                        sendRaw(msg);
                    } catch (IOException e) {
                        closeQuietly();
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // closed already
        }
    }

    private void closeQuietly() {
        final SSLSocket s = socket;
        if (s == null) {
            return;
        }
        // Closing a TLS socket sends a goodbye message, which Android forbids on the UI
        // thread (it crashes the app), so always do it on a helper thread.
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // already closed
                }
            }
        }, "socket-close");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void close() {
        closed = true;
        ready = false;
        closeQuietly();
        writer.shutdownNow();
    }
}
