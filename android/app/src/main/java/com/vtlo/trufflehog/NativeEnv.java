package com.vtlo.trufflehog;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prepares the runtime environment for the bundled native executables.
 *
 * The trufflehog CLI (and optionally git) ship as lib*.so files so Android
 * extracts them into nativeLibraryDir, the only place a modern app may exec
 * binaries from. This class symlinks them under their real names into
 * filesDir/bin (on PATH) and exports a CA bundle built from the device trust
 * store (system + user-installed CAs).
 */
public final class NativeEnv {
    private static final String TRUFFLEHOG_LIB = "libtrufflehog.so";
    private static final String GIT_LIB = "libgit.so";
    private static final String GIT_HTTP_LIB = "libgit-remote-http.so";

    private NativeEnv() {}

    public static File nativeDir(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    public static File trufflehog(Context ctx) {
        return new File(nativeDir(ctx), TRUFFLEHOG_LIB);
    }

    public static boolean hasGit(Context ctx) {
        return new File(nativeDir(ctx), GIT_LIB).exists();
    }

    public static File home(Context ctx) {
        return new File(ctx.getFilesDir(), "home");
    }

    public static File binDir(Context ctx) {
        return new File(ctx.getFilesDir(), "bin");
    }

    public static File gitExecDir(Context ctx) {
        return new File(ctx.getFilesDir(), "libexec/git-core");
    }

    public static File tmpDir(Context ctx) {
        return new File(ctx.getCacheDir(), "tmp");
    }

    public static File caBundle(Context ctx) {
        return new File(ctx.getFilesDir(), "cacert.pem");
    }

    /** Idempotently creates directories, symlinks and the CA bundle. */
    public static synchronized void setup(Context ctx) throws IOException {
        File nd = nativeDir(ctx);
        mkdirs(home(ctx));
        mkdirs(binDir(ctx));
        mkdirs(tmpDir(ctx));
        mkdirs(new File(ctx.getFilesDir(), "git-templates"));

        link(new File(nd, TRUFFLEHOG_LIB), new File(binDir(ctx), "trufflehog"));
        if (hasGit(ctx)) {
            mkdirs(gitExecDir(ctx));
            link(new File(nd, GIT_LIB), new File(binDir(ctx), "git"));
            link(new File(nd, GIT_LIB), new File(gitExecDir(ctx), "git"));
            link(new File(nd, GIT_HTTP_LIB), new File(gitExecDir(ctx), "git-remote-http"));
            link(new File(nd, GIT_HTTP_LIB), new File(gitExecDir(ctx), "git-remote-https"));
            link(new File(nd, GIT_HTTP_LIB), new File(gitExecDir(ctx), "git-remote-ftp"));
            link(new File(nd, GIT_HTTP_LIB), new File(gitExecDir(ctx), "git-remote-ftps"));
        }
        writeCaBundle(caBundle(ctx));
        writeGitConfig(new File(home(ctx), ".gitconfig"));
    }

    /** The environment passed to every spawned process. */
    public static Map<String, String> environment(Context ctx, Map<String, String> extra) {
        Map<String, String> env = new LinkedHashMap<>();
        String sysPath = System.getenv("PATH");
        env.put("PATH", binDir(ctx).getAbsolutePath() + ":" + (sysPath != null ? sysPath : "/system/bin:/system/xbin"));
        env.put("HOME", home(ctx).getAbsolutePath());
        env.put("TMPDIR", tmpDir(ctx).getAbsolutePath());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG", "en_US.UTF-8");
        env.put("SSL_CERT_FILE", caBundle(ctx).getAbsolutePath());
        env.put("GIT_SSL_CAINFO", caBundle(ctx).getAbsolutePath());
        env.put("GIT_EXEC_PATH", gitExecDir(ctx).getAbsolutePath());
        env.put("GIT_TEMPLATE_DIR", new File(ctx.getFilesDir(), "git-templates").getAbsolutePath());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_TERMINAL_PROMPT", "0");
        // Keep variables Android itself relies on.
        for (String k : new String[]{"ANDROID_ROOT", "ANDROID_DATA", "ANDROID_RUNTIME_ROOT",
                "ANDROID_TZDATA_ROOT", "ANDROID_I18N_ROOT", "ANDROID_ART_ROOT",
                "BOOTCLASSPATH", "EXTERNAL_STORAGE", "ANDROID_STORAGE"}) {
            String v = System.getenv(k);
            if (v != null) env.put(k, v);
        }
        if (extra != null) env.putAll(extra);
        return env;
    }

    public static String[] environmentArray(Context ctx, Map<String, String> extra) {
        Map<String, String> env = environment(ctx, extra);
        String[] out = new String[env.size()];
        int i = 0;
        for (Map.Entry<String, String> e : env.entrySet()) out[i++] = e.getKey() + "=" + e.getValue();
        return out;
    }

    private static void mkdirs(File f) throws IOException {
        if (!f.isDirectory() && !f.mkdirs()) throw new IOException("cannot create " + f);
    }

    private static void link(File target, File link) throws IOException {
        try {
            String current = null;
            try {
                current = Os.readlink(link.getAbsolutePath());
            } catch (Exception ignored) {
            }
            if (target.getAbsolutePath().equals(current)) return;
            //noinspection ResultOfMethodCallIgnored
            link.delete();
            Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        } catch (Exception e) {
            throw new IOException("symlink " + link + " -> " + target + ": " + e.getMessage(), e);
        }
    }

    private static void writeCaBundle(File out) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            ks.load(null, null);
            StringBuilder sb = new StringBuilder();
            Enumeration<String> aliases = ks.aliases();
            for (String alias : Collections.list(aliases)) {
                Certificate c = ks.getCertificate(alias);
                if (c == null) continue;
                sb.append("-----BEGIN CERTIFICATE-----\n");
                sb.append(Base64.encodeToString(c.getEncoded(), Base64.DEFAULT));
                sb.append("-----END CERTIFICATE-----\n");
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            }
        } catch (Exception ignored) {
            // Go falls back to /system/etc/security/cacerts on Android.
        }
    }

    private static void writeGitConfig(File out) throws IOException {
        if (out.exists()) return;
        String cfg = "[safe]\n\tdirectory = *\n[core]\n\tfileMode = false\n[http]\n\tsslCAInfo = "
                + out.getParentFile().getParentFile().getAbsolutePath() + "/cacert.pem\n";
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(cfg.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String abiSummary() {
        return Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?";
    }
}
