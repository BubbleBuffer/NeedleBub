# Private alpha gates

Completed on the development machine:

- strict pack and tools schemas and hostile ZIP installation tests;
- model and upstream runtime checksum pinning;
- ARM64 JNI compilation and static Needle linking;
- isolated service and public AIDL compilation;
- OTP postprocessor parity with every original Python fixture;
- browser/unit tests, Kotlin unit tests, and APK assembly.

Still requires an attached Android 12+ ARM64 phone:

1. Load the tuned `.cact` through the read-only descriptor in the isolated
   process and confirm the exact pinned engine compatibility.
2. Run the 200-example held-out corpus through Android and compare with
   `OTPNeedle/RESULTS.md`.
3. Confirm MacroDroid receives generic outputs plus `nb_code` and `nb_source`.
4. Measure cold/warm/model-switch latency, peak RSS, idle release, notification
   delivery, timeouts, cancellation, and binder-death recovery.
5. Exercise permission denial, source modes, hidden lock-screen content,
   authenticated copy, and process-death cases.
