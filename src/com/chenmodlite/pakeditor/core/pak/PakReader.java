package com.chenmodlite.pakeditor.core.pak;

import com.chenmodlite.pakeditor.core.compress.Decompress;
import com.chenmodlite.pakeditor.core.crypto.AesUtil;
import com.chenmodlite.pakeditor.core.io.BinReader;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pak reader: mounts legacy (v1..v9) and path-hash (v10+) indexes, extracts
 * plain, compressed and AES-encrypted entries. Port of CUE4Parse PakFileReader
 * for stock (non game-obfuscated) containers.
 */
public final class PakReader {

    public final String path;
    public final PakInfo info;
    public final String mountPoint;

    private final BinReader ar;
    private final byte[] aesKey;
    private final Map<String, PakEntry> files; // ordered insertion by directory walk

    public interface Progress {
        void onProgress(int done, int total, String currentPath);
        boolean isCancelled();
    }

    private PakReader(String path, BinReader ar, PakInfo info, String mountPoint, Map<String, PakEntry> files, byte[] aesKey) {
        this.path = path;
        this.ar = ar;
        this.info = info;
        this.mountPoint = mountPoint;
        this.files = files;
        this.aesKey = aesKey;
    }

    public static PakReader open(String path, byte[] aesKey) throws IOException {
        BinReader ar = BinReader.file(path);
        try {
            PakInfo info = PakInfo.read(ar);
            String mount;
            Map<String, PakEntry> files = new LinkedHashMap<>();

            if (info.version >= PakInfo.VER_PATH_HASH_INDEX && !usesLegacyIndex(info)) {
                Object[] res = readIndexUpdated(ar, info, aesKey);
                mount = (String) res[0];
                @SuppressWarnings("unchecked")
                Map<String, PakEntry> f = (Map<String, PakEntry>) res[1];
                files = f;
            } else if (info.indexIsFrozen) {
                throw new IOException("frozen index paks are not supported by this build");
            } else {
                Object[] res = readIndexLegacy(ar, info, aesKey);
                mount = (String) res[0];
                @SuppressWarnings("unchecked")
                Map<String, PakEntry> f = (Map<String, PakEntry>) res[1];
                files = f;
            }
            return new PakReader(path, ar, info, mount, files, aesKey);
        } catch (IOException | RuntimeException e) {
            ar.close();
            throw e;
        }
    }

    private static boolean usesLegacyIndex(PakInfo info) {
        return info.version < PakInfo.VER_PATH_HASH_INDEX;
    }

    public List<PakEntry> entries() {
        List<PakEntry> out = new ArrayList<>(files.values());
        Collections.sort(out, Comparator.comparing((PakEntry e) -> e.path));
        return out;
    }

    public int fileCount() { return files.size(); }

    public PakEntry entry(String path) { return files.get(path); }

    public void close() throws IOException { ar.close(); }

    // ------------------------------------------------------------------ index

    private static byte[] readAndDecryptIndex(BinReader ar, PakInfo info, byte[] key, long offset, long size) throws IOException {
        ar.seek(offset);
        int read = (int) size;
        byte[] data = ar.readBytes(read);
        if (info.encryptedIndex) {
            if (key == null) throw new IOException("index is encrypted — set an AES key");
            int aligned = AesUtil.align(read, AesUtil.ALIGN);
            if (aligned != read) {
                ar.seek(offset);
                data = ar.readBytes(aligned);
            }
            AesUtil.decryptInPlace(data, 0, data.length, key);
        }
        return data;
    }

    private static String validateMountPoint(String mount) throws IOException {
        // A wrong AES key decrypts to garbage that still parses as "a string".
        // Real mount points always look like "../", "./" or "/Game/...".
        if (mount == null || mount.isEmpty()) throw new IOException("empty mount point — wrong AES key?");
        if (!(mount.startsWith("../") || mount.startsWith("./") || mount.startsWith("/") || mount.startsWith("..\\") || mount.startsWith(".\\")))
            throw new IOException("garbage mount point \"" + mount + "\" — wrong AES key?");
        return mount;
    }

    private static Object[] readIndexLegacy(BinReader ar, PakInfo info, byte[] key) throws IOException {
        byte[] blob = readAndDecryptIndex(ar, info, key, info.indexOffset, info.indexSize);
        BinReader index = BinReader.memory(blob);

        String mount = validateMountPoint(index.readFString());
        int fileCount = index.readInt();
        if (fileCount < 0 || fileCount > 50_000_000) throw new IOException("corrupt pak index (fileCount=" + fileCount + ")");

        Map<String, PakEntry> files = new LinkedHashMap<>(Math.max(16, fileCount));
        for (int i = 0; i < fileCount; i++) {
            String rel = index.readFString();
            PakEntry e = PakEntry.readLegacy(info, index, mount + rel);
            if (e.isDeleted() && e.uncompressedSize == 0) continue;
            files.put(e.path, e);
        }
        return new Object[]{mount, files};
    }

