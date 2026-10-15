# Slim A connected flow — review draft

## Adoption — 2026-09-24

The user approved the current slim A design for production implementation and selected Design A (Open expression) for the finalized assets. This supersedes earlier provisional-choice and no-production instructions below. The final reference is the distilled five-page board: model Overview, separate One-time codes, Settings, Sources, and Activity. The asset page now presents Design A; the earlier two-option comparison is archived. Production SVGs are byte-identical copies in public/design-a.

## Copy and preview controls — 2026-09-23

User correction: explanatory text earns its place only when it resolves ambiguity or helps a decision. Settings needs no subtitle explaining what settings are. Activity needs neither an introductory retention sentence nor a repeated privacy card. Notification Sources retains “Choose which apps can supply one-time code notifications.” because the source concept can be ambiguous. Privacy detail belongs in Settings disclosures; prototype provenance is compact.

The active board fixes the open expression and removes its global selector. Light/dark is global across all five previews. OTP state and Activity data controls sit with their own pages, so changing one does not reset the others. Historical expression studies remain available through the asset reference, not a primary board control.

Impeccable distill guidance is the editorial pass. The detector ran once over all nine changed UI files and returned no matches in degraded regex mode (HTML parser dependencies unavailable). It did not evaluate computed styles or contrast. Browser verification confirmed all five dark previews, global theme updates from Settings, a source draft preserved through theme changes, independent OTP/Activity controls, and no horizontal overflow in compact previews around 302–310 px. JavaScript syntax and focused source-flow assertions pass. Current board screenshot: screenshots/slim-distilled-board.png. The mechanical detector is supplementary: a clean regex result alone cannot establish that copy is useful.

## Current revision — user feedback, 2026-09-23

The earlier checks below describe the first connected-flow iteration, not final approval of its interactions. The user then rejected the all-zero day chart and confusing All apps / Selected filter and clarified that the console is an OTP feature page, not the app front page.

The current board adds `app-shell-study/` with a true model Overview and Settings. It names only the current OTP model and distinguishes imported model capabilities from built-in app features. Light/dark switching is a proposed in-memory setting. The OTP page links back to All models.

Sources now proposes Only these apps / All except these apps, independent draft lists, explicit effective source counts and search-scoped bulk actions. The exclusion list is not supported by the current Android implementation and is labeled a proposal. Activity removes the day graph entirely; its empty state explains that no activity exists without rendering seven empty tracks.

Browser checks: zero exclusions enables all five demo apps; searching Mail then Exclude all shown excludes only Mail and saves four enabled sources. The policy radio retains keyboard focus after rendering. Narrow 320 px Sources has no horizontal overflow and keeps the 48 px Save action on-screen. Overview/Settings navigation and the dark Settings preview work, with 48 px main controls. The five-screen review board has no horizontal overflow at 1280 px. Updated images: `screenshots/slim-app-contact.png`, `slim-sources-policy.png`, `slim-activity-empty.png` and `slim-settings-dark-320.png`.

The selected visual direction and connected-reference-page workflow are now retained in BubbleBufferDesign's canonical design skill references. The original selected OTP screenshot is copied unchanged with provenance; revised interactions remain open to user review. Production code is unchanged.

## Earlier iteration

This round extends the user-liked A2 prototype; it does not change production UI or shared design guidance. `index.html` is the review board, `slim-a-flow/index.html` is the connected prototype, and `screenshots/slim-flow-contact.png` shows the three current screens together.

## Design decisions for this round

- Home retains A2's proportions, thin casing, dark status display and apricot action.
- Supporting views use a compact Back header, flat layout and lighter rows. The console enclosure is reserved for Home.
- Source choices are staged until Save; Cancel and Back discard a draft. The source list has search and selected-only filtering. A sticky footer keeps Save and Cancel reachable.
- Activity shows seven local-day buckets with fictional counts, without saved codes, message bodies or source identities.
- Open expression is provisional. The review board also offers Quiet visor. Both asset families and the original app mark are preserved.

## Verification

The coordinator inspected actual browser renders at 390 x 720 and a compact 320 x 720 sample, plus the board at its actual 1280 x 720 viewport. Screenshot files are under `screenshots/slim-flow-*.png`. All are browser captures of local prototypes, not device screenshots or image-generated comps.

Direct browser checks covered search with no matches, Cancel, source deselection and Save, zero-source Home with historical activity preserved, keyboard focus after navigation, access recovery, pause, asset loading and horizontal overflow. Save remained visible in the compact viewport. A measured 42 px Back target was corrected to 48 px. The compact Sources screenshot predates that 6 px change; current 390 px captures include it.

The first visual review found a heavy Activity summary, repeated source-row captions, and an overlapping day label. The correction flattened the summary, removed repeated captions, widened day labels and kept bottom selection actions reachable.

JavaScript syntax checks pass. Impeccable's detector ran once without reported findings, but in degraded regex mode: it did not evaluate computed contrast or selectors. This is not a complete accessibility or platform validation.

An independent GPT-6 Luna medium review returned ship for this local design iteration, with no material findings in the supplied captures and code. The coordinator also verified the review board switches the face family and access-off state, then restored the open-face Ready view. This verdict applies to prototype review readiness only.

## Remaining design decisions

The face treatment, secondary-screen composition, dark theme and eventual settings/first-run design remain for collaborative review. No production adoption is implied. The prototype has no real notifications, device settings, persistence or clipboard operations.
