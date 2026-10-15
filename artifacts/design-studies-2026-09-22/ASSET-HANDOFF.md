# NeedleBub asset study — slim Pocket console

The user selected A over B, found both chunky, then liked the slimmer A2. Preserve the manga, cyberpunk, robot and emoticon character through small expressive details within this lighter layout. The task is to offer editable asset options for collaborative review.

## Reference files

All paths below are relative to this document's directory.

- `screenshots/pocket-console-slim.png`: selected 390 x 720 visual reference.
- `pocket-console-slim/index.html`, `style.css`, `script.js`: selected interactive prototype and current state/action vocabulary.
- `assets/needlebub-mark.svg`: existing user-authored app mark; preserve exactly.
- `assets/lilbub-reference.svg`: user-approved LilBub faceted head reference.
- `PRODUCT.md`: provisional product facts; validate uncertain behavior against the app README.
- Local preview, while its server runs: http://127.0.0.1:5184/pocket-console-slim/index.html

## Visual constraints

Paper #f5f0e5, ink #292d2b, casing #e9e1d1, apricot #efad7c, mint #c2e5c8, muted #625e53, line #bcb3a0. Current typography is Bricolage, Atkinson and Fragment Mono; mono is reserved for data. Keep A2's thin casing, compact title and 48px touch targets. Small assets must remain readable without inflating the panel. Avoid heavy bevels, thick outlines, pill-shaped decoration, neon HUD telemetry and tiny technical labels. Expressiveness should come from silhouette, face and a few deliberate cuts.

## Deliverables

Explore two closely related treatments, not two new interface redesigns:

1. Compact expression family for the existing ready, paused and access-off/attention states. Each state retains an adjacent text label; meaning must not depend on color or expression alone. Show faces at 32px and the prototype's 96px display size.
2. Cohesive functional glyphs for activity, sources, pause, resume and reconnect access. Show 20px and 24px samples, with their labels and 48px control context. Keep source configuration distinct from history.

Deliver named and logically grouped editable SVG boards, individual SVG assets, PNG previews at relevant sizes, and one copy of the A2 prototype per treatment showing assets in context. Include a short recommendation explaining legibility, character and any unresolved tradeoff. Two treatments total is enough.

Write only to `asset-studies/` under this directory (create it as needed). Preserve every reference and the accepted prototype. Use an index/contact sheet that makes review easy. All samples are exploratory; no launcher redesign, production changes, canonical skill edits, installations or deployments.

## Product truth

NeedleBub extracts codes locally from selected notifications. Normal usage does not persist codes, notification bodies or inference output. There is no normal-user code history or paste extractor. The prototype's activity is fictional aggregate data, not a code inbox or an app-specific activity log. Do not invent features to justify assets.

## Pawth and verification

Use the pawth-svg-editor skill and discover the current Pawth runtime/CLI in C:/Users/Admin/Documents/Projects/Pawth. Prefer its live command bus and native SVG/PNG export. Do not use legacy svg-icon-editor commands without verifying that identity. Keep unrelated live artwork untouched; use a dedicated study document. Never print session tokens. If live editor access fails, document the concrete limitation and continue with organized SVG study files and rendered previews rather than claiming live validation.

Inspect actual rendered assets at the requested sizes and show them in a copy of A2. Verify the ready, paused and attention presentation and keep controls usable. Readability at actual scale matters more than elaborate artwork. Finish with exact output paths and previews for the parent task to review with the user. Do not promote your preferred option automatically.
