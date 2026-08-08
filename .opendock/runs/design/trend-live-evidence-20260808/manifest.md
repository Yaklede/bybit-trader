# Design Run Manifest

Status: completed

## Target Files

- `apps/dashboard/src/App.jsx`
- `apps/dashboard/src/styles.css`

## Design Contract

- DESIGN.md reviewed: yes
- STYLESEED.md reviewed or updated: reviewed; existing lock retained
- Key color/accent: `#F7A707`
- Radius personality: sharp, panels 8px and controls 6px
- Motion style: Snap

## Palette Planning

Palette Source: existing Bybit-like project tokens
Palette Mood: compact, calm, operational
Palette Role Map: charcoal neutrals for canvas/surface/text/border, yellow for primary action and selection, green/red/yellow only for semantic state
Contrast Plan: keep body and table text at WCAG AA; pair every semantic color with text or icon; retain visible focus rings
Color Risks: no second accent, no gradient, no pure black, no decorative status colors

## Layout Planning

Layout Type: dashboard
First Gaze: account equity and H4 entry readiness
Primary Action: refresh or reconcile account state
Section Architecture: global status -> account/H4 evidence -> forward approval -> activity tables
Reference Categories: dashboard, component, fintech operations
Reference Notes: dense table-first inspection, tabular numerals, compact headings, one quiet accent, real empty/loading/error states
Do Not Copy: screenshot, exact copy, brand asset, paid/private reference content

## Review

- Typography: compact panel headings and tabular operational values follow the existing dashboard scale
- Colors: one `#F7A707` accent retained; semantic green, red, and yellow are paired with text or icons
- Spacing: H4 evidence is grouped as one full-width section with unframed metrics and tables
- Radius: panels remain 8px and controls remain 6px
- Interaction states: real loading, disabled, empty, success, warning, and danger states are present
- Responsive behavior: browser-verified at 1280x720 and 390x844; document scroll width equals viewport width in both cases
- Accessibility: visible mobile buttons are at least 44px high; focus ring, labels, reduced motion, and non-color status text remain intact
- Browser console: zero warning or error entries during overview and activity checks
- Theme: light and dark tokens both retain `#F7A707` as the only accent

## Exceptions

None.
