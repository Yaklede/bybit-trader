# Design Run Manifest

Status: updated

## Target Files

- `STYLESEED.md`
- `DESIGN.md`
- `apps/dashboard/package.json`
- `apps/dashboard/index.html`
- `apps/dashboard/vite.config.js`
- `apps/dashboard/src/App.jsx`
- `apps/dashboard/src/styles.css`

## Design Contract

- DESIGN.md reviewed: created and reviewed for the dashboard MVP.
- STYLESEED.md reviewed or updated: updated for dashboard MVP.
- Key color/accent: #F7A707.
- Radius personality: sharp.
- Motion style: Snap.

## Palette Planning

Palette Source: StyleSeed custom lock + Bybit-like yellow/charcoal direction; public color reference indicates Bybit yellow around #F7A707, but the UI uses semantic tokens rather than copied assets.
Palette Mood: calm, operational, high-trust, not decorative.
Palette Role Map: light canvas #F4F6F8, light surface #FFFFFF, light text #171A1F, light border #D6DBE1, dark canvas #0F1115, dark surface #171A21, dark text #F2F4F7, accent #F7A707, semantic success/danger/warning.
Contrast Plan: primary action uses dark text on yellow; body text uses high-contrast charcoal/near-white on surface; focus ring uses accent; status colors include text plus labels.
Color Risks: avoid pure black, yellow overuse, rainbow status rows, color-only PnL meaning, and decorative warning/success colors.

## Layout Planning

Layout Type: dashboard / work tool.
First Gaze: account equity and bot state.
Primary Action: refresh/reconcile the live account snapshot; evaluate-and-submit is present but visually secondary because it can place orders.
Section Architecture: single real dashboard surface, sticky header, connection settings panel, account summary band, positions and open orders, recent executions/signals, control rail.
Reference Categories: dashboard, fintech, dev-tool.
Reference Notes: apply compact navigation, table-first scanning, restrained controls, clear empty/error/loading states.
Do Not Copy: screenshot, exact copy, brand asset, paid/private reference content.

## Review

- Typography: Pretendard, Korean labels, desktop app-chrome sizes.
- Colors: one yellow accent; light/dark tokens; PnL semantic colors only.
- Spacing: 8px grid, compact but scannable.
- Radius: sharp personality, 6-8px controls/panels.
- Interaction states: hover, focus, disabled, loading, empty, error states required.
- Responsive behavior: no fake page navigation; full-width desktop grid; mobile stacks without page horizontal scroll.
- Accessibility: contrast, focus, touch target, labels, aria-live errors, reduced motion.

## Exceptions

- None.
