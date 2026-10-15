# NeedleBub app shell study

Open `index.html?view=overview` or `index.html?view=settings`, with optional `theme=light|dark`. This isolated browser prototype adds a general overview and grouped Settings to the Slim A One-time codes flow. Its ready state and access labels are sample data. Appearance changes update the preview and its connected study pages; no Android permissions, models, storage, or settings are changed.

## Source grounding

- `src/App.tsx` declares one built-in feature, One-time codes, mapped to the official `de.x0bubbuff.needlebub.otp` pack. `catalogue/catalogue.json` has one verified OTP Extractor entry.
- Imported packs are unverified data-only capabilities for external callers. They do not add application screens (`src/App.tsx`, Models and imports).
- Android Settings has Runtime, Downloads, Models and imports, Integrations, Privacy and data, and About. This study puts user-facing access and privacy information first and summarizes technical sections under Advanced.
- `src/native.ts` exposes a resolved `light` or `dark` color scheme but no theme setter. The current app follows the device theme. The appearance control is a proposed preview, not shipped behavior.
- Normal use retains only content-free seven-day activity summaries; message bodies and codes remain in memory. The demo's sample state is not a real device reading (`README.md`, Privacy boundary).

## Proposal boundary

The overview reserves a list structure for additional built-in tools without naming or implying any future shipped model. The external-capability empty state is a preview. The theme selector is a UI proposal, and the access labels are sample values. Theme links carry `theme` across studies; a same-origin parent can send `{type:'study-theme',theme}` without closing an open Settings disclosure. Choosing Light or Dark in Settings sends the same message to the parent board. The one-time-code flow lives in `../slim-a-flow/`.
