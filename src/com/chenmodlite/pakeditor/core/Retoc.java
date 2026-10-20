package com.chenmodlite.pakeditor.core;

public final class Retoc {
    static {
        try { System.loadLibrary("retoc_jni"); } catch (Throwable ignored) {}
    }

    private Retoc() {}

    /**
     * Run a retoc action. Actions: "to-legacy", "to-zen", "unpack", "info".
     * Returns 0 on success, non-zero on failure.
     */
    public static native int nativeRun(String action, String input, String output, String aesKey, String engineVersion);

    /** Captured output from the most recent nativeRun call. */
    public static native String nativeLog();
}
