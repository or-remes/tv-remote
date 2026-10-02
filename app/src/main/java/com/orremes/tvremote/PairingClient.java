package com.orremes.tvremote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLSocket;

/**
 * One-time pairing with an Android TV box (port 6467). The box shows a 6 character code on
 * the TV screen; the user types it into the app.
 */
public final class PairingClient implements Closeable {
    static final int PORT = 6467;
    private static final int PROTOCOL_VERSION = 2;
    private static final int STATUS_OK = 200;

    private final String host;
    private final Identity identity;
    private SSLSocket socket;
    private InputStream in;
    private OutputStream out;
    private X509Certificate serverCert;

    public PairingClient(String host, Identity identity) {
        this.host = host;
        this.identity = identity;
    }

    /** Connects and negotiates until the box displays the code on screen. */
    public void begin() throws Exception {
        socket = identity.connect(host, PORT, 8000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
        serverCert = (X509Certificate) socket.getSession().getPeerCertificates()[0];
        socket.setSoTimeout(15000);

        // 1. Introduce ourselves.
        send(10, new Pb.Writer().string(1, "atvremote").string(2, "Phone Remote"));
        expect(11);

        // 2. Offer the code format: 6 hexadecimal symbols, we are the "input" side.
        send(20, new Pb.Writer()
                .msg(1, new Pb.Writer().varint(1, 3).varint(2, 6))
                .varint(3, 1));
        expect(20);

        // 3. Confirm the format. The box now shows the code on the TV.
        send(30, new Pb.Writer()
                .msg(1, new Pb.Writer().varint(1, 3).varint(2, 6))
                .varint(2, 1));
        expect(31);
    }

    /** Sends the code the user read from the TV screen. */
    public void finish(String code) throws Exception {
        byte[] codeBytes = hex(code);
        if (codeBytes.length != 3) {
            throw new IOException("הקוד חייב להכיל 6 תווים (0-9, A-F)");
        }
        RSAPublicKey client = (RSAPublicKey) identity.getCertificate().getPublicKey();
        RSAPublicKey server = (RSAPublicKey) serverCert.getPublicKey();

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(unsigned(client.getModulus()));
        sha.update(unsigned(client.getPublicExponent()));
        sha.update(unsigned(server.getModulus()));
        sha.update(unsigned(server.getPublicExponent()));
        sha.update(codeBytes, 1, 2);
        byte[] secret = sha.digest();

        // The first byte of the code is a check value, so typos are caught locally.
        if (secret[0] != codeBytes[0]) {
            throw new IOException("הקוד שגוי. בדוק שהקלדת אותו בדיוק כפי שמופיע על המסך.");
        }

        send(40, new Pb.Writer().bytes(1, secret));
        expect(41);
    }

    private void send(int field, Pb.Writer payload) throws IOException {
        byte[] msg = new Pb.Writer()
                .varint(1, PROTOCOL_VERSION)
                .varint(2, STATUS_OK)
                .msg(field, payload)
                .toBytes();
        Pb.writeFrame(out, msg);
    }

    private void expect(int field) throws IOException {
        Map<Integer, List<Object>> m = Pb.parse(Pb.readFrame(in));
        long status = Pb.firstLong(m, 2, 0);
        if (status != STATUS_OK) {
            throw new IOException("הממיר דחה את הצימוד (קוד " + status + ")");
        }
        if (!m.containsKey(field)) {
            throw new IOException("תשובה לא צפויה מהממיר בזמן הצימוד");
        }
    }

    static byte[] unsigned(BigInteger n) {
        byte[] b = n.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] r = new byte[b.length - 1];
            System.arraycopy(b, 1, r, 0, r.length);
            return r;
        }
        return b;
    }

    static byte[] hex(String s) throws IOException {
        String t = s.trim();
        if (t.length() % 2 != 0) {
            throw new IOException("הקוד חייב להכיל 6 תווים (0-9, A-F)");
        }
        byte[] r = new byte[t.length() / 2];
        for (int i = 0; i < r.length; i++) {
            int hi = Character.digit(t.charAt(2 * i), 16);
            int lo = Character.digit(t.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IOException("הקוד יכול להכיל רק ספרות ואותיות A-F");
            }
            r[i] = (byte) ((hi << 4) | lo);
        }
        return r;
    }

    @Override
    public void close() {
        final SSLSocket s = socket;
        if (s == null) {
            return;
        }
        // Closing a TLS socket must not happen on the UI thread on Android.
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
}
