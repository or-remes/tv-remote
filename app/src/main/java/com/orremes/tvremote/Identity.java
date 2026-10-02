package com.orremes.tvremote;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * The phone's identity towards the TV box: a private key and a self-signed certificate,
 * created once and kept in the app's private storage. Pairing ties the box to this identity.
 */
public final class Identity {
    private final PrivateKey privateKey;
    private final X509Certificate certificate;
    private final SSLContext sslContext;

    public Identity(File dir) throws Exception {
        File keyFile = new File(dir, "client_key.der");
        File certFile = new File(dir, "client_cert.der");
        PrivateKey key = null;
        X509Certificate cert = null;

        if (keyFile.exists() && certFile.exists()) {
            try {
                key = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(readAll(keyFile)));
                InputStream in = new FileInputStream(certFile);
                try {
                    cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                            .generateCertificate(in);
                } finally {
                    in.close();
                }
            } catch (Exception e) {
                key = null;
                cert = null;
            }
        }

        if (key == null || cert == null) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            cert = SelfSigned.create(pair, "atvremote");
            key = pair.getPrivate();
            writeAll(keyFile, key.getEncoded());
            writeAll(certFile, cert.getEncoded());
        }

        this.privateKey = key;
        this.certificate = cert;
        this.sslContext = buildContext();
    }

    public X509Certificate getCertificate() {
        return certificate;
    }

    private SSLContext buildContext() throws Exception {
        final PrivateKey k = privateKey;
        final X509Certificate c = certificate;

        // Always offer our certificate, whatever the box says it accepts.
        X509KeyManager keyManager = new X509KeyManager() {
            @Override public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[] {"client"};
            }
            @Override public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
                return "client";
            }
            @Override public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }
            @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return null;
            }
            @Override public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[] {c};
            }
            @Override public PrivateKey getPrivateKey(String alias) {
                return k;
            }
        };

        // The box uses its own self-signed certificate, so there is nothing to validate it
        // against. Security comes from the pairing code, as in the official remote apps.
        X509TrustManager trustAll = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[] {keyManager}, new TrustManager[] {trustAll}, new SecureRandom());
        return ctx;
    }

    /** Opens a TLS connection with our certificate and finishes the handshake. */
    public SSLSocket connect(String host, int port, int timeoutMs) throws IOException {
        SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.startHandshake();
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing more to do
            }
            throw e;
        }
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    throw new IOException("Unexpected end of file");
                }
                off += n;
            }
            return buf;
        } finally {
            in.close();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
