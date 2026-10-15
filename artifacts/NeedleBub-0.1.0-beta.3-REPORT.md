# NeedleBub beta.3 — reliability and copy update

Version 0.1.0-beta.3, code 10. Prepared September 21, 2026. Publication and physical-phone verification are pending; no beta.3 phone installation is claimed.

## Changes

- Notification Copy no longer opens an app-added biometric prompt. Android's device lock still applies. Developer capture/export authentication is unchanged.
- Added **Auto-copy codes**, off by default, with the requested unlocked-only description. Delivery rechecks automation, preference, unlock state, accepted grounded result, current notification revision and a two-minute notification-age limit.
- Deduplication remembers item/code pairs across restarts and ordinary text revisions. Missed codes do not replay on unlock or a timer. Storage holds the latest 128 pairs per index; sufficiently old pairs can be evicted by later distinct results. Arbitrary reposted text cannot establish the actual issuing time of a code.
- Both copy paths mark clipboard content sensitive and contain clipboard errors. Manual actions have persisted, expiring, unique delivery tokens. No background clearing can erase newer clipboard content; clearing is left to Android.
- Runtime deadlines run independently of the main looper. Queue/bind waits are bounded, stale generations and timers cannot invalidate newer work, cancellation/start admission is atomic, and callbacks retain admission capacity until completion. Revisions still coalesce within capacity; overload gets an explicit outcome.
- Independent inference requests rewind conversation state while retaining loaded weights. Queue size, five-second notification inference budget, five-second idle residency and 256-token limit remain unchanged.
- Traces add request time, build, engine/model identity, separate queue/bind/load/inference/runtime-result-persistence timing and explicit unknown load state. Persistence timing covers the runtime-record write, not every later diagnostic/outcome write. Old captures remain readable.
- Fixed an additional reproduced activity-lifecycle crash: a queued Capacitor request arriving after executor shutdown now rejects cleanly instead of crashing the app.

## Evidence and limits

Historical captures contained timeout durations around 32 seconds despite a five-second inference budget. A main-thread watchdog was vulnerable to main-looper stalls, and its failure records incorrectly inferred warm state from a reload flag. Those paths are repaired; individual historical timeouts cannot all be attributed to one cause.

The deployed Needle 2 weights completed 78 independent desktop requests with resets. The same ordered sequence retaining context stalled on request 25 for more than 30 seconds. This supports the independent-request reset change, but execution of the exact Android arm64 engine is still pending on the physical phone.

Six synthetic long-input cases completed without truncation at both 256 and 512 tokens. Larger input materially increased latency; no benefit from a larger output budget was established. Historical phone truncations remain unattributed. Failed/truncated envelopes cannot produce accepted copies.

## Verification

- Desktop importer/evaluation tests: **125 passed**. The importer validates manifest v3 and mixed record v2/v3 while retaining older support. The full local export of **4,754 records, 3,461 lifecycles and 56,312 observed revisions** validates successfully.
- Web tests: **31 passed**; type checks, lint and Android UI production build passed.
- Android JVM tests: **164 passed** after final application changes.
- Emulator: **all 32 tests passed in the final full run**, including all 11 copy tests and rapid activity recreation. The real UI switch also toggled the native persisted setting on/off successfully and was left off.
- Actual Android main-looper/capture-store contention test: a 250 ms synthetic deadline fired in **305/252 ms with capture off** and **252/253 ms with capture on**; successful recovery followed in both cases. The test deliberately held the real store monitor and used encrypted synthetic result persistence. This is a bounded-contention test, not a native inference or general capture-overhead benchmark.
- Emulator uses an x86 runtime stub. It verifies lifecycle, binding, copying, storage and export behavior, not production model inference.

## Needle 3 recommendation

**Keep Needle 2 for this update.** Separate pinned Needle 2.0.9 and Needle 3.0.1 environments, model hashes, 4/8/12/20-layer candidates, validation-only schema/confidence experiments and held-out synthetic results are retained locally. Needle 3 candidates produced more false positives and frequent truncation. A small local LoRA export/reload smoke succeeded after the base comparison; it is not a production model. No hosted training or notification upload occurred.

The current model also produced four false positives among 68 synthetic negatives. The comparison is a regression study, not accuracy clearance for publication: some repository negative families were available to previous training. Phone captures remain unlabelled local evidence, not automatic labels. Human-reviewed real-data evaluation remains outstanding.

Detailed comparison: [Needle 3 report](C:/Users/Admin/Documents/Projects/OTPNeedle/private/needle3-evaluation-2026-09-21/REPORT.md).

## Packaging checks

Both non-debuggable arm64 APKs have verified signatures and identical application payloads after resigning. APK filenames and SHA-256 hashes are in the adjacent verification JSON. A native packaging defect was also corrected: ELF LOAD alignment was 4 KB and is now 16 KB; both APKs pass the 16 KB ZIP alignment check. This follows [Android's native-library guidance](https://developer.android.com/guide/practices/page-sizes). Execution on a 16 KB device is not yet verified.

## Update and remaining checks

The alpha-compatible APK is intended for an in-place update of this phone's existing signing identity. The permanent beta-signed APK is a separate artifact and cannot replace that existing installation without a signing migration. Do not uninstall or clear the app to work around this.

The phone disappeared from wireless ADB and is no longer discoverable. Physical installation, native arm64 reset/compatibility probes and the bounded synthetic notification smoke remain pending. The prepared probe uses only authored synthetic inputs and a remote timeout. Its source is `OTPNeedle/scripts/run_needle_android_probe.py`.

Do not publish based solely on these desktop/emulator results. Complete the physical smoke and review the remaining extraction errors first.
