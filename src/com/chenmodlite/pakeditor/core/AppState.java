package com.chenmodlite.pakeditor.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Shared app state: settings, loaded archive summary, session messages. */
public final class AppState {

    public static final String PREFS = "pakeditor";
    public static final String DEFAULT_STORAGE = "/storage/emulated/0";

    public static String gameDir = "";
    public static String aesKey = "";
    public static String outDir = DEFAULT_STORAGE + "/PakEditor";
    public static String engineVersion = "UE 5.3";
    public static String editedAssetsDir = DEFAULT_STORAGE + "/PakEditor/EditedAssets";

    public static String loadedArchive = "";
    public static int knownFileCount = 0;
    public static int editedAssetCount = 0;

    public static String lastMessage = "";
    public static String lastError = "";

    private AppState() {}

    public static void load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        gameDir = p.getString("gameDir", "");
        aesKey = p.getString("aesKey", "");
        outDir = p.getString("outDir", DEFAULT_STORAGE + "/PakEditor");
        engineVersion = p.getString("engineVersion", "UE 5.3");
        editedAssetsDir = p.getString("editedAssetsDir", outDir + "/EditedAssets");
        refreshEditedCount();
    }

    public static void save(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("gameDir", gameDir)
                .putString("aesKey", aesKey)
                .putString("outDir", outDir)
                .putString("engineVersion", engineVersion)
                .putString("editedAssetsDir", editedAssetsDir)
                .apply();
    }

    public static void setMessage(String msg) { lastMessage = msg; lastError = ""; }
    public static void setError(String err) { lastError = err; }

    public static void refreshEditedCount() {
        File dir = new File(editedAssetsDir);
        editedAssetCount = countFiles(dir);
    }

    private static int countFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        int n = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File f : kids) n += f.isDirectory() ? countFiles(f) : 1;
        return n;
    }

    public static List<String> recentMessages(int max) {
        return new ArrayList<>();
    }
}
