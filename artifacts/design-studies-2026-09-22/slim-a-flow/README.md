# Slim A OTP flow study

This browser-only study extends the selected Pocket console A2 visual direction. Its landing view is the **One-time codes feature page**; the header's **All models** link opens the sibling app overview study. This is a proposed interaction, not the Android app.

Open `index.html` with `view=home|sources|activity`, `state=ready|paused|attention|empty`, optional `theme=light|dark`, and `treatment=open|visor`. The Open SVG family is the provisional default. Secondary Back buttons return to the OTP page; browser Back also follows the route history. The All models link carries the selected theme to the app overview study.

Sources offers two explicit policies: **Only these apps** (checked apps allowed) and **All except these apps** (checked apps excluded). The list heading, row outcome, and enabled count follow the chosen policy. Search and the named bulk actions affect only shown demo apps. Each policy keeps its own draft list; switching modes does not invert or erase either list. **Save policy** commits both lists and the active mode to this in-memory preview. Cancel or Back discards the draft. Zero checked in Only mode means no sources; zero checked in Except mode means all five demo sources. Paused and access-off status survive policy edits.

The current Android implementation supports `allApps` or a selected package allowlist (`AutomationSettings.accepts` uses `allApps || packageName in selectedPackages`). **The exclusion policy is a design proposal and has no production support.** This preview never changes Android settings, reads notifications, copies codes, or persists values outside browser history.

Activity uses illustrative counts for today and seven rolling local days, plus the last activity time. A fresh zero sample shows a short empty state and a Sources action when no source is enabled. Policy edits never create or erase activity. Normal-user activity contains no code, message content, or app identity; the UI keeps that explanation in Settings. The preview accepts same-origin `{type:'study-theme',theme}` messages without changing its source draft or view.

Validation: `node --check script.js` and `node verify-flow.mjs` pass. The focused assertions cover policy semantics, independent drafts, search-scoped bulk actions, Cancel, zero-source resume/recovery, and activity independence. Browser screenshots and visual checks are handled by the study board owner.
