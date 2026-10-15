# `.nbpack` format v1

An `.nbpack` is a data-only ZIP with a maximum expanded size of 128 MiB. Its
root contains exactly `manifest.json`, `tools.json`, `model.cact`, at least one
`LICENSE*` or `NOTICE*` text file, and optionally an icon in PNG, WebP, or SVG.

Nested paths, duplicate entries, symlinks, executable mode bits, scripts,
native libraries, bytecode, archives with unknown sizes, and unexpected files
are rejected before installation. Metadata entries are limited to 512 KiB.

The strict manifest declares a reverse-domain ID, semantic version, metadata
and license, exact Needle engine ABI, model size and SHA-256, a query template
containing one `{{input}}`, supported surfaces, and typed JSON Pointer output
mappings. Unknown fields fail validation.

Local imports are unverified and external-only. The automatic notification
listener resolves only the verified `de.x0bubbuff.needlebub.otp` catalogue pack.

## Engine compatibility

The app runs Needle 3 only. Its Android ARM64 engine ABI is
`needle3-hf-0f51a1ac2917a03644c4cc7836f19476c6d177dd-android-arm64`.
The immutable upstream revision, archive SHA-256 and header SHA-256 live in
`android/app/src/main/cpp/vendor/needle/UPSTREAM.json`; CMake verifies both hashes.
The JNI build pin must also match the app's `PackManifest.ENGINE_ABI`.

Needle 2 ABI packs are incompatible. Import validation rejects them before any
native load, installed incompatible packs are excluded from inference selection,
and external requests and cold checks report `ENGINE_INCOMPATIBLE` when only
an incompatible pack is available. There is no fallback engine. The JNI loader
also rejects archive tags other than Needle 3's `0x05E12A84`.

OTP pack `1.0.0-alpha.3` carries OTPNeedle v3.2 and the `otp-needle3-v1` contract.
Its query template is `Message: {{input}}`; tools require `code`, with `source`
optional. Tool descriptions and schema match the training contract, whose
canonical tools SHA-256 is
`5ecaaf42b060df95197c838dacf29f61a894e8ff9db3f362081f4c7c025199c0`.
The exporter package is cactus-needle 3.0.4; native platform artifacts are
identified by the upstream revision and hashes, independently of SDK versions.

Built-in OTP callers pass raw message bodies. The pack template supplies the
single `Message: ` wrapper; grounding and developer capture use that same
rendered query. Notification sender metadata remains outside the model query.

Pack format v1, catalogue format and catalogue signing remain unchanged.
