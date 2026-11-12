package com.vtlo.trufflehog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class RepoTargetsTest {
    private static List<String> args(String input, RepoTargets.Options o) {
        RepoTargets.Target t = RepoTargets.parseOne(input, o);
        assertNull(t.error, t.error);
        return t.args;
    }

    private static List<String> args(String input) {
        return args(input, new RepoTargets.Options());
    }

    @Test
    public void githubRepoUsesGitSourceWithoutToken() {
        assertEquals(Arrays.asList("git", "https://github.com/trufflesecurity/test_keys.git"),
                args("https://github.com/trufflesecurity/test_keys"));
    }

    @Test
    public void githubWebUrlSuffixIsDropped() {
        assertEquals(Arrays.asList("git", "https://github.com/o/r.git"),
                args("https://github.com/o/r/tree/main/src"));
        assertEquals(Arrays.asList("git", "https://github.com/o/r.git"), args("https://github.com/o/r.git"));
    }

    @Test
    public void shorthandAndSchemelessUrls() {
        assertEquals(Arrays.asList("git", "https://github.com/o/r.git"), args("o/r"));
        assertEquals(Arrays.asList("git", "https://github.com/o/r.git"), args("github.com/o/r"));
    }

    @Test
    public void githubTokenOrCommentsUseGithubSource() {
        RepoTargets.Options o = new RepoTargets.Options();
        o.hasGithubToken = true;
        assertEquals(Arrays.asList("github", "--repo=https://github.com/o/r.git"), args("https://github.com/o/r", o));

        RepoTargets.Options c = new RepoTargets.Options();
        c.comments = true;
        assertEquals(Arrays.asList("github", "--repo=https://github.com/o/r.git", "--issue-comments", "--pr-comments"),
                args("https://github.com/o/r", c));
    }

    @Test
    public void githubOrg() {
        assertEquals(Arrays.asList("github", "--org=trufflesecurity"), args("https://github.com/trufflesecurity"));
    }

    @Test
    public void gitlab() {
        assertEquals(Arrays.asList("git", "https://gitlab.com/group/sub/project.git"),
                args("https://gitlab.com/group/sub/project/-/tree/main"));
        RepoTargets.Options o = new RepoTargets.Options();
        o.hasGitlabToken = true;
        assertEquals(Arrays.asList("gitlab", "--repo=https://gitlab.com/group/project.git"),
                args("https://gitlab.com/group/project", o));
    }

    @Test
    public void huggingface() {
        assertEquals(Arrays.asList("huggingface", "--model=user/model"), args("https://huggingface.co/user/model"));
        assertEquals(Arrays.asList("huggingface", "--dataset=user/data"),
                args("https://huggingface.co/datasets/user/data"));
        assertEquals(Arrays.asList("huggingface", "--space=user/app"), args("https://huggingface.co/spaces/user/app"));
        assertEquals(Arrays.asList("huggingface", "--org=user"), args("https://huggingface.co/user"));
    }

    @Test
    public void bitbucketAndGenericAndSsh() {
        assertEquals(Arrays.asList("git", "https://bitbucket.org/team/repo.git"),
                args("https://bitbucket.org/team/repo/src/main/"));
        assertEquals(Arrays.asList("git", "https://git.example.com/team/app.git"),
                args("https://git.example.com/team/app.git"));
        assertEquals(Arrays.asList("git", "git@github.com:o/r.git"), args("git@github.com:o/r.git"));
    }

    @Test
    public void gitOptionsApplyOnlyToGitSource() {
        RepoTargets.Options o = new RepoTargets.Options();
        o.branch = "main";
        o.maxDepth = "50";
        assertEquals(Arrays.asList("git", "https://github.com/o/r.git", "--branch=main", "--max-depth=50"),
                args("o/r", o));
        assertEquals(Arrays.asList("huggingface", "--model=u/m"), args("https://huggingface.co/u/m", o));
    }

    @Test
    public void invalidInput() {
        RepoTargets.Target t = RepoTargets.parseOne("not a url", new RepoTargets.Options());
        assertNotNull(t.error);
        assertNull(t.args);
    }

    @Test
    public void parseSplitsLinesAndCommasAndSkipsComments() {
        List<RepoTargets.Target> ts = RepoTargets.parse("o/r\n# comment\n\n a/b , c/d \n", new RepoTargets.Options());
        assertEquals(3, ts.size());
        assertTrue(RepoTargets.needsGit(ts));
        assertTrue(RepoTargets.needsGit(RepoTargets.parse("https://huggingface.co/u/m", new RepoTargets.Options())));
        assertFalse(RepoTargets.needsGit(RepoTargets.parse("not a url", new RepoTargets.Options())));
    }
}
