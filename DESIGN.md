# NeedleBub — slim Pocket console / Design A

Approved for app implementation by the user on 2026-09-24. Surface mode: Operate. Android phone app with React/Capacitor UI.

## Visual authority

The selected reference is artifacts/design-studies-2026-09-22, after the distilled copy revision. app-shell-study defines Overview and Settings; slim-a-flow defines the OTP console, Sources, and Activity. Existing approved artwork remains authoritative: /needlebub-mark.svg is the product mark. Design A Open expression provides in-app states and controls in public/design-a, with exact source hashes in manifest.json. Quiet visor is historical exploration.

Use warm paper, charcoal, restrained apricot primary actions, fine seams, compact type, and thin casing around the OTP console. Keep the open emoticon and manga/robot character in functional states. Overview and secondary pages use simple rows and spacing. All interactive touch targets remain at least 48 px where practical. Keep a visible keyboard focus ring and reduced-motion support.

## Structure and behavior

- Overview is the front page, showing built-in features and actual imported models. Do not invent future models or imply imported packs add UI screens.
- One-time codes owns its console, installation/access setup, pause/resume, source controls, and aggregate activity destination.
- Activity contains actual totals and a clear empty state. Do not restore the all-zero day graph. Codes and notification text are never part of normal-user history.
- Sources uses Only these apps or All except these apps. Save commits both independent lists; Cancel discards drafts. Search-scoped bulk actions name their scope. A saved exclusion has no effect in Only these apps mode.
- Settings owns appearance and links to access, downloads, runtime, model management, integrations, privacy, and the existing protected developer paths. System/Light/Dark is an app appearance preference, with System as the default.

## Copy

Do not explain a self-explanatory heading. Omit marketing phrases, repeated privacy assurances, and design-study commentary from the shipped UI. Keep explanation where it resolves a real ambiguity: Notification Sources retains “Choose which apps can supply one-time code notifications.” Put detailed privacy behavior in Settings. Recovery copy names the missing requirement and provides the next action.

## State and implementation truth

Use actual native configuration, permissions, packs, source policy and content-free activity. No fictional ready state or prototype labels in the Android app. Browser fallback must not claim native operations succeeded. Preserve setup, errors, authorization, export and deletion semantics while adopting the selected layout. Design approval does not establish physical-device validation; report build, tests and runtime evidence separately.
