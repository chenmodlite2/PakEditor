package com.chenmodlite.pakeditor.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Simple in-app directory browser over raw filesystem paths. */
public final class DirPicker {

    public interface Callback { void onPicked(String path); }

    private DirPicker() {}

    public static void pick(Activity host, String startPath, String title, Callback cb) {
        File start = startPath == null || startPath.trim().isEmpty() ? new File("/") : new File(startPath.trim());
        if (!start.isDirectory()) start = new File("/");
        showLevel(host, start, title, cb);
    }

    private static void showLevel(final Activity host, final File dir, final String title, final Callback cb) {
        final File[] parentHolder = {dir.getParentFile()};
        final File[] current = {dir};

        LinearLayout layout = new LinearLayout(host);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 8);

        final TextView breadcrumb = new TextView(host);
        breadcrumb.setText(current[0].getAbsolutePath());
        breadcrumb.setTextColor(0xFF8A94A3);
        breadcrumb.setTextSize(12);
        breadcrumb.setSingleLine(true);
        breadcrumb.setEllipsize(android.text.TextUtils.TruncateAt.START);
        layout.addView(breadcrumb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final List<String> entries = new ArrayList<>();
        final List<File> files = new ArrayList<>();
        refresh(dir, entries, files);

        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(host, android.R.layout.simple_list_item_1, entries) {
            @Override
            public android.view.View getView(int position, android.view.View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTypeface(Typeface.MONOSPACE, position == 0 && entries.get(0).startsWith("..") ? Typeface.NORMAL : Typeface.NORMAL);
                tv.setTextColor(0xFFE6EAF0);
                return tv;
            }
        };

        ListView list = new ListView(host);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, position, id) -> {
            if (position == 0 && entries.get(0).startsWith("..")) {
                File up = current[0].getParentFile();
                if (up != null) showLevel(host, up, title, cb);
                return;
            }
            File picked = files.get(position);
            if (picked.isDirectory()) {
                showLevel(host, picked, title, cb);
            } else {
                cb.onPicked(picked.getAbsolutePath());
            }
        });
        layout.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton("Use this folder", (d, w) -> {
                    cb.onPicked(current[0].getAbsolutePath());
                })
                .setNeutralButton("Up", (d, w) -> {
                    File up = current[0].getParentFile();
                    if (up != null) showLevel(host, up, title, cb);
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
    }

    private static void refresh(File dir, List<String> entries, List<File> files) {
        entries.clear();
        files.clear();
        entries.add("../  up");
        files.add(dir.getParentFile());
        File[] kids = dir.listFiles();
        if (kids != null) {
            List<File> dirs = new ArrayList<>();
            List<File> regular = new ArrayList<>();
            for (File f : kids) {
                if (f.isDirectory() && !f.getName().startsWith(".")) dirs.add(f);
                else if (f.isFile() && !f.getName().startsWith(".")) regular.add(f);
            }
            Collections.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            Collections.sort(regular, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : dirs) { entries.add("▸ " + f.getName() + "/"); files.add(f); }
            for (File f : regular) { entries.add("   " + f.getName() + "  (" + human(f.length()) + ")"); files.add(f); }
        }
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0);
    }
}
