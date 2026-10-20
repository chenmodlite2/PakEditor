package com.chenmodlite.pakeditor.core.io;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Little-endian binary reader over a RandomAccessFile or an in-memory buffer.
 * Mirrors CUE4Parse FArchive semantics used by the pak/IoStore readers.
 */
public final class BinReader implements Closeable {

    public static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private final RandomAccessFile raf;
    private final byte[] buffer;
    private final long length;
    private long pos;

    private BinReader(RandomAccessFile raf, long length) {
        this.raf = raf;
        this.buffer = null;
        this.length = length;
        this.pos = 0;
    }

    private BinReader(byte[] data, int off, int len) {
        this.raf = null;
        this.buffer = new byte[len];
        System.arraycopy(data, off, this.buffer, 0, len);
        this.length = len;
        this.pos = 0;
    }

    public static BinReader file(String path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path, "r");
        return new BinReader(raf, raf.length());
    }

    public static BinReader memory(byte[] data) {
        return new BinReader(data, 0, data.length);
    }

    public static BinReader memory(byte[] data, int off, int len) {
        return new BinReader(data, off, len);
    }

    public long position() { return pos; }

    public void seek(long p) { pos = p; }

    public void skip(long n) { pos += n; }

    public long length() { return length; }

    private void ensure(long n) throws IOException {
        if (pos + n > length) throw new EOFException("read past end (pos=" + pos + " +" + n + " len=" + length + ")");
    }

    public int readByte() throws IOException {
        ensure(1);
        int b;
        if (raf != null) { raf.seek(pos); b = raf.read() & 0xFF; }
        else b = buffer[(int) pos] & 0xFF;
        pos++;
        return b;
    }

    public boolean readBoolean() throws IOException { return readByte() != 0; }

    public int readU16() throws IOException {
        ensure(2);
        int b0, b1;
        if (raf != null) {
            raf.seek(pos);
            byte[] tmp = new byte[2];
            raf.readFully(tmp);
            b0 = tmp[0] & 0xFF; b1 = tmp[1] & 0xFF;
        } else {
            b0 = buffer[(int) pos] & 0xFF; b1 = buffer[(int) pos + 1] & 0xFF;
        }
        pos += 2;
        return b0 | (b1 << 8);
    }

    public int readInt() throws IOException {
        ensure(4);
        int b0, b1, b2, b3;
        if (raf != null) {
            raf.seek(pos);
            byte[] tmp = new byte[4];
            raf.readFully(tmp);
            b0 = tmp[0] & 0xFF; b1 = tmp[1] & 0xFF; b2 = tmp[2] & 0xFF; b3 = tmp[3] & 0xFF;
        } else {
            int p = (int) pos;
            b0 = buffer[p] & 0xFF; b1 = buffer[p + 1] & 0xFF; b2 = buffer[p + 2] & 0xFF; b3 = buffer[p + 3] & 0xFF;
        }
        pos += 4;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    public long readLong() throws IOException {
        long lo = readInt() & 0xFFFFFFFFL;
        long hi = readInt();
        return lo | (hi << 32);
    }

    public float readFloat() throws IOException { return Float.intBitsToFloat(readInt()); }

    public double readDouble() throws IOException { return Double.longBitsToDouble(readLong()); }

    public byte[] readBytes(int n) throws IOException {
        if (n < 0) throw new IOException("negative read " + n);
        ensure(n);
        byte[] out = new byte[n];
        if (raf != null) {
            raf.seek(pos);
            raf.readFully(out);
        } else {
            System.arraycopy(buffer, (int) pos, out, 0, n);
        }
        pos += n;
        return out;
    }

    public void readInto(byte[] dst, int off, int n) throws IOException {
        ensure(n);
        if (raf != null) {
            raf.seek(pos);
            raf.readFully(dst, off, n);
        } else {
            System.arraycopy(buffer, (int) pos, dst, off, n);
        }
        pos += n;
    }

    /** UE FString: int32 length; >0 = length bytes 8-bit, <0 = -length UTF-16LE chars. Null-terminated. */
    public String readFString() throws IOException {
        int len = readInt();
        if (len == 0) return "";
        if (len > 0) {
            byte[] raw = readBytes(len);
            int end = raw.length;
            while (end > 0 && raw[end - 1] == 0) end--;
            return new String(raw, 0, end, LATIN1);
        } else {
            int chars = -len;
            byte[] raw = readBytes(chars * 2);
            int end = chars;
            while (end > 0 && raw[end * 2 - 1] == 0 && raw[end * 2 - 2] == 0) end--;
            return new String(raw, 0, end * 2, StandardCharsets.UTF_16LE);
        }
    }

    /** Peek a UTF-16LE/ASCII string starting at absolute offset without moving. */
    public String peekFString(long at) throws IOException {
        long saved = pos;
        seek(at);
        String s = readFString();
        seek(saved);
        return s;
    }

    @Override
    public void close() throws IOException {
        if (raf != null) raf.close();
    }
}
