# Design Run Manifest

Status: complete

## Target Files

- `apps/dashboard/src/App.jsx`

## Design Contract

- DESIGN.md reviewed: yes
- STYLESEED.md reviewed or updated: reviewed; no token changes
- Key color/accent: existing Bybit-like yellow `#F7A707`
- Radius personality: sharp, existing 8px panels
- Motion style: snap; no new motion

## Palette Planning

Palette Source: existing project tokens and Bybit-like operations dashboard contract
Palette Mood: compact, calm, operational
Palette Role Map: existing canvas, surface, text, muted text, border, yellow primary, semantic green/red
Contrast Plan: use existing semantic text and value formatting; no color-only status change
Color Risks: do not introduce a second accent or alter panel hierarchy for a metric-label correction

## Layout Planning

Layout Type: dashboard / work tool
First Gaze: current account equity and bot state
Primary Action: reconcile current account state
Section Architecture: account snapshot -> live performance -> market synchronization -> operating surfaces
Reference Categories: dashboard / work tool
Reference Notes: preserve dense scan order and distinguish account risk from realized-trade statistics in the existing performance detail list.
Do Not Copy: screenshot, exact copy, brand asset, paid/private reference content

## Review

- Typography: existing dashboard scale; no new type
- Colors: existing semantic tokens only
- Spacing: existing detail-list rows; no layout shift
- Radius: unchanged
- Interaction states: existing loading/error/empty states remain authoritative
- Responsive behavior: text rows remain stackable; no new horizontal content
- Accessibility: labels are explicit, values are not conveyed by color alone, existing focus and reduced-motion rules remain

## Exceptions

- The external StyleSeed URL could not be opened by the network safety policy; the checked-in `STYLESEED.md` remains the source of truth and no new visual tokens were added.
