# Deployment update

Installed in place on the phone on September 21 over wireless ADB. Confirmed beta.2/code9 and successfully exported all 4,754 retained records with matching manifest and unique IDs; the app process stayed alive. Earlier pending-install statements below describe preparation history. Full review: C:\Users\Admin\Documents\Projects\OTPNeedle\private\device-review-2026-09-21\beta2\REVIEW.md

# Export fix ready — 2026-09-21

Use **NeedleBub-0.1.0-beta.2-alpha-update-arm64.apk** for the existing alpha
installation. It is version code 9, package `de.x0bubbuff.needlebub`, ARM64,
Android 12+, and signed with the exact certificate of the saved alpha.7 APK.
It is intended to update in place and preserve the installation's encrypted
captures, settings, and packs. **Do not uninstall or clear data.** Actual phone
installation compatibility and the full phone export remain pending the user's
return; no phone commands were run during preparation of this update.

The separately saved `NeedleBub-0.1.0-beta.2-arm64.apk` uses the permanent beta
certificate and is not the in-place update for an alpha-signed installation.
Both APKs have identical nonsignature contents; the recovery APK remains
non-debuggable despite using the alpha signing identity. Neither was published.

## Fix

- Interrupted consumers, I/O failures, grant revocation, and grant expiry are
  contained in the export worker rather than crashing the app.
- A fixed encrypted snapshot keeps exported data and manifest counts consistent
  while new notifications arrive. Streaming, decryption and archive compression
  happen outside the shared store lock.
- Existing authenticated shell-only access and capture formats remain intact.
- New desktop pull tooling rejects malformed, truncated, or duplicate-record
  streams, preserves failed output as `.partial`, and never retries automatically.
- Snapshots above 32 MiB of encrypted payload are explicitly refused without
  deleting or silently omitting records; this bounds additional memory use.

## Verification

- 29 web tests passed; type checking and lint passed.
- Android unit-test build passed, including all 18 exporter regression tests.
- Eight Android emulator export scenarios verified: seven passed on the first
  completed suite, and the remaining full shell-export test passed after fixing
  its test-only shell invocation. They cover 4,711 synthetic records, exact
  manifest count, unique IDs, disconnect and successful follow-up export,
  revoked access, caller restrictions, and explicit oversized-snapshot failure.
- Emulator tests used only `emulator-5580` with the non-inferencing runtime stub.
  The test emulator needed its existing test credential unlocked after boot;
  the physical phone was never used for this verification.
- Production ARM64 release build passed with runtime stub explicitly disabled.
  Application instrumentation APK build passed via `:app:assembleDebugAndroidTest`.
  A broader unqualified instrumentation build also tried dependency-library
  test APKs and hit their unrelated Kotlin duplicate-class conflict; the app
  instrumentation target and release build both pass.
- Both APK signatures verified; alpha-update certificate matches the saved
  alpha.7 artifact. Package/version/SDK/architecture and absence of private
  dataset/model files in the APK were checked.
- Dependency audit passed the high-severity gate; two moderate findings remain
  in the development-only Vitest dependency chain.

SHA-256 hashes and sizes are recorded in
`NeedleBub-0.1.0-beta.2-verification.json` beside these APKs.

## Retry when the user returns

Install the alpha-update APK in place, authenticate in Capture and export, and
grant temporary ADB access. Run the validated pull with an explicit phone serial
and a new private local filename. Do not erase the phone's capture store after
export until the complete manifest count has been verified.

The original ADB reader termination cause is not established. This update fixes
the app crash on termination and the new pull method was verified through the
emulator shell path. The actual phone's full retained dataset still needs its
post-update retry. The desktop training importer format update and Needle 3
migration are separate work, not included in this APK.
