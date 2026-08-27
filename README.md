# NeedleBub

NeedleBub is an Android capability host for small, locally fine-tuned Needle
models. The private alpha extracts one-time authentication codes from explicitly
selected notifications and exposes installed external packs to MacroDroid and
other Android apps.

Input text, tool calls, OTPs, and result JSON are memory-only during normal
operation. An explicitly unlocked developer capture can retain encrypted local
records for dataset work. The isolated inference service has no Android
permissions and no network access of its own.

## Architecture

- React 19, Vite 8, and Capacitor 8 provide a compact Home with hash-backed
  Sources, Model, Advanced, and authenticated Developer Lab drill-ins.
- Kotlin owns pack installation, notification selection, the Locale action,
  public Binder contracts, rate limits, and recovery.
- The verified official OTP pack updates atomically from an Ed25519-signed
  public catalogue. Automatic checks run over unmetered networks and retain the
  previous verified pack for rollback.
- `IsolatedInferenceService` is declared with `android:isolatedProcess="true"`.
- A small JNI library memory-maps a read-only model file descriptor and links a
  checksummed ARM64 Needle archive.
- The process-global Needle C API is serialized. The last pack stays warm for
  five idle seconds, after which the broker unbinds the isolated process.

See [NBPACK.md](docs/NBPACK.md), [GATEWAY.md](docs/GATEWAY.md), and
[PRIVATE_ALPHA.md](docs/PRIVATE_ALPHA.md).

## Build

Requirements: Node 22+, Android SDK 36, Java 21, CMake 3.22.1, and Android NDK
23.1.7779620. Only `arm64-v8a` is built.

```powershell
npm install
npm test
npm run test:android:emulator
npm run pack:otp
npm run android:build
```

`test:android:emulator` requires a booted x86_64 Android emulator. It uses a
property-gated, non-inferencing Needle stub while exercising the production
Android Keystore, SQLite store, Capacitor controls, system notification
listener, crash detection, and visible capture count end to end. Release builds
remain ARM64-only and always link the pinned Needle archive.

`pack:otp` reads the existing tuned model from the sibling `OTPNeedle` project,
verifies its SHA-256, and writes a release artifact under `artifacts/`. Override
the source with `OTP_NEEDLE_MODEL_PATH` when needed.

The current host compiles and links on Windows. The tuned model and automatic
OTP path are exercised on an Android 16 Pixel 8 Pro over wireless ADB. The full
held-out corpus, MacroDroid output-variable interoperability, model switching,
and binder-death recovery remain broader private-alpha gates.

## Privacy boundary

NeedleBub never persists or transmits notification bodies, caller input,
inference output, extracted codes, or result JSON during normal operation.
Tapping the Version entry in build facts seven times unlocks Developer Mode.
The Notification Lab requires biometric or device-credential authentication
for each foreground session. Its capture is off by default, encrypts each full
notification/model record with an Android Keystore key, retains at most 30 days
or 10,000 records, and exports only after device authentication into a
password-authenticated `.nbcapture` file. Backgrounding the app clears the
decrypted Lab state. Persistent diagnostics and ordinary Logcat output remain
metadata-only. There is no paste/share extractor or normal-user result history.

MIT licensed. Needle and the Locale protocol are covered by the notices in
[NOTICE.md](NOTICE.md).
