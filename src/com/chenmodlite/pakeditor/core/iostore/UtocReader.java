package com.chenmodlite.pakeditor.core.iostore;

import com.chenmodlite.pakeditor.core.compress.Decompress;
import com.chenmodlite.pakeditor.core.crypto.AesUtil;
import com.chenmodlite.pakeditor.core.io.BinReader;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UE4/UE5 IoStore container reader (.utoc + .ucas partitions).
 *
 * Format references (cross-checked against each other):
 *  - CUE4Parse: FIoStoreTocResource / IoStoreReader.ProcessIndex / block extraction loop
 *  - retoc:     FIoStoreTocHeader, Toc::read order, directory_index module
 *
 * Read order: 144-byte header, chunk ids (12B), offset/length (10B BE each),
 * perfect-hash maps (version gated), compression blocks (12B), method names,
 * block signatures (signed containers only), directory index (ECB-encrypted
 * when the container is encrypted), chunk metas.
 */
public final class UtocReader implements Closeable {

    public static final int HEADER_SIZE = 144;
    public static final byte[] MAGIC = "-==--==--==--==-".getBytes(StandardCharsets.US_ASCII);

    public static final int FLAG_COMPRESSED = 1;
    public static final int FLAG_ENCRYPTED = 2;
    public static final int FLAG_SIGNED = 4;
    public static final int FLAG_INDEXED = 8;

    public interface Progress {
        void onProgress(int done, int total, String currentPath);
        boolean isCancelled();
    }

    /** One file from the directory index. */
    public static final class FileEntry {
        public final String rawPath;     // includes mount point, e.g. "../../../Game/Foo.uasset"
        public final String displayPath; // normalized, e.g. "Game/Foo.uasset"
        public final int tocIndex;       // index into chunk ids / offset table
        public final byte[] chunkId;     // 12 bytes
        public final String chunkType;   // e.g. "ExportBundleData", "BulkData"

        FileEntry(String rawPath, String displayPath, int tocIndex, byte[] chunkId, String chunkType) {
            this.rawPath = rawPath;
            this.displayPath = displayPath;
            this.tocIndex = tocIndex;
            this.chunkId = chunkId;
            this.chunkType = chunkType;
        }
    }

    // header
    public final String path;
    public final int version;
    public final long tocEntryCount;
    public final long blockEntryCount;
    public final int blockEntrySize;
    public final int compressionBlockSize;
    public final int directoryIndexSize;
    public final int partitionCount;
    public final long partitionSize;
    public final long containerId;
    public final byte[] encryptionKeyGuid;
    public final int containerFlags;
    public final boolean encrypted;
    public final boolean signedContainer;

    // tables
    private final byte[][] chunkIds;      // [tocEntryCount][12]
    private final long[] chunkOffsets;    // logical offsets
    private final long[] chunkLengths;
    private final long[] blockOffsets;    // container offsets of compressed blocks
    private final int[] blockCompressedSize;
    private final int[] blockUncompressedSize;
    private final byte[] blockMethodIndex; // 0 = None, else methods[index]
    private final int[] methodIds;         // Decompress ids, index 0 = NONE (CUE4Parse convention)
    private final String[] methodNames;
    private final Map<String, FileEntry> files;
    private final String mountPoint;
    private final byte[] aesKey;

    private final RandomAccessFile[] partitions;
    private final long[] partitionLengths;

