package com.chenmodlite.pakeditor.core.pak;

import com.chenmodlite.pakeditor.core.compress.Decompress;
import com.chenmodlite.pakeditor.core.crypto.AesUtil;
import com.chenmodlite.pakeditor.core.io.BinWriter;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pak writer: emits a UE4.22-style pak (version 8, legacy index, 4 method slots:
 * Zlib/Gzip/Oodle/LZ4). Readable by UE4.22+ and by CUE4Parse/FModel. Files are
 * stored uncompressed by default; zlib optional per call.
 */
public final class PakWriter {

    public static final int ENTRY_ALIGN = 8;

    public static final class PendingFile {
        public final String path;         // full virtual path including mount point
        public final byte[] data;
        public final boolean compress;

        public PendingFile(String path, byte[] data, boolean compress) {
            this.path = path;
            this.data = data;
            this.compress = compress;
        }
    }

    private final String mountPoint;
    private final List<PendingFile> files = new ArrayList<>();

    public PakWriter(String mountPoint) {
        this.mountPoint = normalizeMount(mountPoint);
    }

    private static String normalizeMount(String m) {
        if (m == null || m.isEmpty()) return "../";
        if (!m.endsWith("/")) m += "/";
        return m;
    }

    public void add(String fullPath, byte[] data, boolean compress) {
        files.add(new PendingFile(fullPath, data, compress));
    }

    public int count() { return files.size(); }

    /** Write the pak to disk. Returns bytes written. */
    public long write(String outPath) throws IOException {
        if (files.isEmpty()) throw new IOException("nothing to pack");

        try (BinWriter w = BinWriter.file(outPath)) {
            // pass 1: lay out file data, remember entry positions
            long[] entryOffsets = new long[files.size()];
            byte[][] entryStructs = new byte[files.size()][];
            int[] methodIndices = new int[files.size()];

            long pos = 0;
            for (int i = 0; i < files.size(); i++) {
                PendingFile f = files.get(i);
                byte[] stored;
                int methodIndex = 0;
                long blockSize = 0;
                long[][] relBlocks = null;

                if (f.compress) {
                    byte[] z = Decompress.zlibCompress(f.data, 0, f.data.length);
                    if (z.length + 64 < f.data.length) { // only when it actually pays
                        stored = z;
                        methodIndex = 1; // Zlib slot
                        blockSize = f.data.length;
                        // block offsets are relative to the entry offset and include
                        // the prepended entry header itself
                        long headerSize = 50 + 4 + 16; // base struct + block count + 1 block
                        relBlocks = new long[][]{{headerSize, headerSize + z.length}};
                    } else {
                        stored = f.data;
                    }
                } else {
                    stored = f.data;
                }

                entryOffsets[i] = pos;
                byte[] header = buildEntryHeader(pos, stored.length, f.data.length, methodIndex, blockSize, relBlocks);
                entryStructs[i] = header;
                methodIndices[i] = methodIndex;

                w.seek(pos);
                w.writeBytes(header);
                w.writeBytes(stored);
                pos = w.position();
                long aligned = PakEntry.alignUp(pos, ENTRY_ALIGN);
                if (aligned != pos) {
                    w.writeZeros((int) (aligned - pos));
                    pos = aligned;
                }
            }

            // pass 2: index
            long indexOffset = pos;
            w.seek(indexOffset);
            BinWriter idx = BinWriter.memory();
            idx.writeFString(mountPoint);
            idx.writeInt(files.size());
            for (int i = 0; i < files.size(); i++) {
                PendingFile f = files.get(i);
                String rel = relativePath(f.path);
                idx.writeFString(rel);
                idx.writeBytes(entryStructs[i]);
            }
            byte[] indexBytes = idx.toByteArray();
            w.writeBytes(indexBytes);
            long tailOffset = w.position();

            // pass 3: footer (Size8 layout: 61 + 4*32)
            w.writeZeros(16);                       // EncryptionKeyGuid
            w.writeByte(0);                         // EncryptedIndex = false
            w.writeInt(PakInfo.PAK_FILE_MAGIC);
            w.writeInt(PakInfo.VER_FNAME_BASED_COMPRESSION); // version 8
            w.writeLong(indexOffset);
            w.writeLong(indexBytes.length);
            w.writeBytes(sha1(indexBytes));
            writeMethodName(w, "Zlib");
            writeMethodName(w, "Gzip");
            writeMethodName(w, "Oodle");
            writeMethodName(w, "LZ4");
            return w.position();
        }
    }

    private String relativePath(String fullPath) {
        if (fullPath.startsWith(mountPoint)) return fullPath.substring(mountPoint.length());
        // tolerate a leading "../" style mount mismatch
        if (fullPath.startsWith("/") && mountPoint.startsWith("/")) return fullPath.substring(1);
        return fullPath;
    }

    private static byte[] buildEntryHeader(long offset, long compressedSize, long uncompressedSize,
                                           int methodIndex, long blockSize, long[][] relBlocks) throws IOException {
        BinWriter h = BinWriter.memory();
        h.writeLong(offset);
        h.writeLong(compressedSize);
        h.writeLong(uncompressedSize);
        h.writeByte(methodIndex);          // v8 reads a byte method index
        h.writeZeros(20);                  // hash
        if (methodIndex != 0) {
            h.writeInt(relBlocks.length);
            for (long[] b : relBlocks) {
                h.writeLong(b[0]);
                h.writeLong(b[1]);
            }
        }
        h.writeByte(0);                    // flags: not encrypted, not deleted
        h.writeInt((int) blockSize);       // CompressionBlockSize (uint32)
        return h.toByteArray();
    }

    private static void writeMethodName(BinWriter w, String name) throws IOException {
        byte[] slot = new byte[32];
        byte[] src = name.getBytes(com.chenmodlite.pakeditor.core.io.BinReader.LATIN1);
        System.arraycopy(src, 0, slot, 0, Math.min(32, src.length));
        w.writeBytes(slot);
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            return new byte[20];
        }
    }

    /** Pack a legacy entry for callers that need its serialized form (replace flow). */
    public static byte[] legacyEntryHeader(long offset, long compressedSize, long uncompressedSize,
                                           int methodIndex, long blockSize, long[][] relBlocks,
                                           int flags) throws IOException {
        return buildEntryHeader(offset, compressedSize, uncompressedSize, methodIndex, blockSize, relBlocks);
    }
}
