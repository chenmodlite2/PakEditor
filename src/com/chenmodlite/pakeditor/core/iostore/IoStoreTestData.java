package com.chenmodlite.pakeditor.core.iostore;

import com.chenmodlite.pakeditor.core.io.BinWriter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal IoStore container writer used for round-trip verification of {@link UtocReader}.
 *
 * Produces a valid toc version 3 container with an uncompressed directory index and
 * uncompressed blocks (method index 0), which is sufficient to exercise the full read
 * path: header, chunk ids, offset/length table, block table, method names, directory
 * index walk and block extraction.
 *
 * NOTE: this is a test/verification aid, not the production cooker (which is retoc).
 */
public final class IoStoreTestData {

    private final String mountPoint;
    private final int blockSize;
    private final List<Entry> entries = new ArrayList<>();

    private static final class Entry {
        final String path;
        final byte[] data;
        final int chunkType;
        Entry(String path, byte[] data, int chunkType) {
            this.path = path;
            this.data = data;
            this.chunkType = chunkType;
        }
    }

    public IoStoreTestData(String mountPoint, int blockSize) {
        this.mountPoint = mountPoint;
        this.blockSize = blockSize;
    }

    public void add(String rawPath, byte[] data, int chunkType) {
        entries.add(new Entry(rawPath, data, chunkType));
    }

    /** Write the .utoc and its .ucas partition; returns the utoc path. */
    public String write(String utocPath) throws IOException {
        String base = utocPath.substring(0, utocPath.lastIndexOf('.'));
        String ucasPath = base + ".ucas";

        // block table + container layout
        int[] blockOffset = new int[0];
        int[] blockSizeOnDisk = new int[0];
        int[] blockUncompressed = new int[0];
        byte[] blockMethod = new byte[0];

        List<long[]> blocks = new ArrayList<>(); // {offset, csize, usize, method}
        List<Long> chunkOffset = new ArrayList<>();
        List<Long> chunkLength = new ArrayList<>();
        long containerPos = 0;

        // Real UE semantics: every chunk is split into blockSize blocks and its logical
        // offset is the start of its first block in the concatenated block stream.
        // Chunks therefore begin on block boundaries; short chunks still own a whole
        // (partially filled) block.
        try (FileOutputStream cas = new FileOutputStream(ucasPath)) {
            for (Entry e : entries) {
                long logicalStart = blocks.size() * (long) blockSize;
                chunkOffset.add(logicalStart);
                chunkLength.add((long) e.data.length);
                int off = 0;
                while (off < e.data.length) {
                    int n = Math.min(blockSize, e.data.length - off);
                    cas.write(e.data, off, n);
                    blocks.add(new long[]{containerPos, n, n, 0});
                    containerPos += n;
                    off += n;
                }
            }
        }

        // ---- directory index ----
        DirBuilder dirs = new DirBuilder();
        List<int[]> dirEntries = new ArrayList<>(); // {name, firstChild, nextSibling, firstFile}
        List<int[]> fileEntries = new ArrayList<>(); // {name, nextFile, userData}
        List<String> strings = new ArrayList<>();

        dirEntries.add(new int[]{-1, -1, -1, -1}); // node 0 = root
        for (int i = 0; i < entries.size(); i++) {
            String path = entries.get(i).path;
            String rel = stripMount(path);
            int dirNode = dirs.ensureDirs(dirEntries, strings, rel);
            int nameIdx = stringsIdx(strings, rel.substring(rel.lastIndexOf('/') + 1));
            int fileIdx = fileEntries.size();
            fileEntries.add(new int[]{nameIdx, -1, i});
            // append to the directory's file list
            int cur = dirEntries.get(dirNode)[3];
            if (cur == -1) {
                dirEntries.set(dirNode, new int[]{dirEntries.get(dirNode)[0], dirEntries.get(dirNode)[1],
                        dirEntries.get(dirNode)[2], fileIdx});
            } else {
                while (fileEntries.get(cur)[1] != -1) cur = fileEntries.get(cur)[1];
                fileEntries.set(cur, new int[]{fileEntries.get(cur)[0], fileIdx, fileEntries.get(cur)[2]});
            }
        }

        BinWriter di = BinWriter.memory();
        di.writeFString(mountPoint);
        di.writeInt(dirEntries.size());
        for (int[] d : dirEntries) {
            di.writeInt(d[0]); di.writeInt(d[1]); di.writeInt(d[2]); di.writeInt(d[3]);
        }
        di.writeInt(fileEntries.size());
        for (int[] f : fileEntries) {
            di.writeInt(f[0]); di.writeInt(f[1]); di.writeInt(f[2]);
        }
        di.writeInt(strings.size());
        for (String s : strings) di.writeFString(s);
        byte[] dirIndex = di.toByteArray();

        // ---- chunk ids ----
        // note: block indices are recomputed after the cas layout is known, chunk index
        // is informational only (the reader addresses chunks by toc index / offset table)
        byte[][] chunkIds = new byte[entries.size()][];
        for (int i = 0; i < entries.size(); i++) {
            byte[] id = new byte[12];
            long pkgId = pkgIdFromPath(stripMount(entries.get(i).path));
            for (int b = 0; b < 8; b++) id[b] = (byte) (pkgId >>> (8 * b));
            // chunk index (network order) = i+1
            int v = i + 1;
            id[8] = (byte) (v >>> 8);
            id[9] = (byte) v;            id[10] = 0;
            id[11] = (byte) entries.get(i).chunkType;
            chunkIds[i] = id;
        }

        // ---- header ----
        BinWriter header = BinWriter.memory();
        header.writeBytes(UtocReader.MAGIC);
        header.writeByte(3);            // version: PartitionSize
        header.writeByte(0);
        header.writeU16(0);
        header.writeInt(144);           // header size
        header.writeInt(entries.size());
        header.writeInt(blocks.size());
        header.writeInt(12);            // block entry size
        header.writeInt(1);             // method name count
        header.writeInt(32);            // method name length
        header.writeInt(blockSize);
        header.writeInt(dirIndex.length);
        header.writeInt(1);             // partition count
        header.writeLong(1234567890L);  // container id
        header.writeBytes(new byte[16]); // encryption key guid
        header.writeByte(0);            // container flags: none
        header.writeByte(0);
        header.writeU16(0);
        header.writeInt(0);             // perfect hash seeds
        header.writeLong(1L << 40);     // partition size
        header.writeInt(0);             // chunks without perfect hash
        header.writeInt(0);
        header.writeBytes(new byte[40]);

        BinWriter out = BinWriter.memory();
        out.writeBytes(header.toByteArray());
        for (byte[] id : chunkIds) out.writeBytes(id);
        for (int i = 0; i < entries.size(); i++) {
            writeOffsetLength(out, chunkOffset.get(i), chunkLength.get(i));
        }        // no hash maps for version 3
        for (long[] b : blocks) {
            writeBlock(out, b[0], (int) b[1], (int) b[2], (byte) b[3]);
        }
        byte[] name = new byte[32];
        byte[] nm = "None".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nm, 0, name, 0, nm.length);
        out.writeBytes(name);
        // no signatures (not signed)
        out.writeBytes(dirIndex);
        // chunk metas: version < 8 → 32-byte hash + 1 flag
        for (int i = 0; i < entries.size(); i++) out.writeBytes(new byte[33]);

