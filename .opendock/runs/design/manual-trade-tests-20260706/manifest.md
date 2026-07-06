# Design Run Manifest

Status: updated

## Target Files

- `apps/dashboard/src/App.jsx`
- `apps/dashboard/src/styles.css`

## Design Contract

- DESIGN.md reviewed: yes.
- STYLESEED.md reviewed or updated: reviewed.
- Key color/accent: `#F7A707`.
- Radius personality: sharp.
- Motion style: Snap.

## Palette Planning

Palette Source: existing DESIGN.md and StyleSeed design lock.
Palette Mood: compact, exchange-style, calm operations dashboard.
Palette Role Map: canvas/surface/text/border from dashboard tokens; primary actions use Bybit-like yellow; cancel/close actions use semantic danger.
Contrast Plan: destructive row actions use danger text on raised surface and danger background on hover; focus ring remains accent.
Color Risks: avoid adding another accent, avoid rainbow statuses, reserve danger for cancel/close actions only.

## Layout Planning

Layout Type: dashboard.
First Gaze: account state and bot control rail.
Primary Action: verify runtime state, then run specific manual tests.
Section Architecture: keep existing account/control/table layout; extend the control rail with runtime-aware manual order test; add row-level actions to open positions and open orders.
Reference Categories: dashboard/work tool, component/action table patterns.
Reference Notes: repeated operational actions belong close to the table row they affect; high-risk actions need compact buttons plus confirmation.
Do Not Copy: screenshot, exact copy, brand asset, paid/private reference content.

## Review

- Typography: keeps compact app-chrome scale.
- Colors: uses existing semantic tokens only.
- Spacing: table actions use compact 34px controls without layout shift.
- Radius: existing 6px button and 8px panel radius preserved.
- Interaction states: disabled, hover, confirm, and result summaries are present.
- Responsive behavior: table remains horizontally scrollable on narrow viewports.
- Accessibility: buttons have visible text, focus ring, and no icon-only controls.

## Exceptions

- Table action buttons are 34px high as desktop pointer-first controls; mobile table remains horizontally scrollable like existing data tables.
