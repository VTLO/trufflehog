# VTLOs3c — TruffleHog for Android

A standalone Android APK that bundles the full `trufflehog` CLI from this
repository. It needs no root and no Termux.

## What's inside

| Component | Purpose |
|-----------|---------|
| `libtrufflehog.so` | The trufflehog CLI cross-compiled for Android with cgo. Every detector, verifier, source and analyzer from this repo is included. |
| **Online repos** tab | Paste repository URLs, one per line, and scan them with full git history. It accepts GitHub repos, users and orgs (or the `owner/repo` shorthand), GitLab, Bitbucket, Codeberg and Hugging Face URLs, plus any `https://`, `ssh://` or `git@` git URL. A live preview shows the trufflehog command for each line. Optional GitHub/GitLab/Hugging Face tokens unlock private repos; tokens are kept in memory only and never saved. Other options: verified-only, JSON, GitHub issue/PR comments, branch, max depth and since-commit. You can also share a repo link from your browser to VTLOs3c. |
| **Sources** tab | A form with presets for every command: `filesystem`, `git`, `github`, `github-experimental`, `gitlab`, `huggingface`, `s3`, `gcs`, `docker`, `postman`, `jenkins`, `elasticsearch`, `circleci`, `travisci`, `syslog`, `stdin`, `multi-scan`, `json-enumerator` and `custom`. It also has toggles for the global flags, a box for env vars (tokens), a stdin box, file and folder import, and live output you can share or save. Scans keep running in a foreground service. |
| **Terminal** screen | A real PTY terminal emulator ([Termux terminal-view](https://github.com/termux/termux-app), Apache-2.0). It runs trufflehog's interactive TUI: the wizard you get with no arguments, and `analyze` for API-key permission analysis. After the command exits you get a shell (`/system/bin/sh`) with `trufflehog` on `PATH`. |
| Share target | Share any text to VTLOs3c from another app and it is scanned with `trufflehog stdin`. |
| `libgit.so` | Bundled `git` + `git-remote-https` (git 2.47 with static curl, OpenSSL and zlib). trufflehog runs the `git` program for the `git`, `github`, `gitlab` and `huggingface` sources, and Android has no git. `WITH_GIT=0` skips it. |

The CA bundle is generated at startup from the device trust store, including
user-installed CAs. DNS and TLS go through Android's bionic libc. A
`CGO_ENABLED=0` build can't resolve DNS on Android.

The self-updater is always disabled (`--no-update`).

## Building

Requirements:
* Go (the version in `../go.mod`)
* JDK 17+
* Gradle 8.9+
* Android SDK with `platforms;android-34`, `build-tools;34.0.0` and an NDK (r26+)

```sh
export ANDROID_HOME=/path/to/android-sdk
./build.sh                    # arm64-v8a + x86_64, git included
WITH_GIT=0 ./build.sh         # without git (no git/github/gitlab/huggingface sources, no Online repos tab)
ABIS=arm64-v8a ./build.sh     # phones only (smaller APK)
```

The output is `dist/VTLOs3c.apk`. By default it is signed with the local debug
key. To sign with your own key, pass
`-PreleaseStoreFile=… -PreleaseStorePassword=… -PreleaseKeyAlias=… -PreleaseKeyPassword=…`
to Gradle.

32-bit ARM (`armeabi-v7a`) is not supported: upstream trufflehog doesn't
compile for 32-bit targets.

## Usage tips

* **Online repos:** how each URL is scanned.
  * GitHub repos use the `git` source (full history, no API calls). With a GitHub token, or with "issue and PR comments" checked, they use the `github` source instead.
  * `https://github.com/<name>` scans all repos of that user or org.
  * GitLab repos use the `gitlab` source when a GitLab token is set.
  * Several URLs run one after another, and their output is combined.

* **Scanning `/sdcard`:** tap *Storage access* and grant "All files access".
  Without it, *+ File* / *+ Folder* copies the picked items into app storage and
  scans the copy.
* **Tokens:** add them in the env box, one per line, for example
  `GITHUB_TOKEN=…`, `GITLAB_TOKEN=…`, `AWS_ACCESS_KEY_ID=…`.
* **Terminal:** the extra-keys row gives you ESC, TAB, CTRL, ALT, arrows and
  paste. Pinch to zoom.
