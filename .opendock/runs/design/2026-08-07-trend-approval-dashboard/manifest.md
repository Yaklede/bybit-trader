# Design Run Manifest

Status: complete

## Target Files

- `apps/dashboard/src/App.jsx`
- `apps/dashboard/src/styles.css`

## Design Contract

- DESIGN.md reviewed: yes
- STYLESEED.md reviewed or updated: reviewed; existing 2026-07-06 lock remains unchanged
- Key color/accent: Bybit-like amber `#F7A707`
- Radius personality: sharp 8px panels and 6px controls
- Motion style: functional feedback only, with reduced-motion support

## Palette Planning

Palette Source: existing DESIGN.md and STYLESEED.md semantic tokens
Palette Mood: compact, calm, operational
Palette Role Map: canvas/surface for hierarchy, amber for primary focus, green/red/yellow for semantic status only
Contrast Plan: existing light/dark token pairs, text-first status labels, visible amber focus ring
Color Risks: approval state must not look live-enabled; semantic status must not rely on color alone

## Layout Planning

Layout Type: dashboard
First Gaze: account equity, bot state, and strategy validation status
Primary Action: refresh the current operational and validation state
Section Architecture: sticky app header -> operating state strip -> tabs -> account board -> strategy validation band -> supporting operational panels
Reference Categories: exchange operations console, dense SaaS dashboard, validation checklist
Reference Notes: retain the existing compact table-first hierarchy and expose the full-width validation band without nested cards
Do Not Copy: screenshot, exact copy, brand asset, paid/private reference content

## Review

- Typography: retained the existing compact dashboard scale; panel and table headings remain subordinate to account metrics
- Colors: existing semantic tokens only; amber remains the sole accent and status also uses text labels
- Spacing: 8/10/12/14/16/20px values follow the existing compact rhythm
- Radius: existing 8px panel and 6px control language retained
- Interaction states: loading, unavailable, disabled, collecting, failed, stale, and review-ready states covered
- Responsive behavior: visually checked at 1440x1000 and 390x844 with no horizontal page overflow; mobile tables use compact two-row records
- Accessibility: semantic headings/tables, aria-live status, text-plus-color state, visible focus contract, 44px controls, reduced motion retained

## Exceptions

None.
