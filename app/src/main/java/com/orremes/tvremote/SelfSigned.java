package com.orremes.tvremote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds a minimal self-signed X.509 certificate by hand (DER), so no crypto library is
 * needed. The TV only uses this certificate to recognise the phone after pairing.
 */
final class SelfSigned {
    private SelfSigned() {}

    // OID 1.2.840.113549.1.1.11 (sha256WithRSAEncryption)
    private static final byte[] OID_SHA256_RSA = {
        0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B
    };
    // OID 2.5.4.3 (commonName)
    private static final byte[] OID_CN = {0x06, 0x03, 0x55, 0x04, 0x03};

    static X509Certificate create(KeyPair kp, String commonName) throws Exception {
        byte[] sigAlg = seq(OID_SHA256_RSA, new byte[] {0x05, 0x00});
        byte[] name = seq(set(seq(OID_CN, tlv(0x0C, commonName.getBytes("UTF-8")))));

        long now = System.currentTimeMillis();
        byte[] validity = seq(
                utcTime(now - 24L * 3600 * 1000),
                utcTime(now + 20L * 365 * 24 * 3600 * 1000));

        byte[] serialBytes = new byte[8];
        new SecureRandom().nextBytes(serialBytes);
        serialBytes[0] = (byte) ((serialBytes[0] & 0x7F) | 0x01); // positive and non-zero
        byte[] serial = tlv(0x02, serialBytes);

        byte[] version = tlv(0xA0, tlv(0x02, new byte[] {0x02})); // v3

        byte[] tbs = seq(version, serial, sigAlg, name, validity, name, kp.getPublic().getEncoded());

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(kp.getPrivate());
        signer.update(tbs);
        byte[] sig = signer.sign();

        byte[] bits = new byte[sig.length + 1]; // leading 0 = no unused bits
        System.arraycopy(sig, 0, bits, 1, sig.length);

        byte[] cert = seq(tbs, sigAlg, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert));
    }

    private static byte[] utcTime(long millis) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(new Date(millis)).getBytes("US-ASCII"));
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else if (len < 0x10000) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }
}
