package com.orremes.tvremote;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny protobuf codec: just enough for the Android TV Remote protocol (varints and
 * length-delimited fields), so the app needs no protobuf library.
 */
final class Pb {
    private Pb() {}

    /** Builds one protobuf message. */
    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private void rawVarint(long value) {
            long v = value;
            while ((v & ~0x7FL) != 0L) {
                out.write((int) ((v & 0x7FL) | 0x80L));
                v >>>= 7;
            }
            out.write((int) v);
        }

        Writer varint(int field, long value) {
            rawVarint(((long) field << 3));
            rawVarint(value);
            return this;
        }

        Writer bytes(int field, byte[] data) {
            rawVarint(((long) field << 3) | 2L);
            rawVarint(data.length);
            out.write(data, 0, data.length);
            return this;
        }

        Writer string(int field, String s) {
            try {
                return bytes(field, s.getBytes("UTF-8"));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }

        Writer msg(int field, Writer inner) {
            return bytes(field, inner.toBytes());
        }

        byte[] toBytes() {
            return out.toByteArray();
        }
    }

    /**
     * Parses one message into field number -> list of values. Varints become Long,
     * length-delimited fields become byte[].
     */
    static Map<Integer, List<Object>> parse(byte[] data) throws IOException {
        Map<Integer, List<Object>> result = new HashMap<Integer, List<Object>>();
        int[] pos = {0};
        while (pos[0] < data.length) {
            long key = readVarint(data, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            Object value;
            switch (wire) {
                case 0:
                    value = Long.valueOf(readVarint(data, pos));
                    break;
                case 1:
                    pos[0] += 8;
                    continue;
                case 5:
                    pos[0] += 4;
                    continue;
                case 2: {
                    long len = readVarint(data, pos);
                    if (len < 0 || pos[0] + len > data.length) {
                        throw new IOException("Bad protobuf length");
                    }
                    byte[] chunk = new byte[(int) len];
                    System.arraycopy(data, pos[0], chunk, 0, (int) len);
                    pos[0] += (int) len;
                    value = chunk;
                    break;
                }
                default:
                    throw new IOException("Unsupported protobuf wire type " + wire);
            }
            List<Object> list = result.get(field);
            if (list == null) {
                list = new ArrayList<Object>();
                result.put(field, list);
            }
            list.add(value);
        }
        return result;
    }

    private static long readVarint(byte[] data, int[] pos) throws IOException {
        long result = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= data.length) {
                throw new EOFException("Truncated varint");
            }
            int b = data[pos[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new IOException("Varint too long");
            }
        }
    }

    /** First varint value of a field, or the default. */
    static long firstLong(Map<Integer, List<Object>> m, int field, long dflt) {
        List<Object> l = m.get(field);
        if (l != null && !l.isEmpty() && l.get(0) instanceof Long) {
            return (Long) l.get(0);
        }
        return dflt;
    }

    /** First length-delimited value of a field, or null. */
    static byte[] firstBytes(Map<Integer, List<Object>> m, int field) {
        List<Object> l = m.get(field);
        if (l != null && !l.isEmpty() && l.get(0) instanceof byte[]) {
            return (byte[]) l.get(0);
        }
        return null;
    }

    /** Writes one message with a varint length prefix. */
    static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int v = payload.length;
        while (v >= 0x80) {
            buf.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf.write(v);
        buf.write(payload, 0, payload.length);
        out.write(buf.toByteArray());
        out.flush();
    }

    /** Reads one message that has a varint length prefix. */
    static byte[] readFrame(InputStream in) throws IOException {
        int len = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Connection closed");
            }
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("Bad frame length");
            }
        }
        if (len < 0 || len > 1000000) {
            throw new IOException("Frame too large: " + len);
        }
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new EOFException("Connection closed");
            }
            off += n;
        }
        return buf;
    }
}
