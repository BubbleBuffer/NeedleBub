# Two screen studies, pending user choice

## Adoption — 2026-09-24

The user approved the current slim A design for production implementation and selected Design A (Open expression) for the finalized assets. This supersedes earlier provisional-choice and no-production instructions below. The final reference is the distilled five-page board: model Overview, separate One-time codes, Settings, Sources, and Activity. The asset page now presents Design A; the earlier two-option comparison is archived. Production SVGs are byte-identical copies in public/design-a.

Purpose: let the user compare two materially different compositions before implementing a new direction. This is a code-first interactive sketch for this round, not a stored workflow default.

Both studies show the same fictional ready state: One-time codes is on; 2 notification sources; content-free summary of 3 codes handled today, last activity 2 minutes ago. An attention state means notification access is off, with an explicit path to reconnect; do not claim actual platform settings opened. Prototype only, no backend or real clipboard.

Study A: Pocket console. A warm compact personal robot control panel: expressive status display at the center, physically coherent joined controls, restrained apricot and mint, purposeful cut corners. Calm and approachable; one expressive face communicates a real labeled state.
Study B: Ink circuit. A dark manga/cyberdeck utility: asymmetrical panel composition, bold typography, high-contrast light fields, apricot action paths, emoticon status integrated into the tool rather than a generic hero. Avoid stereotypical neon HUD telemetry, endless tiny mono labels, fake technical readouts, and decorative scanlines.

Each is one phone-first HTML page at 390x780, usable 320-430 and at wide widths with max-width. Keep touch controls >=44px, contrast, visible focus, reduced motion. Existing self-hosted display/ui/mono fonts provided. Mono only for code/time data. Inline SVG for simple geometric action glyphs or emoticon expressions; these are placeholders for future Pawth asset studies. Do not redraw the user's app mark.

Interaction contract: listen for window message {type:'study-state', state:'ready'|'attention'} from the same-origin parent; update labeled readiness, expression and action honestly. A primary action can reveal a fictional, content-free activity detail; Sources can open a simple sheet with two demo app rows. No fake network/load or permissions. The real app has NO normal-user result history or paste extractor and never persists codes. Do not add a code inbox, app-specific history, or stored code display. Source configuration is separate from activity. Any mock behavior labeled in surrounding study board. Each concept is standalone and all controls must do something evident.

No production work, no shared skill changes, no finalized DESIGN.md, no installs/deploys. Acceptance: recognizable and differentiated composition, real browser rendering at phone width, state switching works, assets remain editable, review with user is next.

## User feedback, next iteration

The user preferred A (Pocket console), but found both initial studies too chunky. Preserve the original A and B. Add pocket-console-slim as a proposed refinement: lighter casing, smaller type/expression, denser meaningful grouping, unchanged touch-target usability. User has not approved a final density or production implementation.

## Selected reference

The user responded "I like this" to pocket-console-slim (A2). Use A2 as the visual reference for the next asset exploration. This supersedes the pending visual-direction choice above; it does not promote the prototype to production or authorize replacing the existing app mark. See ASSET-HANDOFF.md for the separate Astra/Pawth study.

## Connected flow, 2026-09-23

The user asked to continue design with slimmer A. `slim-a-flow/` extends the accepted Home composition into Sources and Activity, with browser-only selection, pause, empty and access-recovery states. The main `index.html` now reviews these three screens and can switch between the two Pawth asset treatments. The earlier density board is preserved as `density-review.html`.

Open expression is a provisional preview choice, not a selected replacement for the brand artwork. Secondary screens use a compact Back header, flat rows and fine dividers. Sources has staged Save/Cancel and reachable bottom actions; Activity keeps only fictional aggregate counts. See `FLOW-REVIEW.md` for verification and boundaries.
