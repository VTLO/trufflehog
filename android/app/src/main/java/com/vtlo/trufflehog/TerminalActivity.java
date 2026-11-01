package com.vtlo.trufflehog;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A full terminal (PTY + VT emulator) for trufflehog's interactive features:
 * the no-argument wizard and `trufflehog analyze`. After the command exits
 * (or when opened without a command) it drops into /system/bin/sh with
 * `trufflehog` and `git` on PATH, so every CLI feature is reachable.
 */
public class TerminalActivity extends Activity implements TerminalSessionClient, TerminalViewClient {
    public static final String EXTRA_ARGS = "args";
    public static final String EXTRA_ENV = "env";
    private static final String TAG = "VTLOs3c";

    private TerminalView terminal;
    private TerminalSession session;
    private Button ctrlButton, altButton;
    private boolean ctrlDown, altDown;
    private float fontPx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            NativeEnv.setup(this);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Color.BLACK);

        terminal = new TerminalView(this, null);
        terminal.setTerminalViewClient(this);
        terminal.setTypeface(Typeface.MONOSPACE);
        fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics());
        terminal.setTextSize((int) fontPx);
        terminal.setFocusable(true);
        terminal.setFocusableInTouchMode(true);
        root.addView(terminal, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildExtraKeys(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        String[] args = getIntent().getStringArrayExtra(EXTRA_ARGS);
        String envText = getIntent().getStringExtra(EXTRA_ENV);
        session = createSession(args, envText);
        terminal.attachSession(session);
        terminal.requestFocus();
    }

    private TerminalSession createSession(String[] args, String envText) {
        String sh = "/system/bin/sh";
        String script;
        if (args != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add("trufflehog");
            cmd.addAll(Arrays.asList(args));
            script = ArgParser.join(cmd) + "; echo; echo \"[trufflehog exited with $?] -- shell (trufflehog, git on PATH)\"; exec " + sh + " -i";
        } else {
            script = "echo 'VTLOs3c shell -- run: trufflehog --help'; exec " + sh + " -i";
        }
        String[] argv = {"sh", "-c", script};
        String[] env = NativeEnv.environmentArray(this, envText == null ? null : ArgParser.parseEnv(envText));
        return new TerminalSession(sh, NativeEnv.home(this).getAbsolutePath(), argv, env, 5000, this);
    }

    private HorizontalScrollView buildExtraKeys() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addKey(row, "ESC", "\033");
        addKey(row, "TAB", "\t");
        ctrlButton = addKey(row, "CTRL", null);
        ctrlButton.setOnClickListener(v -> {
            ctrlDown = !ctrlDown;
            ctrlButton.setTextColor(ctrlDown ? getColor(R.color.accent) : Color.WHITE);
        });
        altButton = addKey(row, "ALT", null);
        altButton.setOnClickListener(v -> {
            altDown = !altDown;
            altButton.setTextColor(altDown ? getColor(R.color.accent) : Color.WHITE);
        });
        addKey(row, "←", "\033[D");
        addKey(row, "↓", "\033[B");
        addKey(row, "↑", "\033[A");
        addKey(row, "→", "\033[C");
        addKey(row, "⏎", "\r");
        addKey(row, "^C", "\003");
        addKey(row, "-", "-");
        addKey(row, "/", "/");
        addKey(row, "|", "|");
        Button paste = addKey(row, "PASTE", null);
        paste.setOnClickListener(v -> onPasteTextFromClipboard(session));
        Button kb = addKey(row, "⌨", null);
        kb.setOnClickListener(v -> showKeyboard());
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setBackgroundColor(0xFF1E1E1E);
        sv.addView(row);
        return sv;
    }

    private Button addKey(LinearLayout row, String label, String seq) {
        Button b = new Button(this, null, android.R.attr.buttonBarButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        if (seq != null) {
            b.setOnClickListener(v -> {
                if (session != null) session.write(seq.getBytes(), 0, seq.getBytes().length);
            });
        }
        row.addView(b);
        return b;
    }

    private void showKeyboard() {
        terminal.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    protected void onDestroy() {
        if (session != null) session.finishIfRunning();
        super.onDestroy();
    }

    // ---- TerminalSessionClient -----------------------------------------

    @Override
    public void onTextChanged(TerminalSession s) {
        terminal.onScreenUpdated();
    }

    @Override
    public void onTitleChanged(TerminalSession s) {}

    @Override
    public void onSessionFinished(TerminalSession s) {
        finish();
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession s, String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("trufflehog", text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession s) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || s == null) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text != null && s.getEmulator() != null) s.getEmulator().paste(text.toString());
    }

    @Override
    public void onBell(TerminalSession s) {}

    @Override
    public void onColorsChanged(TerminalSession s) {}

    @Override
    public void onTerminalCursorStateChange(boolean state) {}

    @Override
    public Integer getTerminalCursorStyle() {
        return null;
    }

    // ---- TerminalViewClient --------------------------------------------

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            fontPx = Math.max(12f, Math.min(80f, fontPx * scale));
            terminal.setTextSize((int) fontPx);
            return 1.0f;
        }
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        showKeyboard();
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return true;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {}

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession s) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        boolean d = ctrlDown;
        if (d) {
            ctrlDown = false;
            ctrlButton.setTextColor(Color.WHITE);
        }
        return d;
    }

    @Override
    public boolean readAltKey() {
        boolean d = altDown;
        if (d) {
            altDown = false;
            altButton.setTextColor(Color.WHITE);
        }
        return d;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession s) {
        return false;
    }

    @Override
    public void onEmulatorSet() {}

    // ---- logging (shared by both client interfaces) --------------------

    @Override
    public void logError(String tag, String message) {
        Log.e(TAG, tag + ": " + message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(TAG, tag + ": " + message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(TAG, tag + ": " + message);
    }

    @Override
    public void logDebug(String tag, String message) {}

    @Override
    public void logVerbose(String tag, String message) {}

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(TAG, tag + ": " + message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(TAG, tag, e);
    }
}
