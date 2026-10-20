import com.chenmodlite.pakeditor.core.iostore.IoStoreTestData;
import com.chenmodlite.pakeditor.core.iostore.UtocReader;

import java.io.File;
import java.util.Arrays;
import java.util.Random;

/** Round-trip test for the IoStore reader against a synthetic uncompressed container. */
public class IoStoreTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        testRoundTrip();
        System.out.println(failures == 0 ? "IOSTORE ALL OK" : (failures + " FAILURES"));
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(boolean cond, String what) {
        if (cond) System.out.println("  ok: " + what);
        else { System.out.println("  FAIL: " + what); failures++; }
    }

    static void testRoundTrip() throws Exception {
        System.out.println("[iostore roundtrip]");
        File dir = new File(System.getProperty("java.io.tmpdir"), "utoc-test-" + System.nanoTime());
        dir.mkdirs();
        String utoc = new File(dir, "test.utoc").getAbsolutePath();

        byte[] small = "hello iostore world\n".getBytes("UTF-8");
        byte[] big = new byte[5 * 1024 * 1024 + 321];
        new Random(7).nextBytes(big);
        byte[] zeroes = new byte[300_000];

        IoStoreTestData w = new IoStoreTestData("../../../", 64 * 1024);
        w.add("../Game/Content/Hello.uasset", small, 2);
        w.add("../Game/Content/Big.ubulk", big, 3);
        w.add("../Game/Content/Zeroes.uasset", zeroes, 2);
        w.write(utoc);
        System.out.println("  wrote " + new File(utoc).length() + " byte utoc + "
                + new File(utoc.substring(0, utoc.length() - 5) + ".ucas").length() + " byte ucas");

        UtocReader r = UtocReader.open(utoc, null);
        System.out.println("  version=" + r.version + " chunks=" + r.chunkCount() + " blocks=" + r.blockEntryCount
                + " files=" + r.fileCount() + " mount=" + r.mountPoint() + " flags=[" + r.flagsString() + "]"
                + " parts=" + r.partitionCount + " blockSize=" + r.compressionBlockSize);
        check(r.version == 3, "toc version");
        check(r.fileCount() == 3, "directory index file count");
        check(r.mountPoint().equals("../../../"), "mount point");
        check(r.chunkCount() == 3, "chunk count");
        check(r.blockEntryCount == 87, "block count (1 + 81 for 5MB + 5 for 300k)");

        UtocReader.FileEntry e1 = r.fileByDisplayPath("Game/Content/Hello.uasset");
        check(e1 != null, "hello entry by display path");
        check(e1 != null && "ExportBundleData".equals(e1.chunkType), "chunk type ExportBundleData");
        check(Arrays.equals(r.extractFile(e1), small), "hello bytes");

        UtocReader.FileEntry e2 = r.fileByDisplayPath("Game/Content/Big.ubulk");
        check(e2 != null && "BulkData".equals(e2.chunkType), "bulk data chunk type");
        check(Arrays.equals(r.extractFile(e2), big), "big bytes (multi block)");

        UtocReader.FileEntry e3 = r.fileByDisplayPath("Game/Content/Zeroes.uasset");
        check(Arrays.equals(r.extractFile(e3), zeroes), "zeroes bytes");

        // chunk-extract variant
        check(Arrays.equals(r.extractChunk(e2.tocIndex), big), "extractChunk matches");

        // method name lookups
        check("None".equals(r.compressionMethodOf(e1.tocIndex)), "compression method None");
        r.close();
        deleteRec(dir);
    }

    static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }
}
