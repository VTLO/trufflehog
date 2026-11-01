package com.vtlo.trufflehog;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity implements ScanRunner.Listener {
    private static final int REQ_FILE = 1;
    private static final int REQ_DIR = 2;
    private static final int REQ_SAVE = 3;
    private static final int MAX_VIEW_CHARS = 200_000;

    private Spinner preset;
    private TextView presetHelp, output, status;
    private EditText args, env, stdin;
    private CheckBox optJson, optVerified, optNoVerify, optArchive;
    private ScrollView outScroll;
    private View outputPanel, formScroll;
    private Button btnRun, btnStop;
    private SharedPreferences prefs;
    private boolean ignorePresetSelection = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("form", MODE_PRIVATE);

        preset = findViewById(R.id.preset);
        presetHelp = findViewById(R.id.presetHelp);
        args = findViewById(R.id.args);
        env = findViewById(R.id.env);
        stdin = findViewById(R.id.stdin);
        optJson = findViewById(R.id.optJson);
        optVerified = findViewById(R.id.optVerified);
        optNoVerify = findViewById(R.id.optNoVerify);
        optArchive = findViewById(R.id.optArchive);
        output = findViewById(R.id.output);
        status = findViewById(R.id.status);
        outScroll = findViewById(R.id.outScroll);
        outputPanel = findViewById(R.id.outputPanel);
        formScroll = findViewById(R.id.formScroll);
        btnRun = findViewById(R.id.btnRun);
        btnStop = findViewById(R.id.btnStop);

        ArrayAdapter<Presets.Preset> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, Presets.ALL);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        preset.setAdapter(adapter);
        preset.setSelection(prefs.getInt("preset", 0));
        presetHelp.setText(Presets.ALL[preset.getSelectedItemPosition()].help);
        args.setText(prefs.getString("args", Presets.ALL[preset.getSelectedItemPosition()].args));
        env.setText(prefs.getString("env", ""));
        optJson.setChecked(prefs.getBoolean("json", false));
        optVerified.setChecked(prefs.getBoolean("verified", false));
        optNoVerify.setChecked(prefs.getBoolean("noverify", false));
        optArchive.setChecked(prefs.getBoolean("archive", false));

        preset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Presets.Preset p = Presets.ALL[position];
                presetHelp.setText(p.help + (p.needsGit && !NativeEnv.hasGit(MainActivity.this)
                        ? "\n⚠ This build has no bundled git binary; this source will not work." : ""));
                if (ignorePresetSelection) {
                    ignorePresetSelection = false;
                    return;
                }
                args.setText(p.args);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnRun.setOnClickListener(v -> run(false));
        findViewById(R.id.btnHelp).setOnClickListener(v -> run(true));
        btnStop.setOnClickListener(v -> ScanRunner.get().stop());
        findViewById(R.id.btnToggle).setOnClickListener(v -> toggleOutput());
        findViewById(R.id.btnShare).setOnClickListener(v -> share());
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnPickFile).setOnClickListener(v -> pickFile());
        findViewById(R.id.btnPickDir).setOnClickListener(v -> pickDir());
        findViewById(R.id.btnStorage).setOnClickListener(v -> requestStorage());
        findViewById(R.id.btnTerminal).setOnClickListener(v -> openTerminal(null));

        new Thread(() -> {
            try {
                NativeEnv.setup(this);
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        for (int i = 0; i < Presets.ALL.length; i++) {
            if (Presets.ALL[i].args.equals("stdin")) {
                preset.setSelection(i);
                break;
            }
        }
        args.setText("stdin");
        stdin.setText(text);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ScanRunner r = ScanRunner.get();
        r.addListener(this);
        String snap = r.bufferSnapshot();
        output.setText(snap);
        if (!snap.isEmpty()) showOutput(true);
        onStateChanged(r.isRunning(), r.lastExit());
    }

    @Override
    protected void onStop() {
        ScanRunner.get().removeListener(this);
        saveForm();
        super.onStop();
    }

    private void saveForm() {
        prefs.edit()
                .putInt("preset", preset.getSelectedItemPosition())
                .putString("args", args.getText().toString())
                .putString("env", env.getText().toString())
                .putBoolean("json", optJson.isChecked())
                .putBoolean("verified", optVerified.isChecked())
                .putBoolean("noverify", optNoVerify.isChecked())
                .putBoolean("archive", optArchive.isChecked())
                .apply();
    }

    private List<String> buildArgs(boolean help) {
        List<String> a = new ArrayList<>(ArgParser.split(args.getText().toString()));
        if (help) {
            List<String> h = new ArrayList<>();
            if (!a.isEmpty() && !a.get(0).startsWith("-")) h.add(a.get(0));
            h.add("--help");
            return h;
        }
        a.add("--no-update");
        if (optJson.isChecked()) a.add("--json");
        if (optVerified.isChecked()) a.add("--results=verified");
        if (optNoVerify.isChecked()) a.add("--no-verification");
        if (optArchive.isChecked()) {
            a.add("--force-skip-archives");
            a.add("--force-skip-binaries");
        }
        return a;
    }

    private void run(boolean help) {
        saveForm();
        List<String> a = buildArgs(help);
        Map<String, String> e = ArgParser.parseEnv(env.getText().toString());
        // The analyze command and the no-argument wizard are interactive TUIs.
        boolean interactive = !help && (a.isEmpty() || a.get(0).equals("--no-update")
                || a.get(0).equals("analyze"));
        if (interactive) {
            openTerminal(a);
            return;
        }
        if (ScanRunner.get().isRunning()) {
            Toast.makeText(this, "A scan is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        output.setText("");
        showOutput(true);
        try {
            ScanRunner.get().start(this, a, e, stdin.getText().toString());
        } catch (Exception ex) {
            output.append("Failed to start trufflehog: " + ex.getMessage() + "\n");
        }
    }

    private void openTerminal(List<String> a) {
        Intent i = new Intent(this, TerminalActivity.class);
        if (a != null) {
            List<String> clean = new ArrayList<>(a);
            clean.remove("--no-update");
            i.putExtra(TerminalActivity.EXTRA_ARGS, clean.toArray(new String[0]));
            i.putExtra(TerminalActivity.EXTRA_ENV, env.getText().toString());
        }
        startActivity(i);
    }

    private void toggleOutput() {
        showOutput(outputPanel.getVisibility() != View.VISIBLE);
    }

    private void showOutput(boolean show) {
        outputPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        formScroll.setVisibility(show ? View.GONE : View.VISIBLE);
        ((Button) findViewById(R.id.btnToggle)).setText(show ? "Form" : "Output");
    }

    @Override
    public void onOutput(String chunk) {
        output.append(chunk);
        CharSequence t = output.getText();
        if (t.length() > MAX_VIEW_CHARS) {
            output.setText(t.subSequence(t.length() - MAX_VIEW_CHARS / 2, t.length()));
        }
        outScroll.post(() -> outScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onStateChanged(boolean running, Integer exitCode) {
        btnStop.setEnabled(running);
        btnRun.setEnabled(!running);
        status.setText(running ? "Running…" : exitCode == null ? "" : "Finished (exit " + exitCode + ")");
    }

    // ---- input helpers ------------------------------------------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_FILE);
    }

    private void pickDir() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DIR);
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "All-files access already granted", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 11);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_SAVE) {
            saveTo(data.getData());
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        final boolean dir = requestCode == REQ_DIR;
        Toast.makeText(this, "Importing…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            StringBuilder paths = new StringBuilder();
            for (Uri u : uris) {
                try {
                    String p = dir ? importTree(u) : importFile(u);
                    paths.append(' ').append(ArgParser.join(java.util.Collections.singletonList(p)));
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
            runOnUiThread(() -> {
                String cur = args.getText().toString().trim();
                if (cur.isEmpty() || !cur.startsWith("filesystem")) cur = "filesystem";
                args.setText(cur + paths);
            });
        }).start();
    }

    /** Copies a picked document into app storage so the native binary can read it. */
    private String importFile(Uri uri) throws IOException {
        File dir = new File(getCacheDir(), "imports/" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, displayName(uri, "file"));
        copy(getContentResolver(), uri, out);
        return out.getAbsolutePath();
    }

    private String importTree(Uri tree) throws IOException {
        // With all-files access, use the real path directly when it is on primary storage.
        String docId = DocumentsContract.getTreeDocumentId(tree);
        if (docId.startsWith("primary:") && hasAllFilesAccess()) {
            return Environment.getExternalStorageDirectory() + "/" + docId.substring("primary:".length());
        }
        File root = new File(getCacheDir(), "imports/" + System.currentTimeMillis());
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        copyTree(tree, docUri, new File(root, displayName(docUri, "folder")));
        return root.getAbsolutePath();
    }

    private void copyTree(Uri tree, Uri doc, File dest) throws IOException {
        ContentResolver cr = getContentResolver();
        //noinspection ResultOfMethodCallIgnored
        dest.mkdirs();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(doc));
        try (Cursor c = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                String name = c.getString(1).replace('/', '_');
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    copyTree(tree, child, new File(dest, name));
                } else {
                    copy(cr, child, new File(dest, name));
                }
            }
        }
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager()
                : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private String displayName(Uri uri, String fallback) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0).replace('/', '_');
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static void copy(ContentResolver cr, Uri from, File to) throws IOException {
        try (InputStream in = cr.openInputStream(from); OutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new IOException("cannot open " + from);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // ---- output helpers -----------------------------------------------

    private void share() {
        String text = ScanRunner.get().bufferSnapshot();
        if (text.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Share scan output"));
    }

    private void save() {
        File log = ScanRunner.get().logFile();
        if (log == null || !log.exists()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "trufflehog-scan.log");
        startActivityForResult(i, REQ_SAVE);
    }

    private void saveTo(Uri uri) {
        File log = ScanRunner.get().logFile();
        try (InputStream in = new FileInputStream(log); OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("cannot open destination");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            new AlertDialog.Builder(this).setMessage("Save failed: " + e.getMessage()).show();
        }
    }
}