    public static UtocReader open(String utocPath, byte[] aesKey) throws IOException {
        BinReader ar = BinReader.file(utocPath);
        try {
            byte[] magic = ar.readBytes(16);
            for (int i = 0; i < 16; i++) {
                if (magic[i] != MAGIC[i]) throw new IOException("not a utoc file (bad magic): " + utocPath);
            }
            int version = ar.readByte();
            ar.readByte();                            // reserved0
            ar.readU16();                             // reserved1
            long headerSize = ar.readInt() & 0xFFFFFFFFL;
            long tocEntryCount = ar.readInt() & 0xFFFFFFFFL;
            long blockEntryCount = ar.readInt() & 0xFFFFFFFFL;
            long blockEntrySize = ar.readInt() & 0xFFFFFFFFL;
            long methodNameCount = ar.readInt() & 0xFFFFFFFFL;
            long methodNameLength = ar.readInt() & 0xFFFFFFFFL;
            long compressionBlockSize = ar.readInt() & 0xFFFFFFFFL;
            long directoryIndexSize = ar.readInt() & 0xFFFFFFFFL;
            long partitionCount = ar.readInt() & 0xFFFFFFFFL;
            long containerId = ar.readLong();
            byte[] guid = ar.readBytes(16);
            int containerFlags = ar.readByte();
            ar.readByte();                            // reserved3
            ar.readU16();                             // reserved4
            long perfectHashSeedsCount = ar.readInt() & 0xFFFFFFFFL;
            long partitionSize = ar.readLong();
            long chunksWithoutHashCount = ar.readInt() & 0xFFFFFFFFL;
            ar.readInt();                             // reserved7
            ar.readBytes(40);                         // reserved8[5]

            if (version < 3) {                        // < PartitionSize: trailing fields undefined
                partitionCount = 1;
                partitionSize = Long.MAX_VALUE;
            }
            if (partitionSize <= 0 || partitionCount <= 0) {
                partitionCount = 1;
                partitionSize = Long.MAX_VALUE;
            }
            if (tocEntryCount > Integer.MAX_VALUE || blockEntryCount > Integer.MAX_VALUE) {
                throw new IOException("utoc too large: " + tocEntryCount + " entries");
            }
            if (compressionBlockSize <= 0) throw new IOException("bad compression block size");
            if (blockEntrySize == 0) blockEntrySize = 12;
            if (blockEntrySize < 12) {
                throw new IOException("unsupported compressed block entry size: " + blockEntrySize);
            }
            if (headerSize > HEADER_SIZE) ar.seek(headerSize);

            int n = (int) tocEntryCount;
            int nb = (int) blockEntryCount;

            // chunk ids (12 bytes: u64 LE chunk id, u16 chunk index, pad, type)
            byte[][] chunkIds = new byte[n][];
            for (int i = 0; i < n; i++) chunkIds[i] = ar.readBytes(12);

            // offset + length (10 bytes: 5-byte BE offset, 5-byte BE length)
            long[] offsets = new long[n];
            long[] lengths = new long[n];
            for (int i = 0; i < n; i++) {
                byte[] d = ar.readBytes(10);
                offsets[i] = ((long) (d[0] & 0xFF) << 32) | ((long) (d[1] & 0xFF) << 24)
                        | ((long) (d[2] & 0xFF) << 16) | ((long) (d[3] & 0xFF) << 8) | (d[4] & 0xFF);
                lengths[i] = ((long) (d[5] & 0xFF) << 32) | ((long) (d[6] & 0xFF) << 24)
                        | ((long) (d[7] & 0xFF) << 16) | ((long) (d[8] & 0xFF) << 8) | (d[9] & 0xFF);
            }

            // perfect hash maps (version gated exactly per CUE4Parse + retoc)
            long seedsCount = 0, noHashCount = 0;
            if (version >= 5) {          // PerfectHashWithOverflow
                seedsCount = perfectHashSeedsCount;
                noHashCount = chunksWithoutHashCount;
            } else if (version >= 4) {   // PerfectHash
                seedsCount = perfectHashSeedsCount;
            }
            ar.skip(seedsCount * 4);
            ar.skip(noHashCount * 4);

            // compressed block entries
            long[] bOffsets = new long[nb];
            int[] bComp = new int[nb];
            int[] bUncomp = new int[nb];
            byte[] bMethod = new byte[nb];
            for (int i = 0; i < nb; i++) {
                long start = ar.position();
                byte[] d = ar.readBytes(12);
                bOffsets[i] = ((long) d[0] & 0xFF) | (((long) d[1] & 0xFF) << 8) | (((long) d[2] & 0xFF) << 16)
                        | (((long) d[3] & 0xFF) << 24) | (((long) d[4] & 0xFF) << 32);
                bComp[i] = (d[5] & 0xFF) | ((d[6] & 0xFF) << 8) | ((d[7] & 0xFF) << 16);
                bUncomp[i] = (d[8] & 0xFF) | ((d[9] & 0xFF) << 8) | ((d[10] & 0xFF) << 16);
                bMethod[i] = d[11];
                if (blockEntrySize > 12) ar.seek(start + blockEntrySize);
            }

            // compression method names: methods[0] = None (CUE4Parse convention)
            if (methodNameLength == 0) methodNameCount = 0;
            String[] names = new String[(int) methodNameCount + 1];
            int[] ids = new int[(int) methodNameCount + 1];
            names[0] = "None";
            ids[0] = Decompress.METHOD_NONE;
            for (int i = 0; i < (int) methodNameCount; i++) {
                byte[] raw = ar.readBytes((int) methodNameLength);
                int end = 0;
                while (end < raw.length && raw[end] != 0) end++;
                String name = new String(raw, 0, end, StandardCharsets.UTF_8);
                names[i + 1] = name;
                ids[i + 1] = Decompress.idFromName(name);
            }

            boolean encrypted = (containerFlags & FLAG_ENCRYPTED) != 0;
            boolean isSigned = (containerFlags & FLAG_SIGNED) != 0;

            // block signatures (signed containers only): u32 size + size + size + 20 * blocks
            if (isSigned) {
                int size = ar.readInt();
                if (size < 0 || size > 1 << 20) throw new IOException("bad signature size " + size);
                ar.skip(size);                 // toc signature
                ar.skip(size);                 // block signature
                ar.skip(20L * nb);             // per-block FSHAHash
            }

            // directory index (ECB encrypted when container encrypted)
            byte[] dirIndexBytes = null;
            if (directoryIndexSize > 0) {
                dirIndexBytes = ar.readBytes((int) directoryIndexSize);
                if (encrypted) {
                    if (aesKey == null) {
                        throw new IOException("container is encrypted — set an AES key first");
                    }
                    int full = dirIndexBytes.length - (dirIndexBytes.length % 16);
                    if (full > 0) AesUtil.decryptInPlace(dirIndexBytes, 0, full, aesKey);
                }
            }

            // chunk metas: 33 bytes (< v8) or 24 bytes (>= v8: 20-byte hash + flag + 3 pad)
            int metaSize = version >= 8 ? 24 : 33;
            ar.skip((long) n * metaSize);

            // ---- directory index: mount point, dirs, files, string table ----
            String mount = "";
            Map<String, FileEntry> files = new LinkedHashMap<>();
            if (dirIndexBytes != null) {
                BinReader di = BinReader.memory(dirIndexBytes);
                try {
                    mount = di.readFString();
                    int dirCount = di.readInt();
                    if (dirCount < 0 || (long) dirCount * 16 > dirIndexBytes.length) dirCount = 0;
                    long[] dirs = new long[dirCount * 4];
                    for (int i = 0; i < dirs.length; i++) dirs[i] = di.readInt() & 0xFFFFFFFFL;
                    int fileCount = di.readInt();
                    if (fileCount < 0 || (long) fileCount * 12 > dirIndexBytes.length) fileCount = 0;
                    long[] fileEntries = new long[fileCount * 3];
                    for (int i = 0; i < fileEntries.length; i++) fileEntries[i] = di.readInt() & 0xFFFFFFFFL;
                    int strCount = di.readInt();
                    if (strCount < 0 || strCount > dirIndexBytes.length) strCount = 0;
                    String[] strings = new String[strCount];
                    for (int i = 0; i < strCount; i++) strings[i] = di.readFString();

                    if (dirs.length >= 4) {
                        walkDirectory(0, mount, dirs, fileEntries, strings, files, chunkIds);
                    }
                } finally {
                    di.close();
                }
            }

            return new UtocReader(utocPath, version, tocEntryCount, blockEntryCount, (int) blockEntrySize,
                    (int) compressionBlockSize, (int) directoryIndexSize, (int) partitionCount, partitionSize,
                    containerId, guid, containerFlags, encrypted, isSigned,
                    chunkIds, offsets, lengths, bOffsets, bComp, bUncomp, bMethod, ids, names,
                    files, mount, aesKey);
        } finally {
            ar.close();
        }
    }

