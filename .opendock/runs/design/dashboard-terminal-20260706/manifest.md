# Design Run Manifest

Status: ready

## Target Files

- `STYLESEED.md`
- `DESIGN.md`
- `assets/generated/images/bybit-dashboard-reference.png`
- `apps/dashboard/src/App.jsx`
- `apps/dashboard/src/styles.css`

## Design Contract

- DESIGN.md reviewed: yes, updated for terminal-style operations dashboard.
- STYLESEED.md reviewed or updated: yes, signature move updated.
- Key color/accent: #F7A707.
- Radius personality: sharp.
- Motion style: Snap.

## Palette Planning

Palette Source: Bybit-like yellow and charcoal direction plus existing project tokens.
Palette Mood: compact, operational, serious, not decorative.
Palette Role Map: light/dark semantic CSS tokens for canvas, surface, raised surface, muted surface, text, muted text, border, accent, focus, success, danger, warning.
Contrast Plan: body text uses high-contrast text tokens; primary action uses dark text on yellow; focus ring uses accent; status colors include text labels.
Color Risks: avoid pure black, yellow overuse, fake chart color, decorative gradients, and rainbow status rows.

## Layout Planning

Layout Type: dashboard / work tool.
First Gaze: account equity board and bot control state.
Primary Action: refresh, reconcile, and bot operation commands.
Section Architecture: exchange-style header, compact connection bar, dominant account board, sticky bot command rail, positions table, orders table, fills table, signal list.
Reference Categories: fintech dashboard, trading terminal, dev-tool operations.
Reference Notes: use `assets/generated/images/bybit-dashboard-reference.png` as the generated visual source; apply dense table-first scanning, real data surfaces only, no fake pages or sample trading rows.
Do Not Copy: screenshot, exact copy, brand asset, paid/private reference content.

## Review

- Typography: Pretendard, compact Korean dashboard scale.
- Colors: one yellow accent; semantic light/dark tokens only.
- Spacing: compact 8px rhythm with 12px panel grid.
- Radius: sharp 6-8px controls/panels.
- Interaction states: hover, focus, disabled, loading, empty, error states preserved.
- Responsive behavior: desktop two-column operation grid; mobile stacks without horizontal page scroll.
- Accessibility: labels, aria-live messages, visible focus, reduced motion, no color-only state.

## Exceptions

- None.
