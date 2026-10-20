package com.chenmodlite.pakeditor.core.io;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Little-endian binary writer. */
public final class BinWriter implements Closeable {

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private final RandomAccessFile raf; // may be null for memory mode
    private final ByteArrayOutputStream mem;
    private long pos;

    private BinWriter(RandomAccessFile raf) {
        this.raf = raf;
        this.mem = null;
    }

    private BinWriter() {
        this.raf = null;
        this.mem = new ByteArrayOutputStream(1 << 16);
    }

    public static BinWriter file(String path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path, "rw");
        raf.setLength(0);
        return new BinWriter(raf);
    }

    public static BinWriter memory() { return new BinWriter(); }

    public long position() { return pos; }

    public void seek(long p) throws IOException {
        if (raf != null) {
            raf.seek(p);
        } else if (p > mem.size()) {
            byte[] pad = new byte[(int) p - mem.size()];
            mem.write(pad, 0, pad.length);
        }
        pos = p;
    }

    private void bump(int n) { pos += n; }

    public void writeByte(int v) throws IOException {
        if (raf != null) raf.write(v & 0xFF);
        else mem.write(v & 0xFF);
        bump(1);
    }

    public void writeU16(int v) throws IOException {
        writeByte(v); writeByte(v >> 8);
    }

    public void writeInt(int v) throws IOException {
        writeByte(v); writeByte(v >> 8); writeByte(v >> 16); writeByte(v >> 24);
    }

    public void writeLong(long v) throws IOException {
        writeInt((int) v); writeInt((int) (v >> 32));
    }

    public void writeFloat(float f) throws IOException { writeInt(Float.floatToRawIntBits(f)); }

    public void writeDouble(double d) throws IOException { writeLong(Double.doubleToRawLongBits(d)); }

    public void writeBytes(byte[] data) throws IOException {
        if (raf != null) raf.write(data);
        else mem.write(data, 0, data.length);
        bump(data.length);
    }

    public void writeBytes(byte[] data, int off, int len) throws IOException {
        if (raf != null) raf.write(data, off, len);
        else mem.write(data, off, len);
        bump(len);
    }

    /** UE FString with null terminator: int32 byte-length (incl. terminator), 8-bit if ASCII. */
    public void writeFString(String s) throws IOException {
        if (s == null || s.isEmpty()) { writeInt(0); return; }
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) { ascii = false; break; }
        }
        if (ascii) {
            int len = s.length() + 1;
            writeInt(len);
            byte[] raw = new byte[len];
            byte[] src = s.getBytes(LATIN1);
            System.arraycopy(src, 0, raw, 0, src.length);
            raw[len - 1] = 0;
            writeBytes(raw);
        } else {
            int chars = s.length() + 1;
            writeInt(-chars);
            byte[] raw = new byte[chars * 2];
            byte[] src = s.getBytes(StandardCharsets.UTF_16LE);
            System.arraycopy(src, 0, raw, 0, src.length);
            writeBytes(raw);
        }
    }

    public void writeZeros(int n) throws IOException {
        byte[] z = new byte[n];
        writeBytes(z);
    }

    public byte[] toByteArray() {
        if (mem == null) throw new IllegalStateException("memory mode only");
        return mem.toByteArray();
    }

    public void flush() throws IOException {
        if (raf != null) raf.getFD().sync();
    }

    @Override
    public void close() throws IOException {
        if (raf != null) raf.close();
        else mem.close();
    }
}
