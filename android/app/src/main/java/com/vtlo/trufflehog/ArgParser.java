package com.vtlo.trufflehog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal POSIX-shell-like argument splitting (quotes and backslashes). */
public final class ArgParser {
    private ArgParser() {}

    public static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inArg = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < s.length()) {
                    cur.append(s.charAt(++i));
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inArg = true;
            } else if (c == '\\' && i + 1 < s.length()) {
                cur.append(s.charAt(++i));
                inArg = true;
            } else if (Character.isWhitespace(c)) {
                if (inArg) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inArg = false;
                }
            } else {
                cur.append(c);
                inArg = true;
            }
        }
        if (inArg) out.add(cur.toString());
        return out;
    }

    public static String join(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (sb.length() > 0) sb.append(' ');
            if (a.isEmpty() || a.matches(".*[\\s'\"\\\\$`].*")) {
                sb.append('\'').append(a.replace("'", "'\\''")).append('\'');
            } else {
                sb.append(a);
            }
        }
        return sb.toString();
    }

    /** Parses KEY=VALUE lines; blank lines and lines starting with # are ignored. */
    public static Map<String, String> parseEnv(String text) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring(7).trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String v = line.substring(eq + 1).trim();
            if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            env.put(line.substring(0, eq).trim(), v);
        }
        return env;
    }
}
