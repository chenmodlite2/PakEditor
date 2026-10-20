package com.chenmodlite.pakeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.chenmodlite.pakeditor.core.AppState;
import com.chenmodlite.pakeditor.ui.DirPicker;

public class MainActivity extends Activity {

    public static final String[] ENGINE_VERSIONS = {
            "UE 4.0", "UE 4.1", "UE 4.2", "UE 4.3", "UE 4.4", "UE 4.5", "UE 4.6", "UE 4.7", "UE 4.8",
            "UE 4.9", "UE 4.10", "UE 4.11", "UE 4.12", "UE 4.13", "UE 4.14", "UE 4.15", "UE 4.16",
            "UE 4.17", "UE 4.18", "UE 4.19", "UE 4.20", "UE 4.21", "UE 4.22", "UE 4.23", "UE 4.24",
            "UE 4.25", "UE 4.26", "UE 4.27",
            "UE 5.0", "UE 5.1", "UE 5.2", "UE 5.3", "UE 5.4", "UE 5.5", "UE 5.6"
    };

    private EditText etGameDir, etAesKey, etOutDir;
    private TextView tvStatus, tvArchivesSub, tvCookSub;
    private Spinner spinnerEngine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.chenmodlite.pakeditor.core.compress.OodleLoader.install();
        setContentView(R.layout.activity_main);

        etGameDir = findViewById(R.id.etGameDir);
        etAesKey = findViewById(R.id.etAesKey);
        etOutDir = findViewById(R.id.etOutDir);
        tvStatus = findViewById(R.id.tvStatus);
        tvArchivesSub = findViewById(R.id.tvArchivesSub);
        tvCookSub = findViewById(R.id.tvCookSub);
        spinnerEngine = findViewById(R.id.spinnerEngine);

        etAesKey.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(64)});

        ArrayAdapter<String> engineAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, ENGINE_VERSIONS);
        engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerEngine.setAdapter(engineAdapter);
        spinnerEngine.setSelection(ENGINE_VERSIONS.length - 4); // UE 5.3 default

        AppState.load(this);

        etGameDir.setText(AppState.gameDir);
        etAesKey.setText(AppState.aesKey);
        etOutDir.setText(AppState.outDir);
        for (int i = 0; i < ENGINE_VERSIONS.length; i++) {
            if (ENGINE_VERSIONS[i].equals(AppState.engineVersion)) {
                spinnerEngine.setSelection(i);
                break;
            }
        }
        refreshStatus();

        findViewById(R.id.btnPickDir).setOnClickListener(v ->
                DirPicker.pick(this, etGameDir.getText().toString(), "Game directory", path -> {
                    etGameDir.setText(path);
                    saveSettings(false);
                }));

        findViewById(R.id.btnPickOut).setOnClickListener(v ->
                DirPicker.pick(this, etOutDir.getText().toString(), "Output folder", path -> {
                    etOutDir.setText(path);
                    saveSettings(false);
                }));

        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> {
            saveSettings(true);
            refreshStatus();
        });

        findViewById(R.id.cardArchives).setOnClickListener(v -> openArchives());
        findViewById(R.id.btnOpenArchives).setOnClickListener(v -> openArchives());
        findViewById(R.id.cardCook).setOnClickListener(v -> openCook());
        findViewById(R.id.btnOpenCook).setOnClickListener(v -> openCook());
        findViewById(R.id.btnAbout).setOnClickListener(v -> showAbout());

        requestStorageAccess();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void openArchives() {
        saveSettings(false);
        if (AppState.gameDir == null || AppState.gameDir.trim().isEmpty()) {
            Toast.makeText(this, "Set the game directory first", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, BrowserActivity.class));
    }

    private void openCook() {
        saveSettings(false);
        startActivity(new Intent(this, CookActivity.class));
    }

    private void saveSettings(boolean toast) {
        AppState.gameDir = etGameDir.getText().toString().trim();
        AppState.aesKey = etAesKey.getText().toString().trim();
        AppState.outDir = etOutDir.getText().toString().trim();
        AppState.engineVersion = (String) spinnerEngine.getSelectedItem();
        AppState.save(this);
        if (toast) Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
    }

    public void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Game directory: ")
          .append(AppState.gameDir == null || AppState.gameDir.isEmpty() ? "not set" : AppState.gameDir)
          .append('\n');
        sb.append("AES key: ")
          .append(AppState.aesKey != null && AppState.aesKey.length() == 64 ? "loaded" : "not set")
          .append('\n');
        sb.append("Engine: ").append(AppState.engineVersion).append('\n');
        sb.append("Output: ").append(AppState.outDir);
        if (AppState.lastError != null && !AppState.lastError.isEmpty()) {
            sb.append("\n\nLast error: ").append(AppState.lastError);
        } else if (AppState.lastMessage != null && !AppState.lastMessage.isEmpty()) {
            sb.append("\n\n").append(AppState.lastMessage);
        }
        tvStatus.setText(sb.toString());

        int fileCount = AppState.knownFileCount;
        tvArchivesSub.setText(fileCount > 0
                ? (fileCount + " entries indexed in " + AppState.loadedArchive
                   + ". Browse, extract, replace and export files from .pak and .utoc/.ucas containers.")
                : "Browse, extract, replace and export files from .pak and .utoc/.ucas containers.");
        int edited = AppState.editedAssetCount;
        tvCookSub.setText(edited > 0
                ? (edited + " edited assets waiting. Pack them into a loadable mod (.pak, and .utoc/.ucas for IoStore games).")
                : "Pack edited assets into a loadable mod (.pak, and .utoc/.ucas for IoStore games).");
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this)
                    .setTitle("All files access")
                    .setMessage("PakEditor needs access to the whole storage to read game archives and write extracted files.")
                    .setPositiveButton("Grant", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("Later", null)
                    .show();
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("PakEditor for Android")
                .setMessage("Port of PakEditor-FM (jesse-james522/PakEditor, GPL-3.0)\n\n"
                        + "FModel — GPL-3.0\nCUE4Parse — Apache-2.0\nUAssetAPI — MIT\n"
                        + "retoc — MIT\nUAssetGUI — MIT (Windows-only, replaced by the built-in editor)\n\n"
                        + "Engine: pak/IoStore browse, extract, repack, asset edit, cook.")
                .setPositiveButton("Close", null)
                .show();
    }
}
