package com.orremes.tvremote;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Diagnostic only: checks whether a Hisense (VIDAA) TV accepts a connection on its
 * local control port without the official app's certificate, and reports what it said.
 */
public final class TvProbe {
    private static final int PORT = 36669;

    private TvProbe() {}

    /** Looks for a device with port 36669 open on the phone's own /24 network. */
    public static List<String> scan() {
        final List<String> found = Collections.synchronizedList(new ArrayList<String>());
        String base = localBase();
        if (base == null) {
            return found;
        }
        ExecutorService pool = Executors.newFixedThreadPool(64);
        for (int i = 1; i < 255; i++) {
            final String ip = base + i;
            pool.execute(new Runnable() {
                @Override public void run() {
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(ip, PORT), 600);
                        found.add(ip);
                    } catch (IOException ignored) {
                        // closed
                    } finally {
                        try { s.close(); } catch (IOException ignored) { }
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(20, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            // give up waiting
        }
        return found;
    }

    private static String localBase() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface n = ifs.nextElement();
                Enumeration<InetAddress> addrs = n.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String h = a.getHostAddress();
                        return h.substring(0, h.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (Exception ignored) {
            // no network
        }
        return null;
    }

    public static String run(String host, Identity identity) {
        StringBuilder r = new StringBuilder();
        r.append("טלוויזיה: ").append(host).append("\n\n");

        // 1. Is the port open at all?
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, PORT), 3000);
            s.close();
            r.append("1. פורט ").append(PORT).append(": פתוח\n");
        } catch (IOException e) {
            r.append("1. פורט ").append(PORT).append(": סגור או לא עונה (")
                    .append(e.getClass().getSimpleName()).append(")\n");
            r.append("   אם הטלוויזיה כבויה, זה צפוי.\n");
            return r.toString();
        }

        // 2. Without any certificate.
        r.append("\n2. חיבור בלי תעודה:\n");
        attempt(host, null, r);

        // 3. With our own certificate.
        r.append("\n3. חיבור עם התעודה של האפליקציה שלנו:\n");
        attempt(host, identity, r);
        return r.toString();
    }

    private static void attempt(String host, Identity identity, StringBuilder r) {
        SSLSocket s = null;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            TrustManager trustAll = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            if (identity == null) {
                ctx.init(null, new TrustManager[] {trustAll}, new SecureRandom());
                s = (SSLSocket) ctx.getSocketFactory().createSocket();
                s.connect(new InetSocketAddress(host, PORT), 4000);
                s.setSoTimeout(5000);
                s.startHandshake();
            } else {
                s = identity.connect(host, PORT, 5000);
                s.setSoTimeout(5000);
            }
            r.append("   TLS: הצליח (").append(s.getSession().getProtocol()).append(")\n");

            String clientId = "probe$his$000000_vidaacommon_001";
            String[][] creds = {
                {"hisenseservice", "multimqttservice"},
                {"", ""},
            };
            // Each attempt needs a fresh connection, so only try the first on this socket.
            byte[] pkt = connectPacket(clientId, creds[0][0], creds[0][1]);
            OutputStream out = s.getOutputStream();
            out.write(pkt);
            out.flush();
            InputStream in = s.getInputStream();
            DataInputStream din = new DataInputStream(in);
            int type = din.readUnsignedByte();
            int len = din.readUnsignedByte();
            byte[] body = new byte[len];
            din.readFully(body);
            if ((type >> 4) == 2 && len == 2) {
                int rc = body[1] & 0xff;
                r.append("   MQTT CONNACK קוד: ").append(rc).append(rc == 0
                        ? " (התקבלנו!)\n" : " (נדחינו)\n");
            } else {
                r.append("   תשובה לא צפויה, סוג ").append(type).append("\n");
            }
        } catch (IOException e) {
            r.append("   נכשל: ").append(e.getClass().getSimpleName()).append(": ")
                    .append(e.getMessage()).append("\n");
        } catch (Exception e) {
            r.append("   שגיאה: ").append(e).append("\n");
        } finally {
            if (s != null) {
                final SSLSocket c = s;
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        try { c.close(); } catch (Exception ignored) { }
                    }
                });
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private static byte[] connectPacket(String clientId, String user, String pass)
            throws IOException {
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        str(v, "MQTT");
        v.write(4);
        v.write(0xC2);
        v.write(0);
        v.write(60);
        str(v, clientId);
        str(v, user);
        str(v, pass);
        byte[] body = v.toByteArray();
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(0x10);
        int n = body.length;
        do {
            int b = n % 128;
            n /= 128;
            p.write(n > 0 ? b | 128 : b);
        } while (n > 0);
        p.write(body);
        return p.toByteArray();
    }

    private static void str(ByteArrayOutputStream o, String s) throws IOException {
        byte[] b = s.getBytes("UTF-8");
        o.write(b.length >> 8);
        o.write(b.length & 0xff);
        o.write(b);
    }
}
