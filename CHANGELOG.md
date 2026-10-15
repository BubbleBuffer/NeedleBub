# Changelog

## 0.1.0-beta.2

- Hardened developer capture exports against disconnected readers and expired
  or revoked grants, so an interrupted export does not terminate the app.
- Snapshot encrypted records before streaming so notifications arriving during
  an export cannot change its contents or wait behind a slow reader.
- Added a validated desktop pull command that rejects incomplete exports and
  never automatically retries or overwrites an existing export.

## 0.1.0-beta.1

- Promoted the app to a permanently signed private beta.
- Reorganized the interface around built-in Features, with One-time codes as
  the first operational module and app-wide MacroDroid integration in Settings.
- Added content-free seven-day activity summaries and authenticated Notification
  Records and Capture/Export developer pages.
- Added lifecycle-aware notification extraction for ordinary, messaging, inbox,
  progress, media, navigation, and ongoing-service notification shapes.
- Serialized notification inference with bounded backpressure and fixed timeout
  recovery so a cancelled isolated runtime cannot cascade failures into queued
  requests.
- Added signed automatic OTP-pack updates, mobile-data controls, health checks,
  atomic activation, and rollback.
- Added biometric or device-credential authentication for developer data,
  encrypted exports, destructive capture clearing, and secure OTP copying.
- Enabled R8 and resource shrinking, reducing the signed ARM64 APK from roughly
  25.3 MB to 17.0 MB.