    private static Object[] readIndexUpdated(BinReader ar, PakInfo info, byte[] key) throws IOException {
        byte[] blob = readAndDecryptIndex(ar, info, key, info.indexOffset, info.indexSize);
        BinReader primary = BinReader.memory(blob);

        String mount = validateMountPoint(primary.readFString());
        int fileCount = primary.readInt();
        primary.skip(8); // PathHashSeed

        if (!primary.readBoolean()) throw new IOException("pak has no path hash index");
        primary.skip(36); // PathHashIndexOffset + Size + Hash

        if (!primary.readBoolean()) throw new IOException("pak has no directory index");
        long directoryIndexOffset = primary.readLong();
        long directoryIndexSize = primary.readLong();
        primary.skip(20); // directory index hash

        int encodedSize = primary.readInt();
        if (encodedSize < 0 || encodedSize > blob.length) throw new IOException("corrupt encoded entries size");
        byte[] encodedBlob = primary.readBytes(encodedSize);

        int nonEncodedCount = primary.readInt();
        if (nonEncodedCount < 0 || nonEncodedCount > 10_000_000) throw new IOException("corrupt non-encoded entry count");
        PakEntry[] nonEncoded = new PakEntry[nonEncodedCount];
        for (int i = 0; i < nonEncodedCount; i++) {
            nonEncoded[i] = PakEntry.readLegacy(info, primary, "");
        }

        byte[] dirBlob = readAndDecryptIndex(ar, info, key, directoryIndexOffset, directoryIndexSize);
        BinReader directoryIndex = BinReader.memory(dirBlob);
        BinReader encoded = BinReader.memory(encodedBlob);

        Map<String, PakEntry> files = new LinkedHashMap<>(Math.max(16, fileCount));
        int dirCount = directoryIndex.readInt();
        boolean mountEndsWithSlash = !mount.isEmpty() && mount.charAt(mount.length() - 1) == '/';
        for (int d = 0; d < dirCount; d++) {
            String dir = directoryIndex.readFString();
            if (mountEndsWithSlash && dir.startsWith("/")) dir = dir.substring(1);
            int filesInDir = directoryIndex.readInt();
            for (int f = 0; f < filesInDir; f++) {
                String name = directoryIndex.readFString();
                int off = directoryIndex.readInt();
                if (off == Integer.MIN_VALUE) continue;
                String full = mount + dir + name;
                PakEntry entry;
                if (off >= 0) {
                    entry = PakEntry.readEncoded(info, encoded, off, full);
                } else {
                    int idx = -off - 1;
                    if (idx < 0 || idx >= nonEncoded.length) continue;
                    entry = nonEncoded[idx];
                    entry.path = full;
                }
                files.put(full, entry);
            }
        }
        return new Object[]{mount, files};
    }

    // --------------------------------------------------------------- extract

    /** Extract a whole entry into memory. */
    public byte[] extract(PakEntry entry) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                (int) Math.min(entry.uncompressedSize, 64 << 20));
        extract(entry, bos, null);
        return bos.toByteArray();
    }

    /** Extract an entry to a stream, with optional progress + cancellation. */
    public synchronized void extract(PakEntry entry, OutputStream out, Progress progress) throws IOException {
        if (entry.isDeleted()) throw new IOException("entry is a delete record");
        int alignment = entry.isEncrypted() ? AesUtil.ALIGN : 1;

        if (entry.isCompressed()) {
            int blockSize = (int) entry.compressionBlockSize;
            long totalBlocks = entry.blockCount();
            byte[] compressed = new byte[0];
            byte[] plain = new byte[blockSize];
            long uncompressedOff = 0;

            for (int b = 0; b < totalBlocks; b++) {
                if (progress != null && progress.isCancelled()) throw new IOException("cancelled");
                long blockStart = entry.blockStarts[b];
                int blockSizeOnDisk = (int) (entry.blockEnds[b] - entry.blockStarts[b]);
                int srcSize = AesUtil.align(blockSizeOnDisk, alignment);
                if (srcSize > compressed.length) compressed = new byte[srcSize];

                ar.seek(blockStart);
                ar.readInto(compressed, 0, srcSize);
                if (entry.isEncrypted()) AesUtil.decryptInPlace(compressed, 0, srcSize, aesKey);

                int uncompressedSize = (int) Math.min(blockSize, entry.uncompressedSize - uncompressedOff);
                Decompress.decompressInto(entry.compressionMethod, compressed, 0, blockSizeOnDisk, plain, uncompressedSize);
                out.write(plain, 0, uncompressedSize);
                uncompressedOff += uncompressedSize;

                if (progress != null) {
                    progress.onProgress(b + 1, (int) totalBlocks, entry.path);
                }
            }
            if (uncompressedOff != entry.uncompressedSize) {
                throw new IOException("extracted " + uncompressedOff + " of " + entry.uncompressedSize + " bytes");
            }
        } else {
            long readOffset = 0;
            long dataOffset = entry.offset + entry.structSize;
            long remaining = entry.uncompressedSize;
            byte[] buf = new byte[1 << 20];
            long total = remaining;
            long done = 0;
            ar.seek(dataOffset);
            while (remaining > 0) {
                if (progress != null && progress.isCancelled()) throw new IOException("cancelled");
                int want = (int) Math.min(buf.length, remaining);
                int stored = entry.isEncrypted() ? AesUtil.align(want, alignment) : want;
                ar.readInto(buf, 0, stored);
                if (entry.isEncrypted()) {
                    AesUtil.decryptInPlace(buf, 0, stored, aesKey);
                    out.write(buf, 0, want);
                } else {
                    out.write(buf, 0, want);
                }
                remaining -= want;
                done += want;
                if (progress != null) {
                    progress.onProgress((int) done, (int) total, entry.path);
                }
            }
        }
        out.flush();
    }
}
