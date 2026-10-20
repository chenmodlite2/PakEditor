package com.chenmodlite.pakeditor.core.pak;

import com.chenmodlite.pakeditor.core.io.BinReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pak tail footer. Port of CUE4Parse FPakInfo.ReadFPakInfo for stock layouts:
 * the footer is probed at several known sizes (UE4.22 = 61, +1..3 compression
 * slots, 4 slots = UE4.22-ish Size8, 5 slots + frozen flag = Size8a/Size9).
 * Game-specific obfuscated footers are out of scope for the Android port.
 */
public final class PakInfo {

    public static final int PAK_FILE_MAGIC = 0x5A6F12E1;

    public static final int VER_INITIAL = 1;
    public static final int VER_NO_TIMESTAMPS = 2;
    public static final int VER_COMPRESSION_ENCRYPTION = 3;
    public static final int VER_INDEX_ENCRYPTION = 4;
    public static final int VER_RELATIVE_CHUNK_OFFSETS = 5;
    public static final int VER_DELETE_RECORDS = 6;
    public static final int VER_ENCRYPTION_KEY_GUID = 7;
    public static final int VER_FNAME_BASED_COMPRESSION = 8;
    public static final int VER_FROZEN_INDEX = 9;
    public static final int VER_PATH_HASH_INDEX = 10;
    public static final int VER_FNV64_BUGFIX = 11;
    public static final int VER_UTF8_PAK_DIRECTORY = 12;

    public int version;
    public boolean isSubVersion;          // version 8 with 5-slot footer (UE4.23)
    public long indexOffset;
    public long indexSize;
    public byte[] indexHash = new byte[20];
    public boolean encryptedIndex;
    public boolean indexIsFrozen;
    public byte[] encryptionKeyGuid = new byte[16];
    public List<Integer> compressionMethods = new ArrayList<>(); // method ids; index 0 = None
    public int footerSize;

    public static PakInfo read(BinReader ar) throws IOException {
        long length = ar.length();
        int[] offsetsToTry = {61 + 160, 61 + 128, 61, 61 + 161, 61 + 32, 61 + 64, 61 + 96};

        for (int off : offsetsToTry) {
            if (off > length) continue;
            PakInfo info = tryParse(ar, length - off, off);
            if (info != null) return info;
        }
        throw new IOException("not a pak file (footer not found)");
    }

    private static PakInfo tryParse(BinReader ar, long base, int probeSize) throws IOException {
        ar.seek(base);
        PakInfo info = new PakInfo();
        // new fields (v7+): EncryptionKeyGuid, EncryptedIndex
        ar.readInto(info.encryptionKeyGuid, 0, 16);
        info.encryptedIndex = ar.readByte() != 0;
        // old fields
        long magic = ar.readInt() & 0xFFFFFFFFL;
        if (magic != PAK_FILE_MAGIC) return null;
        info.version = ar.readInt();
        if (info.version < VER_INITIAL || info.version > VER_UTF8_PAK_DIRECTORY) return null;
        info.indexOffset = ar.readLong();
        info.indexSize = ar.readLong();
        ar.readInto(info.indexHash, 0, 20);

        if (info.version == VER_FROZEN_INDEX) {
            info.indexIsFrozen = ar.readByte() != 0;
        }
        if (info.version < VER_INDEX_ENCRYPTION) info.encryptedIndex = false;
        if (info.version < VER_ENCRYPTION_KEY_GUID) {
            info.encryptionKeyGuid = new byte[16];
        }

        // compression method slots
        int consumed = (int) (ar.position() - base);
        info.isSubVersion = info.version == VER_FNAME_BASED_COMPRESSION && probeSize == 61 + 160;
        if (info.version < VER_FNAME_BASED_COMPRESSION) {
            info.compressionMethods.add(DecompressIds.NONE);
            info.compressionMethods.add(DecompressIds.ZLIB);
            info.compressionMethods.add(DecompressIds.GZIP);
            info.compressionMethods.add(DecompressIds.OODLE);
            info.compressionMethods.add(DecompressIds.LZ4);
            info.compressionMethods.add(DecompressIds.ZSTD);
        } else {
            int slotBytes = probeSize - consumed;
            int slots = slotBytes / 32;
            info.compressionMethods.add(DecompressIds.NONE);
            for (int i = 0; i < slots; i++) {
                byte[] raw = ar.readBytes(32);
                int end = 0;
                while (end < 32 && raw[end] != 0) end++;
                if (end == 0) continue;
                String name = new String(raw, 0, end, com.chenmodlite.pakeditor.core.io.BinReader.LATIN1);
                int id = DecompressIds.fromName(name);
                info.compressionMethods.add(id);
            }
        }

        // sanity: index must live inside the file
        if (info.indexOffset < 0 || info.indexOffset >= ar.length()) return null;
        if (info.indexSize <= 0 || info.indexOffset + info.indexSize > ar.length()) return null;

        info.footerSize = probeSize;
        return info;
    }

    /** Value of the compression method list at an entry's stored index. */
    public int methodAt(int index) {
        if (index < 0 || index >= compressionMethods.size()) return DecompressIds.UNKNOWN;
        return compressionMethods.get(index);
    }

    static final class DecompressIds {
        static final int NONE = 0;
        static final int ZLIB = 1;
        static final int GZIP = 2;
        static final int CUSTOM = 3;
        static final int OODLE = 4;
        static final int LZ4 = 5;
        static final int LZO = 6;
        static final int ZSTD = 7;
        static final int UNKNOWN = -1;

        static int fromName(String n) {
            switch (n.toLowerCase()) {
                case "zlib": return ZLIB;
                case "gzip": return GZIP;
                case "oodle": return OODLE;
                case "lz4": return LZ4;
                case "lzo": return LZO;
                case "zstd": return ZSTD;
                case "none": return NONE;
                default: return UNKNOWN;
            }
        }

        static String name(int id) {
            switch (id) {
                case ZLIB: return "Zlib";
                case GZIP: return "Gzip";
                case OODLE: return "Oodle";
                case LZ4: return "LZ4";
                case LZO: return "LZO";
                case ZSTD: return "Zstd";
                default: return "None";
            }
        }
    }
}
