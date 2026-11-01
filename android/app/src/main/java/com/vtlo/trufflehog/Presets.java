package com.vtlo.trufflehog;

/** Argument templates for every trufflehog command. */
final class Presets {
    static final class Preset {
        final String label;
        final String args;
        final String help;
        final boolean needsGit;

        Preset(String label, String args, String help, boolean needsGit) {
            this.label = label;
            this.args = args;
            this.help = help;
            this.needsGit = needsGit;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static final Preset[] ALL = {
            new Preset("filesystem — files & folders", "filesystem /sdcard/Download",
                    "Scan files or directories. Use \"+ File\"/\"+ Folder\" or grant Storage access to scan /sdcard paths.", false),
            new Preset("git — repository", "git https://github.com/trufflesecurity/test_keys",
                    "Scan a git repo by https://, ssh:// or file:// URL. Flags: --branch, --since-commit, --max-depth, --bare.", true),
            new Preset("github — repos / orgs", "github --repo=https://github.com/trufflesecurity/test_keys",
                    "--repo / --org (repeatable). Token via GITHUB_TOKEN env. Extras: --issue-comments --pr-comments --gist-comments --include-wikis.", true),
            new Preset("github-experimental", "github-experimental --object-discovery --repo=https://github.com/<user>/<repo>.git",
                    "Experimental object discovery. Requires GITHUB_TOKEN.", true),
            new Preset("gitlab", "gitlab --repo=https://gitlab.com/<group>/<repo>",
                    "Token via GITLAB_TOKEN env (required). --endpoint for self-hosted, --group-id to scan a group.", true),
            new Preset("huggingface", "huggingface --model=<user>/<model>",
                    "--model / --space / --dataset / --bucket / --org / --user. Token via HUGGINGFACE_TOKEN.", true),
            new Preset("s3 — AWS buckets", "s3 --bucket=<bucket>",
                    "Credentials via AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_SESSION_TOKEN env. --endpoint for S3-compatible.", false),
            new Preset("gcs — Google Cloud Storage", "gcs --project-id=<project> --without-auth",
                    "--service-account=<json path>, --api-key (GOOGLE_API_KEY) or --without-auth for public buckets.", false),
            new Preset("docker — images", "docker --image=trufflesecurity/secrets",
                    "Registry images; file:// for a local tarball. DOCKER_TOKEN env for private images.", false),
            new Preset("postman", "postman --workspace-id=<id>",
                    "Token via POSTMAN_TOKEN. Also --collection-id, --environment, --workspace-paths, --collection-paths.", false),
            new Preset("jenkins", "jenkins --url=https://jenkins.example.com",
                    "JENKINS_USERNAME / JENKINS_PASSWORD env.", false),
            new Preset("elasticsearch", "elasticsearch --nodes=https://127.0.0.1:9200",
                    "Auth via ELASTICSEARCH_USERNAME/PASSWORD, ELASTICSEARCH_API_KEY, ELASTICSEARCH_CLOUD_ID ...", false),
            new Preset("circleci", "circleci", "Token via CIRCLECI_TOKEN env (required).", false),
            new Preset("travisci", "travisci", "Token via TRAVISCI_TOKEN env (required).", false),
            new Preset("syslog — listen", "syslog --address=0.0.0.0:5514 --protocol=udp --format=rfc5424",
                    "Listens for syslog and scans messages. Use a port above 1024.", false),
            new Preset("stdin — paste text", "stdin",
                    "Scans the text in the \"Input text\" box below.", false),
            new Preset("multi-scan — config file", "multi-scan --config=/sdcard/Download/trufflehog.yaml",
                    "Scan multiple sources defined in a configuration file.", false),
            new Preset("json-enumerator", "json-enumerator /sdcard/Download/enumerator.json",
                    "Scan items from a JSON enumerator file.", false),
            new Preset("analyze — key permissions (TUI)", "analyze",
                    "Interactive: opens in the Terminal to analyze an API key's permissions.", false),
            new Preset("interactive wizard (TUI)", "",
                    "Opens trufflehog's interactive menu in the Terminal.", false),
            new Preset("custom", "--help", "Any trufflehog arguments.", false),
    };

    private Presets() {}
}
