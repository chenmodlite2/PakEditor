package com.chenmodlite.pakeditor.core.compress;

public final class Oodle {
    static {
        try { System.loadLibrary("ooz"); } catch (Throwable ignored) {}
    }

    private Oodle() {}

    /** Native ooz decoder. Returns decompressed length on success, -1 on failure. */
    public static native int nativeDecompress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstLen);
}