        try (OutputStream os = new FileOutputStream(utocPath)) {
            os.write(out.toByteArray());
        }
        return utocPath;
    }

    private static String stripMount(String path) {
        String p = path;
        while (p.startsWith("../")) p = p.substring(3);
        return UtocReader.normalizePath(p);
    }

    /** Deterministic fake package id (cityhash64 stand-in is not needed for round-trip). */
    private static long pkgIdFromPath(String p) {
        long h = 1469598103934665603L;
        byte[] b = p.toLowerCase().getBytes(StandardCharsets.UTF_8);
        for (byte x : b) { h ^= (x & 0xFF); h *= 1099511628211L; }
        return h;
    }

    private static int stringsIdx(List<String> strings, String s) {
        int i = strings.indexOf(s);
        if (i >= 0) return i;
        strings.add(s);
        return strings.size() - 1;
    }

    private static void writeOffsetLength(BinWriter w, long offset, long length) throws IOException {
        byte[] d = new byte[10];
        for (int i = 0; i < 5; i++) d[i] = (byte) (offset >>> (8 * (4 - i)));
        for (int i = 0; i < 5; i++) d[5 + i] = (byte) (length >>> (8 * (4 - i)));
        w.writeBytes(d);
    }

    private static void writeBlock(BinWriter w, long offset, int csize, int usize, byte method) throws IOException {
        byte[] d = new byte[12];
        d[0] = (byte) offset; d[1] = (byte) (offset >>> 8); d[2] = (byte) (offset >>> 16);
        d[3] = (byte) (offset >>> 24); d[4] = (byte) (offset >>> 32);
        d[5] = (byte) csize; d[6] = (byte) (csize >>> 8); d[7] = (byte) (csize >>> 16);
        d[8] = (byte) usize; d[9] = (byte) (usize >>> 8); d[10] = (byte) (usize >>> 16);
        d[11] = method;
        w.writeBytes(d);
    }

    /** Builds the directory tree node structure (children/siblings as index links). */
    private static final class DirBuilder {
        int ensureDirs(List<int[]> dirEntries, List<String> strings, String rel) {
            String[] parts = rel.split("/");
            int node = 0;
            for (int i = 0; i < parts.length - 1; i++) {
                int child = findChild(dirEntries, strings, node, parts[i]);
                if (child == -1) {
                    int nameIdx = stringsIdx(strings, parts[i]);
                    child = dirEntries.size();
                    dirEntries.add(new int[]{nameIdx, -1, -1, -1});
                    int prev = dirEntries.get(node)[1];
                    if (prev == -1) {
                        dirEntries.set(node, new int[]{dirEntries.get(node)[0], child,
                                dirEntries.get(node)[2], dirEntries.get(node)[3]});
                    } else {
                        while (dirEntries.get(prev)[2] != -1) prev = dirEntries.get(prev)[2];
                        dirEntries.set(prev, new int[]{dirEntries.get(prev)[0], dirEntries.get(prev)[1],
                                child, dirEntries.get(prev)[3]});
                    }
                }
                node = child;
            }
            return node;
        }

        private int findChild(List<int[]> dirEntries, List<String> strings, int node, String name) {
            int child = dirEntries.get(node)[1];
            while (child != -1) {
                int nameIdx = dirEntries.get(child)[0];
                if (nameIdx >= 0 && nameIdx < strings.size() && strings.get(nameIdx).equals(name)) return child;
                child = dirEntries.get(child)[2];
            }
            return -1;
        }
    }
}
