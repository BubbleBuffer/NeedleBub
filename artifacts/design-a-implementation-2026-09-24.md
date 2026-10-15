# Design A implementation — 24 September 2026

The approved slim Pocket console and Open expression assets are now used by the Android app frontend. Overview is the front page, with dedicated OTP, Sources, Activity and Settings screens. Existing model management, downloads, runtime, integrations, privacy and protected developer tools remain available.

Sources supports independent allowlists and exclusions, search-scoped bulk controls, explicit Save/Cancel, loading/error recovery and truthful handling of a successful save followed by a failed refresh. Native notification admission applies exclusions only in All except these apps mode. Appearance persists System/Light/Dark; feature controls remain on the OTP page. Activity uses real aggregate totals without an empty bar chart.

The final asset page is `design-studies-2026-09-22/asset-studies/index.html`. Eleven exact SVGs are bundled under `public/design-a/`; its manifest records provenance hashes. The existing product/launcher artwork is retained. Canonical BubbleBuffer references and installed design plugin were updated to 0.1.0+codex.20260923231123.

## Validation

- Web: 36 tests passed; TypeScript and lint passed.
- Android: 167 debug unit tests passed, zero failures/errors/skips. Source-policy persistence instrumentation compiled but was not run on a device.
- Release: `npm run android:prepare` and `gradlew assembleRelease` succeeded. APK v2 signature verified. All 11 Design A SVG hashes and every generated web bundle asset match the APK contents.
- Browser: reviewed light/dark layouts, compact source layout, persisted appearance and first-run behavior. Confirmed empty source copy, resolved unavailable diagnostics and clearing an action error on navigation. Captured final Overview, OTP and Settings at 390×720.
- Impeccable detector: no regex findings. Parser dependencies were unavailable, so this was degraded coverage; computed contrast, selector matching and custom properties were not evaluated.
- Download and final asset page both returned HTTP 200 through the NetBird hostname on the host. Remote phone access and physical Android behavior remain unverified; no device was connected or installed.

## APK

- File: `design-studies-2026-09-22/downloads/NeedleBub-0.1.0-beta.3-design-a-arm64.apk`
- Existing app version: 0.1.0-beta.3 (10), arm64.
- Size: 17,037,273 bytes.
- SHA-256: `e5fce4acf147111e1037b10d824ab45d9b52771016dcb284265d2edda705ca05`

This build includes the existing working-tree changes; unrelated work was preserved. It is not a clean upstream-only release.
