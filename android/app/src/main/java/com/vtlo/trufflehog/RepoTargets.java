package com.vtlo.trufflehog;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the URLs typed in the "Online repos" tab into trufflehog commands.
 *
 * <ul>
 *   <li>{@code owner/repo} is shorthand for a GitHub repository.</li>
 *   <li>A GitHub repo is scanned with the {@code git} source (full history, no API
 *       calls). It uses the {@code github} source instead when a GitHub token is set
 *       (private repos) or when issue/PR comments are requested.</li>
 *   <li>A GitHub user/org URL ({@code https://github.com/name}) scans all its repos
 *       with {@code github --org}.</li>
 *   <li>A GitLab repo uses the {@code gitlab} source when a GitLab token is set,
 *       the {@code git} source otherwise.</li>
 *   <li>Hugging Face model/dataset/space URLs use the {@code huggingface} source.</li>
 *   <li>Anything else (any https://, ssh://, git@ or file:// git URL) uses the
 *       {@code git} source.</li>
 * </ul>
 */
final class RepoTargets {
    static final class Options {
        boolean hasGithubToken;
        boolean hasGitlabToken;
        boolean comments;
        String branch = "";
        String maxDepth = "";
        String sinceCommit = "";
    }

    static final class Target {
        final String input;
        final List<String> args;
        final String error;

        Target(String input, List<String> args, String error) {
            this.input = input;
            this.args = args;
            this.error = error;
        }
    }

    private static final Pattern SHORTHAND = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern SCP_LIKE = Pattern.compile("^[\\w.-]+@[\\w.-]+:.+$");

    private RepoTargets() {}

    static List<Target> parse(String text, Options o) {
        List<Target> out = new ArrayList<>();
        for (String raw : text.split("[\\n,]")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(parseOne(line, o));
        }
        return out;
    }

    static Target parseOne(String input, Options o) {
        String url = input;
        if (SHORTHAND.matcher(url).matches()) {
            url = "https://github.com/" + url;
        } else if (url.matches("^(github\\.com|gitlab\\.com|bitbucket\\.org|huggingface\\.co|codeberg\\.org)/.*")) {
            url = "https://" + url;
        }

        if (SCP_LIKE.matcher(url).matches() || url.startsWith("ssh://")) {
            return git(input, url, o);
        }
        if (url.startsWith("file://")) {
            return git(input, url, o);
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return new Target(input, null, "not a URL (expected https://…, ssh://…, git@host:path or owner/repo)");
        }

        URI u;
        try {
            u = new URI(url);
        } catch (URISyntaxException e) {
            return new Target(input, null, "invalid URL: " + e.getReason());
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return new Target(input, null, "missing host");
        List<String> seg = new ArrayList<>();
        if (u.getPath() != null) {
            for (String p : u.getPath().split("/")) if (!p.isEmpty()) seg.add(p);
        }

        if (host.equals("github.com") || host.equals("www.github.com")) {
            if (seg.isEmpty()) return new Target(input, null, "missing owner/repo");
            if (seg.size() == 1) {
                // A user or organization: scan all of its repositories.
                List<String> a = new ArrayList<>();
                a.add("github");
                a.add("--org=" + seg.get(0));
                if (o.comments) addComments(a);
                return new Target(input, a, null);
            }
            String repoUrl = "https://github.com/" + seg.get(0) + "/" + stripGit(seg.get(1)) + ".git";
            if (o.hasGithubToken || o.comments) {
                List<String> a = new ArrayList<>();
                a.add("github");
                a.add("--repo=" + repoUrl);
                if (o.comments) addComments(a);
                return new Target(input, a, null);
            }
            return git(input, repoUrl, o);
        }

        if (host.equals("gitlab.com") && seg.size() >= 2 && o.hasGitlabToken) {
            List<String> a = new ArrayList<>();
            a.add("gitlab");
            a.add("--repo=" + gitlabRepoUrl(seg));
            return new Target(input, a, null);
        }
        if (host.equals("gitlab.com") && seg.size() >= 2) {
            return git(input, gitlabRepoUrl(seg), o);
        }

        if (host.equals("huggingface.co") || host.equals("www.huggingface.co")) {
            return huggingface(input, seg);
        }

        if ((host.equals("bitbucket.org") || host.equals("codeberg.org")) && seg.size() >= 2) {
            return git(input, "https://" + host + "/" + seg.get(0) + "/" + stripGit(seg.get(1)) + ".git", o);
        }

        return git(input, url, o);
    }

    private static Target git(String input, String url, Options o) {
        List<String> a = new ArrayList<>();
        a.add("git");
        a.add(url);
        if (!o.branch.isEmpty()) a.add("--branch=" + o.branch);
        if (!o.maxDepth.isEmpty()) a.add("--max-depth=" + o.maxDepth);
        if (!o.sinceCommit.isEmpty()) a.add("--since-commit=" + o.sinceCommit);
        return new Target(input, a, null);
    }

    private static Target huggingface(String input, List<String> seg) {
        if (seg.isEmpty()) return new Target(input, null, "missing Hugging Face repository");
        String kind = "--model=";
        List<String> rest = seg;
        if (seg.get(0).equals("datasets") || seg.get(0).equals("spaces")) {
            kind = seg.get(0).equals("datasets") ? "--dataset=" : "--space=";
            rest = seg.subList(1, seg.size());
        }
        List<String> a = new ArrayList<>();
        a.add("huggingface");
        if (rest.size() == 1) {
            a.add("--org=" + rest.get(0));
        } else if (rest.size() >= 2) {
            a.add(kind + rest.get(0) + "/" + rest.get(1));
        } else {
            return new Target(input, null, "missing Hugging Face repository");
        }
        return new Target(input, a, null);
    }

    /** Drops GitLab UI suffixes such as /-/tree/main so only the project path is kept. */
    private static String gitlabRepoUrl(List<String> seg) {
        StringBuilder sb = new StringBuilder("https://gitlab.com");
        for (String s : seg) {
            if (s.equals("-")) break;
            sb.append('/').append(s);
        }
        String path = sb.toString();
        return path.endsWith(".git") ? path : path + ".git";
    }

    private static void addComments(List<String> a) {
        a.add("--issue-comments");
        a.add("--pr-comments");
    }

    private static String stripGit(String s) {
        return s.endsWith(".git") ? s.substring(0, s.length() - 4) : s;
    }

    /** True when the URL list needs the bundled git binary (every source here clones with git). */
    static boolean needsGit(List<Target> targets) {
        for (Target t : targets) {
            if (t.args != null) return true;
        }
        return false;
    }

    static String describe(Target t) {
        if (t.error != null) return "✗ " + t.input + " — " + t.error;
        return "• " + t.input + "\n    → trufflehog " + ArgParser.join(t.args);
    }
}
