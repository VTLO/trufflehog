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
 * Runs trufflehog processes (non-interactive mode), one at a time or as a queue, streams its
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
    private boolean running;
    private boolean cancelled;
    private Integer lastExit;
    private File logFile;

    public static synchronized ScanRunner get() {
        if (instance == null) instance = new ScanRunner();
        return instance;
    }

    public synchronized boolean isRunning() {
        return running;
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

    /** Runs a single trufflehog invocation. */
    public void start(Context ctx, List<String> args, Map<String, String> env, String stdin) throws IOException {
        List<List<String>> one = new ArrayList<>();
        one.add(args);
        startQueue(ctx, one, env, stdin);
    }

    /**
     * Runs several trufflehog invocations one after another, with their output
     * concatenated. Stop cancels the current one and everything queued after it.
     *
     * @param commands trufflehog arguments for each run (without the program name)
     * @param env      extra environment variables
     * @param stdin    optional text piped to each process' stdin
     */
    public synchronized void startQueue(Context ctx, List<List<String>> commands, Map<String, String> env,
                                        String stdin) throws IOException {
        if (running) throw new IllegalStateException("a scan is already running");
        if (commands.isEmpty()) throw new IllegalArgumentException("nothing to scan");
        NativeEnv.setup(ctx);
        final Context app = ctx.getApplicationContext();
        logFile = new File(ctx.getCacheDir(), "last-scan.log");
        final FileOutputStream log = new FileOutputStream(logFile);
        buffer.setLength(0);
        lastExit = null;
        cancelled = false;
        running = true;
        notifyState(true, null);
        ScanService.start(ctx, "trufflehog " + (commands.get(0).isEmpty() ? "" : commands.get(0).get(0))
                + (commands.size() > 1 ? " (" + commands.size() + " scans)" : ""));

        new Thread(() -> {
            int worst = 0;
            for (int i = 0; i < commands.size(); i++) {
                synchronized (this) {
                    if (cancelled) break;
                }
                List<String> args = commands.get(i);
                String header = (commands.size() > 1 ? "\n=== [" + (i + 1) + "/" + commands.size() + "] " : "")
                        + "$ trufflehog " + ArgParser.join(args) + "\n";
                emit(log, header);
                int code;
                try {
                    code = runOne(app, args, env, stdin, log);
                } catch (IOException e) {
                    emit(log, "Failed to start trufflehog: " + e.getMessage() + "\n");
                    code = -1;
                }
                if (commands.size() > 1) emit(log, "[exit code " + code + "]\n");
                if (code != 0 && worst == 0) worst = code;
            }
            try {
                log.close();
            } catch (IOException ignored) {
            }
            boolean wasCancelled;
            synchronized (this) {
                running = false;
                process = null;
                lastExit = worst;
                wasCancelled = cancelled;
            }
            append("\n[" + (wasCancelled ? "stopped" : "finished") + ", exit code " + worst + "]\n");
            notifyState(false, worst);
            ScanService.stop();
        }, "trufflehog-runner").start();
    }

    private int runOne(Context ctx, List<String> args, Map<String, String> env, String stdin,
                       FileOutputStream log) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(NativeEnv.trufflehog(ctx).getAbsolutePath());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(NativeEnv.home(ctx));
        pb.environment().clear();
        pb.environment().putAll(NativeEnv.environment(ctx, env));

        final Process p;
        synchronized (this) {
            if (cancelled) return -1;
            p = pb.start();
            process = p;
        }
        Thread writer = new Thread(() -> {
            try (OutputStream os = p.getOutputStream()) {
                if (stdin != null && !stdin.isEmpty()) os.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }, "trufflehog-stdin");
        writer.start();

        byte[] buf = new byte[8192];
        try (InputStream is = p.getInputStream()) {
            int n;
            while ((n = is.read(buf)) > 0) {
                log.write(buf, 0, n);
                append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            return -1;
        }
    }

    private void emit(FileOutputStream log, String s) {
        try {
            log.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        append(s);
    }

    public synchronized void stop() {
        cancelled = true;
        if (process != null) {
            process.destroy();
            final Process p = process;
            main.postDelayed(() -> {
                if (p.isAlive()) p.destroyForcibly();
            }, 3000);
        }
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
