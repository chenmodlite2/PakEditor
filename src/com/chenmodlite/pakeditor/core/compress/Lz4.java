package com.chenmodlite.pakeditor.core.compress;

/** LZ4 block (not frame) decompressor — matches what UE pak entries store. */
public final class Lz4 {

    private Lz4() {}

    public static byte[] decompress(byte[] src, int srcOff, int srcLen, int dstLen) {
        byte[] dst = new byte[dstLen];
        int si = srcOff, sEnd = srcOff + srcLen, di = 0;
        while (si < sEnd) {
            int token = src[si++] & 0xFF;
            int litLen = token >>> 4;
            if (litLen == 15) {
                int b;
                do {
                    if (si >= sEnd) throw new RuntimeException("lz4: truncated literal length");
                    b = src[si++] & 0xFF;
                    litLen += b;
                } while (b == 255);
            }
            if (si + litLen > sEnd || di + litLen > dstLen) throw new RuntimeException("lz4: bad literal run");
            System.arraycopy(src, si, dst, di, litLen);
            si += litLen;
            di += litLen;
            if (si >= sEnd) break; // last sequence has no match

            if (si + 2 > sEnd) throw new RuntimeException("lz4: truncated offset");
            int offset = (src[si] & 0xFF) | ((src[si + 1] & 0xFF) << 8);
            si += 2;
            if (offset == 0 || offset > di) throw new RuntimeException("lz4: bad offset " + offset);

            int matchLen = token & 0x0F;
            if (matchLen == 15) {
                int b;
                do {
                    if (si >= sEnd) throw new RuntimeException("lz4: truncated match length");
                    b = src[si++] & 0xFF;
                    matchLen += b;
                } while (b == 255);
            }
            matchLen += 4;
            if (di + matchLen > dstLen) throw new RuntimeException("lz4: match overruns output");
            int m = di - offset; // byte-by-byte: overlapping copy is intended
            for (int i = 0; i < matchLen; i++) dst[di + i] = dst[m + i];
            di += matchLen;
        }
        if (di != dstLen) throw new RuntimeException("lz4: produced " + di + " expected " + dstLen);
        return dst;
    }
}