    private static final long INVALID = 0xFFFFFFFFL;

    private static void walkDirectory(long dirIdx, String parentPath, long[] dirs, long[] fileEntries,
                                      String[] strings, Map<String, FileEntry> files, byte[][] chunkIds) {
        if (dirIdx == INVALID || dirIdx < 0 || dirIdx * 4 + 3 >= dirs.length) return;

        long dir = dirIdx;
        while (dir != INVALID && dir >= 0 && dir * 4 + 3 < dirs.length) {
            long nameIdx = dirs[(int) dir * 4];
            long firstChild = dirs[(int) dir * 4 + 1];
            long nextSibling = dirs[(int) dir * 4 + 2];
            long firstFile = dirs[(int) dir * 4 + 3];

            String dirName = nameIdx != INVALID && nameIdx < strings.length ? strings[(int) nameIdx] : null;
            String basePath = parentPath;
            if (dirName != null && !dirName.isEmpty()) basePath = appendPath(parentPath, dirName);

            long file = firstFile;
            while (file != INVALID && file >= 0 && file * 3 + 2 < fileEntries.length) {
                long fNameIdx = fileEntries[(int) file * 3];
                long nextFile = fileEntries[(int) file * 3 + 1];
                long userData = fileEntries[(int) file * 3 + 2];

                if (fNameIdx != INVALID && fNameIdx < strings.length
                        && userData != INVALID && userData < chunkIds.length) {
                    String raw = appendPath(basePath, strings[(int) fNameIdx]);
                    int tocIndex = (int) userData;
                    byte[] id = chunkIds[tocIndex];
                    if (!files.containsKey(raw)) {
                        files.put(raw, new FileEntry(raw, normalizePath(raw), tocIndex,
                                id, chunkTypeName(id[11] & 0xFF)));
                    }
                }
                file = nextFile;
            }

            walkDirectory(firstChild, basePath, dirs, fileEntries, strings, files, chunkIds);
            dir = nextSibling;
        }
    }

