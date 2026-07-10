# Design Run Manifest

Status: completed

## Target Files

- `apps/dashboard/src/App.jsx`

## Design Contract

- DESIGN.md reviewed: yes
- STYLESEED.md reviewed or updated: reviewed; existing lock retained
- Key color/accent: `#F7A707`
- Radius personality: sharp; existing 6px controls and 8px panels
- Motion style: Snap; no new motion

## Palette Planning

Palette Source: existing project design contract and locked StyleSeed tokens
Palette Mood: compact, calm, operational fintech
Palette Role Map: existing neutral surfaces remain unchanged; semantic green indicates complete collection and neutral indicates waiting or attention
Contrast Plan: existing tokenized text, panel, focus, and semantic status contrast remains unchanged
Color Risks: avoid a second accent, decorative indicator, low-contrast muted status, and status-by-color-only communication

## Layout Planning

Layout Type: dashboard / operations work tool
First Gaze: account equity and bot state remain unchanged
Primary Action: refresh current operations data remains unchanged
Section Architecture: compact status header -> real navigation -> overview panels -> market synchronization and forward-market collection inspection
Reference Categories: dashboard, component system
Reference Notes: extend the existing factual collection panel with one additional timestamp; do not add navigation, controls, or a KPI strip
Do Not Copy: screenshot, exact copy, brand asset, paid/private reference content

## Review

- Typography: existing fixed Pretendard/system scale and zero letter spacing
- Colors: existing semantic tokens only
- Spacing: existing compact panel rhythm with the same four-column stat grid
- Radius: existing sharp 6px/8px family
- Interaction states: read-only data panel reuses existing loading and empty states; disabled, waiting, verified, and attention statuses remain explicit
- Responsive behavior: verified at 1440px and 390px; the existing single-column fallback keeps all four stats and all three timestamps within the viewport without horizontal scrolling
- Accessibility: text labels identify all collected data; status text does not rely on color

## Exceptions

No accepted exceptions.
