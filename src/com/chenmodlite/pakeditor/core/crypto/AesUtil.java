package com.chenmodlite.pakeditor.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * UE AES-256: raw block ECB, NoPadding (matches FAES::DecryptData used by
 * CUE4Parse and retoc — no IV, no chaining). Data length must be 16-aligned;
 * UE guarantees this via the pak 16-byte alignment of encrypted regions.
 */
public final class AesUtil {

    public static final int ALIGN = 16;

    private AesUtil() {}

    /** Parse a 64-char hex key. Returns null when input is not a valid key. */
    public static byte[] parseKey(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        if (h.length() != 64) return null;
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            int hi = Character.digit(h.charAt(i * 2), 16);
            int lo = Character.digit(h.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** In-place decrypt of data[off, off+len). len must be a multiple of 16. */
    public static void decryptInPlace(byte[] data, int off, int len, byte[] key) {
        if (key == null) throw new IllegalArgumentException("null aes key");
        if (len % ALIGN != 0) throw new IllegalArgumentException("aes length not 16-aligned: " + len);
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            byte[] out = c.doFinal(data, off, len);
            System.arraycopy(out, 0, data, off, len);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("aes decrypt failed", e);
        }
    }

    public static byte[] encrypt(byte[] data, int off, int len, byte[] key) {
        if (key == null) throw new IllegalArgumentException("null aes key");
        if (len % ALIGN != 0) throw new IllegalArgumentException("aes length not 16-aligned: " + len);
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c.doFinal(data, off, len);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("aes encrypt failed", e);
        }
    }

    public static byte[] decryptCopy(byte[] data, int off, int len, byte[] key) {
        byte[] out = new byte[len];
        System.arraycopy(data, off, out, 0, len);
        decryptInPlace(out, 0, len, key);
        return out;
    }

    /** Align up. */
    public static int align(int v, int a) { return (v + a - 1) / a * a; }
}
