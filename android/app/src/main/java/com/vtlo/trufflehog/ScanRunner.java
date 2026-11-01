package com.vtlo.trufflehog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs one trufflehog process at a time (non-interactive mode), streams its
 * merged stdout/stderr to listeners on the main thread and keeps the full
 * output in a log file for saving/sharing.
 */
public final class ScanRunner {
    public interface Listener {
        void onOutput(String chunk);

        void onStateChanged(boolean running, Integer exitCode);
    }

    private static final int MAX_BUFFER = 512 * 1024;
    private static ScanRunner instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder buffer = new StringBuilder();
    private final List<Listener> listeners = new ArrayList<>();
    private Process process;
    private Integer lastExit;
    private File logFile;

    public static synchronized ScanRunner get() {
        if (instance == null) instance = new ScanRunner();
        return instance;
    }

    public synchronized boolean isRunning() {
        return process != null;
    }

    public synchronized String bufferSnapshot() {
        return buffer.toString();
    }

    public File logFile() {
        return logFile;
    }

    public void addListener(Listener l) {
        synchronized (this) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        synchronized (this) {
            listeners.remove(l);
        }
    }

    public synchronized void clear() {
        buffer.setLength(0);
    }

    /**
     * @param args  trufflehog arguments (without the program name)
     * @param env   extra environment variables
     * @param stdin optional text piped to the process' stdin
     */
    public synchronized void start(Context ctx, List<String> args, Map<String, String> env, String stdin)
            throws IOException {
        if (process != null) throw new IllegalStateException("a scan is already running");
        NativeEnv.setup(ctx);
        List<String> cmd = new ArrayList<>();
        cmd.add(NativeEnv.trufflehog(ctx).getAbsolutePath());
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(NativeEnv.home(ctx));
        pb.environment().clear();
        pb.environment().putAll(NativeEnv.environment(ctx, env));

        logFile = new File(ctx.getCacheDir(), "last-scan.log");
        buffer.setLength(0);
        lastExit = null;
        append("$ trufflehog " + ArgParser.join(args) + "\n");
        process = pb.start();
        final Process p = process;
        final FileOutputStream log = new FileOutputStream(logFile);
        log.write(("$ trufflehog " + ArgParser.join(args) + "\n").getBytes(StandardCharsets.UTF_8));

        Thread writer = new Thread(() -> {
            try (OutputStream os = p.getOutputStream()) {
                if (stdin != null && !stdin.isEmpty()) os.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }, "trufflehog-stdin");
        writer.start();

        Thread reader = new Thread(() -> {
            byte[] buf = new byte[8192];
            try (InputStream is = p.getInputStream()) {
                int n;
                while ((n = is.read(buf)) > 0) {
                    log.write(buf, 0, n);
                    append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
            int code;
            try {
                code = p.waitFor();
            } catch (InterruptedException e) {
                code = -1;
            }
            try {
                log.close();
            } catch (IOException ignored) {
            }
            finished(p, code);
        }, "trufflehog-output");
        reader.start();
        notifyState(true, null);
        ScanService.start(ctx, "trufflehog " + (args.isEmpty() ? "" : args.get(0)));
    }

    public synchronized void stop() {
        if (process != null) {
            process.destroy();
            final Process p = process;
            main.postDelayed(() -> {
                if (p.isAlive()) p.destroyForcibly();
            }, 3000);
        }
    }

    private void finished(Process p, int code) {
        synchronized (this) {
            if (process == p) process = null;
            lastExit = code;
        }
        append("\n[process exited with code " + code + "]\n");
        notifyState(false, code);
        ScanService.stop();
    }

    private void append(String s) {
        synchronized (this) {
            buffer.append(s);
            if (buffer.length() > MAX_BUFFER) {
                buffer.delete(0, buffer.length() - MAX_BUFFER);
            }
        }
        main.post(() -> {
            for (Listener l : snapshot()) l.onOutput(s);
        });
    }

    private void notifyState(boolean running, Integer exit) {
        main.post(() -> {
            for (Listener l : snapshot()) l.onStateChanged(running, exit);
        });
    }

    private synchronized List<Listener> snapshot() {
        return new ArrayList<>(listeners);
    }

    public synchronized Integer lastExit() {
        return lastExit;
    }
}
