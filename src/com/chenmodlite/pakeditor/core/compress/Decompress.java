package com.chenmodlite.pakeditor.core.compress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Decompression dispatcher for UE compression method names found in pak/IoStore indexes. */
public final class Decompress {

    public static final int METHOD_NONE = 0;
    public static final int METHOD_ZLIB = 1;
    public static final int METHOD_GZIP = 2;
    public static final int METHOD_CUSTOM = 3;
    public static final int METHOD_OODLE = 4;
    public static final int METHOD_LZ4 = 5;
    public static final int METHOD_LZO = 6;
    public static final int METHOD_ZSTD = 7;

    public interface OodleHook {
        /** Returns true when the native oodle decompressor handled the request. */
        boolean decompress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstLen);
    }

    private static volatile OodleHook oodleHook;

    private Decompress() {}

    public static void setOodleHook(OodleHook hook) { oodleHook = hook; }

    public static boolean hasOodle() { return oodleHook != null; }

    /** Map a compression-method name from a pak info block to a method id. */
    public static int idFromName(String name) {
        if (name == null || name.isEmpty()) return METHOD_NONE;
        switch (name.toLowerCase()) {
            case "zlib": return METHOD_ZLIB;
            case "gzip": return METHOD_GZIP;
            case "oodle": return METHOD_OODLE;
            case "lz4": return METHOD_LZ4;
            case "lzo": return METHOD_LZO;
            case "zstd": return METHOD_ZSTD;
            default: return -1; // unknown
        }
    }

    public static byte[] decompress(int method, byte[] src, int srcOff, int srcLen, int dstLen) {
        byte[] dst = new byte[dstLen];
        decompressInto(method, src, srcOff, srcLen, dst, dstLen);
        return dst;
    }

    public static void decompressInto(int method, byte[] src, int srcOff, int srcLen, byte[] dst, int dstLen) {
        switch (method) {
            case METHOD_NONE:
                if (srcLen != dstLen) throw new RuntimeException("none: size mismatch");
                System.arraycopy(src, srcOff, dst, 0, dstLen);
                return;
            case METHOD_ZLIB:
                inflate(src, srcOff, srcLen, dst, dstLen, false);
                return;
            case METHOD_GZIP:
                gzip(src, srcOff, srcLen, dst, dstLen);
                return;
            case METHOD_LZ4: {
                byte[] out = Lz4.decompress(src, srcOff, srcLen, dstLen);
                System.arraycopy(out, 0, dst, 0, dstLen);
                return;
            }
            case METHOD_OODLE: {
                OodleHook hook = oodleHook;
                if (hook != null && hook.decompress(src, srcOff, srcLen, dst, dstLen)) return;
                throw new UnsupportedCompressionException("Oodle",
                        "this build has no native Oodle decompressor attached");
                // Native ooz loader attaches via setOodleHook at runtime when the .so is present.
            }
            case METHOD_ZSTD:
                throw new UnsupportedCompressionException("Zstd",
                        "zstd pak blocks are rare; no native zstd attached to this build");
            default:
                throw new UnsupportedCompressionException("method#" + method, "unknown compression method");
        }
    }

    private static void inflate(byte[] src, int off, int len, byte[] dst, int dstLen, boolean nowrap) {
        try {
            Inflater inf = new Inflater(nowrap);
            inf.setInput(src, off, len);
            int written = 0;
            while (written < dstLen && !inf.finished()) {
                int n = inf.inflate(dst, written, dstLen - written);
                if (n == 0) {
                    if (inf.needsInput()) throw new IOException("zlib: truncated input");
                    if (inf.needsDictionary()) throw new IOException("zlib: dictionary needed");
                }
                written += n;
            }
            if (written != dstLen) throw new IOException("zlib: got " + written + " expected " + dstLen);
            inf.end();
        } catch (IOException | java.util.zip.DataFormatException e) {
            StringBuilder head = new StringBuilder();
            for (int i = 0; i < Math.min(8, len); i++) head.append(String.format("%02x ", src[off + i]));
            throw new RuntimeException("zlib decompress failed: " + e.getMessage()
                    + " [off=" + off + " len=" + len + " head=" + head + "]", e);
        }
    }

    private static void gzip(byte[] src, int off, int len, byte[] dst, int dstLen) {
        try {
            InputStream in = new GZIPInputStream(new ByteArrayInputStream(src, off, len));
            int written = 0;
            while (written < dstLen) {
                int n = in.read(dst, written, dstLen - written);
                if (n < 0) break;
                written += n;
            }
            in.close();
            if (written != dstLen) throw new IOException("gzip: got " + written + " expected " + dstLen);
        } catch (IOException e) {
            throw new RuntimeException("gzip decompress failed: " + e.getMessage(), e);
        }
    }

    /** Compress with zlib wrapper (for repacking). */
    public static byte[] zlibCompress(byte[] src, int off, int len) {
        java.util.zip.Deflater def = new java.util.zip.Deflater(6, false);
        def.setInput(src, off, len);
        def.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(len / 2 + 16);
        byte[] buf = new byte[64 * 1024];
        while (!def.finished()) {
            int n = def.deflate(buf);
            bos.write(buf, 0, n);
        }
        def.end();
        return bos.toByteArray();
    }

    public static class UnsupportedCompressionException extends RuntimeException {
        public final String method;
        public UnsupportedCompressionException(String method, String why) {
            super("unsupported compression: " + method + " (" + why + ")");
            this.method = method;
        }
    }
}
