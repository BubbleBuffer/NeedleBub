# Private beta

`0.1.0-beta.1` is the first NeedleBub build signed with the permanent
BubbleBuffer private-beta Android identity. It remains a private ARM64 APK for
Android 12+ and is distributed through the existing private Google Drive
folder, not a public GitHub release.

## Alpha migration

Alpha builds used Android's disposable debug signing identity. Android cannot
install the permanently signed beta over those builds, so the first beta
installation requires uninstalling the alpha once. That clears its settings,
installed packs, activity summaries, and encrypted captures. Later beta builds
must use the same private-beta key and will update in place.

Before uninstalling an alpha, export any developer capture that must be kept.
The OTP pack can be reinstalled from NeedleBub's signed public catalogue after
the beta starts.

### Alpha capture recovery update

The beta.2 exporter fix is also packaged as
`NeedleBub-0.1.0-beta.2-alpha-update-arm64.apk`, signed with the existing alpha
identity and version code 9. This is an in-place recovery update for alpha
installations: do not uninstall or clear application data before retrieving
their captures. Its signer must match the installed alpha. It is distinct from
the permanently signed beta artifact and is not the public release candidate.
The saved alpha.7 artifact provides the local signer comparison; installation
compatibility with the connected phone remains to be checked when its owner
returns. No phone installation is part of preparing this update.

## Beta gates

- permanent release signing and verified APK signature;
- R8 code shrinking and resource shrinking with JNI names retained;
- unit, web, Android, pack, lint, dependency, and release-build verification;
- Pixel smoke tests for setup, OTP acceptance, model rejection, pause/resume,
  five-second cold reload, authenticated Copy, Developer Lab authentication,
  and temporary ADB capture access;
- APK inspection for architecture, SDK bounds, and forbidden private artifacts;
- private Drive upload only after the installed candidate passes.

## Scope

The official OTP pack remains `1.0.0-alpha.2`; this app release does not
retrain or republish it. One-time codes are the only built-in feature. Imported
packs remain data-only and externally callable through MacroDroid or the public
Binder gateway. Notification classification, digests, larger-model escalation,
and a personal dashboard remain future ideas.

The release keystore and `signing.properties` live outside Git under
`%USERPROFILE%\.config\needlebub\release`. Both files are required for every
future update and must be backed up securely.

## Application identity

Since 2026-10-09 the applicationId is `de.bubblebuffer.needlebub`; the private
F-Droid repo publishes the app under that id. The old `de.x0bubbuff.needlebub`
is a separate app to Android: it installs side by side, and its settings,
installed packs, activity summaries and captures do not move to the new one.
Export any capture to keep before uninstalling the old app.

The Gradle namespace and Java/Kotlin packages stay `de.x0bubbuff.needlebub`.
The inference gateway action is now `de.bubblebuffer.needlebub.action.INFERENCE_GATEWAY`,
so MacroDroid and Binder callers that bind the old action must switch.
Official pack ids such as `de.x0bubbuff.needlebub.otp` are unchanged.

versionCode is the build's minute since the epoch unless `-PversionCode` is
given, so each build's code is higher than the last.
