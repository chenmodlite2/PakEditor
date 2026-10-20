package com.chenmodlite.pakeditor.core.pak;

import com.chenmodlite.pakeditor.core.io.BinReader;

import java.io.IOException;

/** Port of CUE4Parse FPakEntry: legacy index form, encoded (v10+) form, and in-file header form. */
public final class PakEntry {

    public static final int FLAG_ENCRYPTED = 0x01;
    public static final int FLAG_DELETED = 0x02;

    public String path = "";
    public long offset;
    public long compressedSize;
    public long uncompressedSize;
    public int compressionMethod = PakInfo.DecompressIds.NONE;
    public int flags;
    public long compressionBlockSize;
    public long[] blockStarts;   // absolute file offsets
    public long[] blockEnds;     // absolute, exclusive
    public int structSize;       // bytes of header prepended to the stored file data

    public boolean isEncrypted() { return (flags & FLAG_ENCRYPTED) != 0; }
    public boolean isDeleted() { return (flags & FLAG_DELETED) != 0; }
    public boolean isCompressed() { return uncompressedSize != compressedSize && compressionBlockSize > 0; }
    public int blockCount() { return blockStarts == null ? 0 : blockStarts.length; }

    /** Legacy (v1..v9) serialized entry. */
    public static PakEntry readLegacy(PakInfo info, BinReader ar, String path) throws IOException {
        PakEntry e = new PakEntry();
        e.path = path;
        long start = ar.position();

        e.offset = ar.readLong();
        e.compressedSize = ar.readLong();
        e.uncompressedSize = ar.readLong();

        if (info.version < PakInfo.VER_FNAME_BASED_COMPRESSION) {
            int legacy = ar.readInt(); // ECompressionFlags
            int idx;
            if (legacy == 0) idx = 0;
            else if ((legacy & 0x02) != 0) idx = 1;        // ZLIB
            else if ((legacy & 0x04) != 0) idx = 2;        // GZIP
            else if ((legacy & 0x10) != 0) idx = 3;        // Custom -> Oodle
            else idx = -1;
            e.compressionMethod = idx < 0 ? PakInfo.DecompressIds.UNKNOWN : info.methodAt(idx);
        } else if (info.version == PakInfo.VER_FNAME_BASED_COMPRESSION && !info.isSubVersion) {
            e.compressionMethod = info.methodAt(ar.readByte());
        } else {
            e.compressionMethod = info.methodAt(ar.readInt());
        }

        if (info.version < PakInfo.VER_NO_TIMESTAMPS) ar.skip(8); // timestamp
        ar.skip(20); // hash

        if (info.version >= PakInfo.VER_COMPRESSION_ENCRYPTION) {
            if (e.compressionMethod != PakInfo.DecompressIds.NONE) {
                int count = ar.readInt();
                if (count < 0 || count > 1 << 20) throw new IOException("bad block count " + count + " in " + path);
                e.blockStarts = new long[count];
                e.blockEnds = new long[count];
                for (int i = 0; i < count; i++) {
                    e.blockStarts[i] = ar.readLong();
                    e.blockEnds[i] = ar.readLong();
                }
            }
            e.flags = ar.readByte();
            e.compressionBlockSize = ar.readInt() & 0xFFFFFFFFL;
        }

        if (info.version >= PakInfo.VER_RELATIVE_CHUNK_OFFSETS && e.blockStarts != null) {
            for (int i = 0; i < e.blockStarts.length; i++) {
                e.blockStarts[i] += e.offset;
                e.blockEnds[i] += e.offset;
            }
        }

        e.structSize = (int) (ar.position() - start);
        return e;
    }

    /** Encoded entry (v10+ path-hash index) — port of FPakFile::DecodePakEntry. */
    public static PakEntry readEncoded(PakInfo info, BinReader ar, int dataOffset, String path) throws IOException {
        ar.seek(dataOffset);
        PakEntry e = new PakEntry();
        e.path = path;

        long bitfield = ar.readInt() & 0xFFFFFFFFL;

        int compressionBlockSize = (bitfield & 0x3f) == 0x3f ? (int) ar.readInt() : (int) ((bitfield & 0x3f) << 11);
        e.compressionMethod = info.methodAt((int) ((bitfield >> 23) & 0x3f));

        boolean offset32 = (bitfield & (1L << 31)) != 0;
        e.offset = offset32 ? (ar.readInt() & 0xFFFFFFFFL) : ar.readLong();

        boolean size32 = (bitfield & (1L << 30)) != 0;
        e.uncompressedSize = size32 ? (ar.readInt() & 0xFFFFFFFFL) : ar.readLong();

        if (e.compressionMethod != PakInfo.DecompressIds.NONE) {
            boolean csz32 = (bitfield & (1L << 29)) != 0;
            e.compressedSize = csz32 ? (ar.readInt() & 0xFFFFFFFFL) : ar.readLong();
        } else {
            e.compressedSize = e.uncompressedSize;
        }

        if ((bitfield & (1L << 22)) != 0) e.flags |= FLAG_ENCRYPTED;

        int blocksCount = (int) ((bitfield >> 6) & 0xffff);
        e.structSize = 8 * 3 + 4 * 2 + 1 + 20; // 3 longs, 2 ints, flags byte, hash
        if (e.compressionMethod != PakInfo.DecompressIds.NONE) {
            e.structSize += 4 + blocksCount * 2 * 8;
        }
        e.compressionBlockSize = blocksCount == 1 ? e.uncompressedSize : (blocksCount > 0 ? compressionBlockSize : 0);

        long blockOffset = e.offset + e.structSize;
        if (blocksCount == 1 && !e.isEncrypted()) {
            e.blockStarts = new long[]{blockOffset};
            e.blockEnds = new long[]{blockOffset + e.compressedSize};
        } else if (blocksCount > 0) {
            int align = e.isEncrypted() ? 16 : 1;
            e.blockStarts = new long[blocksCount];
            e.blockEnds = new long[blocksCount];
            for (int i = 0; i < blocksCount; i++) {
                int len = ar.readInt();
                e.blockStarts[i] = blockOffset;
                e.blockEnds[i] = blockOffset + len;
                blockOffset += alignUp(len, align);
            }
        }
        return e;
    }

    public String extension() {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
    }

    public String fileName() {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    public static int alignUp(int v, int a) { return (v + a - 1) / a * a; }
    public static long alignUp(long v, long a) { return (v + a - 1) / a * a; }
}
