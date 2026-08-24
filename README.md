# NeedleBub

NeedleBub is an Android capability host for small, locally fine-tuned Needle
models. The private alpha extracts one-time authentication codes from explicitly
selected notifications and exposes installed external packs to MacroDroid and
other Android apps.

Input text, tool calls, OTPs, and result JSON are memory-only. The isolated
inference service has no Android permissions and no network access of its own.

## Architecture

- React 19, Vite 8, and Capacitor 8 provide a compact Home with hash-backed
  Sources, Packs, and Advanced drill-ins.
- Kotlin owns pack installation, notification selection, the Locale action,
  public Binder contracts, rate limits, and recovery.
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
npm run pack:otp
npm run android:build
```

`pack:otp` reads the existing tuned model from the sibling `OTPNeedle` project,
verifies its SHA-256, and writes a release artifact under `artifacts/`. Override
the source with `OTP_NEEDLE_MODEL_PATH` when needed.

The current host compiles and links on Windows. The tuned model and automatic
OTP path are exercised on an Android 16 Pixel 8 Pro over wireless ADB. The full
held-out corpus, MacroDroid output-variable interoperability, model switching,
and binder-death recovery remain broader private-alpha gates.

## Privacy boundary

NeedleBub never persists or transmits notification bodies, caller input,
inference output, extracted codes, or result JSON. Diagnostics may contain only
pack ID/version, status, stable error code, duration, and memory measurements.
There is no paste/share extractor and no result history.

MIT licensed. Needle and the Locale protocol are covered by the notices in
[NOTICE.md](NOTICE.md).