    private static String appendPath(String base, String name) {
        if (base == null || base.isEmpty()) return name;
        if (base.endsWith("/")) return base + name;
        return base + "/" + name;
    }

    /** Strip ../../../ and leading slashes for display / tree building. */
    public static String normalizePath(String p) {
        String s = p;
        while (s.startsWith("../")) s = s.substring(3);
        while (s.startsWith("./")) s = s.substring(2);
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    public static String chunkTypeName(int type) {
        switch (type) {
            case 1: return "InstallManifest";
            case 2: return "ExportBundleData";
            case 3: return "BulkData";
            case 4: return "OptionalBulkData";
            case 5: return "MemoryMappedBulkData";
            case 6: return "LoaderGlobalMeta";
            case 7: return "LoaderInitialLoadMeta";
            case 8: return "LoaderGlobalNames";
            case 9: return "LoaderGlobalNameHashes";
            case 10: return "ContainerHeader";
            default: return type == 0 ? "Invalid" : "Type" + type;
        }
    }

    private UtocReader(String path, int version, long tocEntryCount, long blockEntryCount, int blockEntrySize,
                       int compressionBlockSize, int directoryIndexSize, int partitionCount, long partitionSize,
                       long containerId, byte[] guid, int containerFlags, boolean encrypted, boolean signedContainer,
                       byte[][] chunkIds, long[] offsets, long[] lengths, long[] blockOffsets, int[] bComp, int[] bUncomp,
                       byte[] bMethod, int[] methodIds, String[] methodNames, Map<String, FileEntry> files,
                       String mountPoint, byte[] aesKey) {
        this.path = path;
        this.version = version;
        this.tocEntryCount = tocEntryCount;
        this.blockEntryCount = blockEntryCount;
        this.blockEntrySize = blockEntrySize;
        this.compressionBlockSize = compressionBlockSize;
        this.directoryIndexSize = directoryIndexSize;
        this.partitionCount = partitionCount;
        this.partitionSize = partitionSize;
        this.containerId = containerId;
        this.encryptionKeyGuid = guid;
        this.containerFlags = containerFlags;
        this.encrypted = encrypted;
        this.signedContainer = signedContainer;
        this.chunkIds = chunkIds;
        this.chunkOffsets = offsets;
        this.chunkLengths = lengths;
        this.blockOffsets = blockOffsets;
        this.blockCompressedSize = bComp;
        this.blockUncompressedSize = bUncomp;
        this.blockMethodIndex = bMethod;
        this.methodIds = methodIds;
        this.methodNames = methodNames;
        this.files = files;
        this.mountPoint = mountPoint;
        this.aesKey = aesKey;
        this.partitions = new RandomAccessFile[Math.max(1, partitionCount)];
        this.partitionLengths = new long[Math.max(1, partitionCount)];
        for (int i = 0; i < partitionLengths.length; i++) partitionLengths[i] = -1;
    }

    // ------------------------------------------------------------------ access

    public String mountPoint() { return mountPoint; }

    public int fileCount() { return files.size(); }

    /** Raw paths including mount point, in index order. */
    public List<String> rawPaths() { return new ArrayList<>(files.keySet()); }

    public Map<String, FileEntry> files() { return files; }

    public FileEntry file(String rawPath) { return files.get(rawPath); }

    public FileEntry fileByDisplayPath(String displayPath) {
        for (FileEntry e : files.values()) if (e.displayPath.equals(displayPath)) return e;
        return null;
    }

    public int chunkCount() { return chunkIds.length; }

    public long chunkLogicalSize(int tocIndex) {
        if (tocIndex < 0 || tocIndex >= chunkLengths.length) throw new IndexOutOfBoundsException("toc " + tocIndex);
        return chunkLengths[tocIndex];
    }

    public long chunkLogicalOffset(int tocIndex) { return chunkOffsets[tocIndex]; }

    public byte[] chunkId(int tocIndex) { return chunkIds[tocIndex]; }

    /** Compression method name used by the chunk's first block. */
    public String compressionMethodOf(int tocIndex) {
        if (blockMethodIndex.length == 0 || compressionBlockSize <= 0) return "None";
        int first = (int) (chunkOffsets[tocIndex] / compressionBlockSize);
        if (first < 0 || first >= blockMethodIndex.length) return "None";
        int idx = blockMethodIndex[first] & 0xFF;
        if (idx <= 0 || idx >= methodNames.length) return "None";
        return methodNames[idx];
    }

    public String[] compressionMethods() { return methodNames.clone(); }

    public String flagsString() {
        StringBuilder sb = new StringBuilder();
        if ((containerFlags & FLAG_COMPRESSED) != 0) sb.append("Compressed ");
        if (encrypted) sb.append("Encrypted ");
        if (signedContainer) sb.append("Signed ");
        if ((containerFlags & FLAG_INDEXED) != 0) sb.append("Indexed ");
        if ((containerFlags & 16) != 0) sb.append("OnDemand ");
        return sb.toString().trim();
    }

    /** Paths of the .ucas partition files that back this utoc. */
    public String[] containerPaths() {
        String base = path;
        int dot = base.lastIndexOf('.');
        int sep = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (dot > sep) base = base.substring(0, dot);
        int parts = Math.max(1, partitionCount);
        String[] out = new String[parts];
        for (int i = 0; i < parts; i++) {
            out[i] = i == 0 ? base + ".ucas" : base + "_s" + i + ".ucas";
        }
        return out;
    }

    // ------------------------------------------------------------------ extract

    public byte[] extractFile(FileEntry entry) throws IOException {
        long size = chunkLogicalSize(entry.tocIndex);
        if (size > Integer.MAX_VALUE - 8) throw new IOException("chunk too large to buffer: " + size);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) size);
        extractFile(entry, bos, null);
        return bos.toByteArray();
    }

    public void extractFile(FileEntry entry, OutputStream out, Progress progress) throws IOException {
        extractChunk(entry.tocIndex, out, progress, entry.displayPath);
    }

    public byte[] extractChunk(int tocIndex) throws IOException {
        long size = chunkLogicalSize(tocIndex);
        if (size > Integer.MAX_VALUE - 8) throw new IOException("chunk too large to buffer: " + size);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) size);
        extractChunk(tocIndex, bos, null, "chunk " + tocIndex);
        return bos.toByteArray();
    }

    public void extractChunk(int tocIndex, OutputStream out, Progress progress) throws IOException {
        extractChunk(tocIndex, out, progress, "chunk " + tocIndex);
    }

    private synchronized void extractChunk(int tocIndex, OutputStream out, Progress progress, String label)
            throws IOException {
        if (tocIndex < 0 || tocIndex >= chunkOffsets.length) throw new IOException("bad toc index " + tocIndex);
        long offset = chunkOffsets[tocIndex];
        long size = chunkLengths[tocIndex];
        long blockSize = compressionBlockSize;
        long firstBlock = offset / blockSize;
        // last block containing the final byte of the chunk (inclusive)
        long lastBlock = (offset + size - 1) / blockSize;
        // number of bytes to skip at the start of the first block: the chunk's logical
        // offset is a stored-offset inside the concatenated block stream, so the first
        // block contributes only its tail.
        int skipInFirstBlock = (int) (offset - firstBlock * blockSize);
        long remaining = size;

        if (lastBlock >= blockOffsets.length) {
            throw new IOException("block range out of bounds (" + firstBlock + ".." + lastBlock + " of "
                    + blockOffsets.length + ") for " + label);
        }

        byte[] compBuf = new byte[0];
        byte[] uncompBuf = new byte[0];
        long totalBlocks = lastBlock - firstBlock + 1;
        long done = 0;

        for (long b = firstBlock; b <= lastBlock; b++) {
            if (progress != null && progress.isCancelled()) throw new IOException("cancelled");

            long blockOff = blockOffsets[(int) b];
            int cSize = blockCompressedSize[(int) b];
            int uSize = blockUncompressedSize[(int) b];
            int methodIdx = blockMethodIndex[(int) b] & 0xFF;

            long rawSize = align(cSize, 16);
            int partIdx = partitionCount <= 1 ? 0 : (int) (blockOff / partitionSize);
            long partOff = partitionCount <= 1 ? blockOff : blockOff % partitionSize;
            if (partIdx >= partitionCount) throw new IOException("partition " + partIdx + " missing");

            RandomAccessFile raf = partition(partIdx);
            long pLen = partitionLengths[partIdx];
            long avail = Math.max(0, pLen - partOff);
            int toRead = (int) Math.min(rawSize, avail);
            if (toRead < cSize) throw new IOException("container truncated (block " + b + ")");

            if (compBuf.length < toRead) compBuf = new byte[toRead];
            raf.seek(partOff);
            raf.readFully(compBuf, 0, toRead);

            if (encrypted) {
                int dec = toRead - (toRead % 16);
                if (dec > 0) AesUtil.decryptInPlace(compBuf, 0, dec, aesKey);
            }

            byte[] src;
            int srcLen;
            if (methodIdx == 0) {
                src = compBuf;
                srcLen = cSize;
            } else {
                int methodId = methodIdx < methodIds.length ? methodIds[methodIdx] : -1;
                String mName = methodIdx < methodNames.length ? methodNames[methodIdx] : "?" + methodIdx;
                if (methodId < 0) throw new IOException("unknown compression method '" + mName + "' in " + label);
                if (uncompBuf.length < uSize) uncompBuf = new byte[uSize];
                try {
                    Decompress.decompressInto(methodId, compBuf, 0, cSize, uncompBuf, uSize);
                } catch (Decompress.UnsupportedCompressionException e) {
                    throw new IOException("entry '" + label + "': " + e.getMessage());
                }
                src = uncompBuf;
                srcLen = uSize;
            }

            int skip = (b == firstBlock) ? skipInFirstBlock : 0;
            if (srcLen <= 0 && remaining > 0) {
                throw new IOException("corrupt block " + b + " for " + label + " (empty block)");
            }
            if (skip > srcLen) {
                throw new IOException("corrupt block " + b + " for " + label
                        + " (offset in block " + skip + " > block size " + srcLen + ")");
            }
            int available = srcLen - skip;
            if (available <= 0 && remaining > 0) {
                throw new IOException("corrupt block " + b + " for " + label + " (no data in block)");
            }
            int copySize = (int) Math.min(available, remaining);
            out.write(src, skip, copySize);
            remaining -= copySize;
            done += copySize;

            if (progress != null) {
                progress.onProgress((int) (b - firstBlock + 1), (int) totalBlocks, label);
            }
        }
        if (remaining != 0) {
            throw new IOException("extracted " + done + " of " + size + " bytes for " + label);
        }
    }

    private RandomAccessFile partition(int idx) throws IOException {
        synchronized (partitions) {
            if (partitions[idx] == null) {
                String[] paths = containerPaths();
                RandomAccessFile raf;
                try {
                    raf = new RandomAccessFile(paths[idx], "r");
                } catch (IOException e) {
                    throw new IOException("cannot open container partition " + idx + " (" + paths[idx] + "): "
                            + e.getMessage());
                }
                partitions[idx] = raf;
                partitionLengths[idx] = raf.length();
            }
            return partitions[idx];
        }
    }

    private static long align(long v, long a) {
        if (a <= 0) return v;
        long r = v % a;
        return r == 0 ? v : v + (a - r);
    }

    @Override
    public void close() {
        synchronized (partitions) {
            for (int i = 0; i < partitions.length; i++) {
                if (partitions[i] != null) {
                    try { partitions[i].close(); } catch (IOException ignored) { }
                    partitions[i] = null;
                }
            }
        }
    }
}
