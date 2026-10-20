import com.chenmodlite.pakeditor.core.compress.Decompress;
import com.chenmodlite.pakeditor.core.compress.Lz4;
import com.chenmodlite.pakeditor.core.crypto.AesUtil;
import com.chenmodlite.pakeditor.core.pak.PakEntry;
import com.chenmodlite.pakeditor.core.pak.PakReader;
import com.chenmodlite.pakeditor.core.pak.PakWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class CoreTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        testLz4();
        testAes();
        testPakRoundTrip();
        testPakCompressed();
        System.out.println(failures == 0 ? "ALL OK" : (failures + " FAILURES"));
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(boolean cond, String what) {
        if (cond) System.out.println("  ok: " + what);
        else { System.out.println("  FAIL: " + what); failures++; }
    }

    static void testLz4() {
        System.out.println("[lz4]");
        // literal-only block: token 0x60 = 6 literals, no match
        byte[] src = new byte[]{0x60, 'h', 'e', 'l', 'l', 'o', '!'};
        byte[] out = Lz4.decompress(src, 0, 7, 6);
        check(new String(out).equals("hello!"), "lz4 literals");

        // match run: token 0x32 = 3 literals, match len 2+4=6, offset 3 -> "abcabcabc"
        byte[] src2 = new byte[]{0x32, 'a', 'b', 'c', 3, 0};
        try {
            byte[] out2 = Lz4.decompress(src2, 0, src2.length, 9);
            check(new String(out2).equals("abcabcabc"), "lz4 match run");
        } catch (RuntimeException e) {
            System.out.println("  FAIL lz4 match: " + e.getMessage()); failures++;
        }
    }

    static void testAes() {
        System.out.println("[aes]");
        byte[] key = AesUtil.parseKey("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F");
        check(key != null && key.length == 32, "key parse");
        byte[] data = new byte[4096];
        new Random(7).nextBytes(data);
        byte[] enc = AesUtil.encrypt(data, 0, data.length, key);
        check(!Arrays.equals(enc, data), "encrypt changes data");
        AesUtil.decryptInPlace(enc, 0, enc.length, key);
        check(Arrays.equals(enc, data), "decrypt restores");
        check(AesUtil.parseKey("zz") == null, "bad key rejected");
    }

    static void testPakRoundTrip() throws Exception {
        System.out.println("[pak roundtrip plain]");
        File f = File.createTempFile("test", ".pak");
        f.deleteOnExit();
        PakWriter w = new PakWriter("../");
        byte[] small = "hello pak world\n".getBytes();
        byte[] big = new byte[3 * 1024 * 1024 + 137];
        new Random(42).nextBytes(big);
        byte[] utf16 = "Game/Content/Unicode_äöü.txt".getBytes();
        w.add("../Game/Content/Hello.txt", small, false);
        w.add("../Game/Content/Big.bin", big, false);
        w.add("../Game/Content/Ünicode.txt", utf16, false);
        long size = w.write(f.getAbsolutePath());
        check(size > big.length, "pak written " + size);

        PakReader r = PakReader.open(f.getAbsolutePath(), null);
        System.out.println("  mount=" + r.mountPoint + " files=" + r.fileCount() + " ver=" + r.info.version
                + " slots=" + r.info.compressionMethods.size() + " footer=" + r.info.footerSize);
        check(r.fileCount() == 3, "file count");
        check(r.mountPoint.equals("../"), "mount point");
        check(r.entry("../Game/Content/Hello.txt") != null, "hello entry");
        check(r.entry("../Game/Content/Big.bin").uncompressedSize == big.length, "big size field");
        check(Arrays.equals(r.extract(r.entry("../Game/Content/Hello.txt")), small), "hello bytes");
        check(Arrays.equals(r.extract(r.entry("../Game/Content/Big.bin")), big), "big bytes");
        check(Arrays.equals(r.extract(r.entry("../Game/Content/Ünicode.txt")), utf16), "unicode path bytes");

        // streaming extract via OutputStream
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        r.extract(r.entry("../Game/Content/Big.bin"), bos, null);
        check(Arrays.equals(bos.toByteArray(), big), "stream extract");

        // path listing sorted
        List<PakEntry> entries = r.entries();
        check(entries.size() == 3, "entries()");
        r.close();
    }

    static void testPakCompressed() throws Exception {
        System.out.println("[pak compressed]");
        File f = File.createTempFile("testc", ".pak");
        f.deleteOnExit();
        PakWriter w = new PakWriter("../");
        // highly compressible payload
        byte[] payload = new byte[2_000_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 16);
        w.add("../Game/Content/Compressed.dat", payload, true);
        long size = w.write(f.getAbsolutePath());

        PakReader r = PakReader.open(f.getAbsolutePath(), null);
        PakEntry e = r.entry("../Game/Content/Compressed.dat");
        check(e != null, "entry present");
        System.out.println("  method=" + e.compressionMethod + " blocks=" + e.blockCount()
                + " csize=" + e.compressedSize + " usize=" + e.uncompressedSize + " pak=" + size);
        check(e.isCompressed(), "entry is compressed");
        check(e.compressionMethod == 1, "zlib method id");
        byte[] got = r.extract(e);
        check(Arrays.equals(got, payload), "zlib extract roundtrip");
        r.close();
    }
}
