package com.chenmodlite.pakeditor.core.compress;

import android.util.Log;

/**
 * Wires the native ooz decoder ({@link Oodle#nativeDecompress}) into
 * {@link Decompress}'s Oodle hook.
 */
public final class OodleLoader {
    private static final String TAG = "OodleLoader";
    private static volatile boolean installed;

    private OodleLoader() {}

    public static synchronized void install() {
        if (installed) return;
        try {
            Class.forName("com.chenmodlite.pakeditor.core.compress.Oodle");
            Decompress.setOodleHook((src, srcOff, srcLen, dst, dstLen) -> {
                int ret = Oodle.nativeDecompress(src, srcOff, srcLen, dst, dstLen);
                if (ret <= 0) return false;
                if (ret != dstLen) {
                    Log.w(TAG, "oodle: expected " + dstLen + " bytes, got " + ret);
                    return false;
                }
                return true;
            });
            installed = true;
            Log.i(TAG, "native Oodle (ooz) decoder attached");
        } catch (Throwable t) {
            Log.w(TAG, "failed to attach native Oodle decoder", t);
        }
    }

    public static boolean isInstalled() { return installed; }
}
