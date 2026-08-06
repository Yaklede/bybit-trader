# Design Run Manifest

Status: complete

## Target Files

- `apps/dashboard/src/App.jsx`
- `apps/dashboard/src/styles.css`

## Design Contract

- DESIGN.md reviewed: yes
- STYLESEED.md reviewed or updated: reviewed; existing 2026-07-06 lock remains valid
- Key color/accent: `#F7A707`
- Radius personality: sharp, 6px controls and 8px panels
- Motion style: Snap; no new decorative motion

## Palette Planning

Palette Source: existing Bybit-like project palette and StyleSeed semantic-role guidance
Palette Mood: compact, calm, operational
Palette Role Map: canvas/surface/text/border neutrals, yellow primary action, green success, amber warning, red blocked
Contrast Plan: status includes icon and text; body copy keeps the existing AA-oriented semantic tokens; focus ring remains yellow
Color Risks: do not color normal rows, do not add a second accent, and do not rely on red/green without labels

## Layout Planning

Layout Type: dashboard / technical operations console
First Gaze: total account equity, then automatic-entry readiness
Primary Action: inspect the exact entry blocker and refresh persisted operational state
Section Architecture: status header -> account board -> risk readiness band -> performance and market diagnostics -> strategy detail
Reference Categories: operations console, technical instrument, component state patterns
Reference Notes: use one exception-focused readiness panel, compact aligned metrics, and progressively disclosed reason details; avoid another equal-weight KPI wall
Do Not Copy: screenshot, exact copy, brand asset, paid/private reference content

## Review

- Typography: Pretendard hierarchy and tabular risk values match the compact dashboard contract
- Colors: light and dark blocked states use semantic danger with text and shield icon; no new accent
- Spacing: 8px scale and hairline grouping verified in rendered output
- Radius: existing 6px/8px sharp system preserved
- Interaction states: absent readiness has a next-step empty state; blocked/ready mappings are explicit; details remains keyboard operable
- Responsive behavior: Playwright verified 1440px, 768px, and 390px with no horizontal document overflow or overflowing risk rows
- Accessibility: status uses icon, text, and color; details has a visible focus ring; existing 44px controls and reduced-motion path remain intact

## Exceptions

None.
