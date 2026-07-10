# Design Run Manifest

Status: completed

## Target Files

- `apps/dashboard/src/App.jsx`

## Design Contract

- DESIGN.md reviewed: yes
- STYLESEED.md reviewed or updated: reviewed; existing lock retained. The required external StyleSeed reference was unavailable to the reader, so the local lock is the applied source of truth.
- Key color/accent: `#F7A707`
- Radius personality: sharp, existing 6px controls and 8px panels
- Motion style: Snap; no new motion

## Palette Planning

Palette Source: existing project design contract and locked StyleSeed tokens
Palette Mood: compact, calm, operational fintech
Palette Role Map: existing neutral surfaces remain unchanged; semantic green/warning/red express collection state without adding an accent color
Contrast Plan: existing tokenized text, panel, focus, and semantic state contrast remains unchanged
Color Risks: avoid a second accent, decorative indicator, low-contrast muted status, and status-by-color-only communication

## Layout Planning

Layout Type: dashboard / operations work tool
First Gaze: account equity and bot state remain unchanged
Primary Action: refresh current operations data remains unchanged
Section Architecture: compact status header -> real navigation -> overview panels -> market synchronization and forward market-data collection inspection
Reference Categories: dashboard, component system
Reference Notes: add one factual inspection panel to the existing overview rather than a new navigation surface or KPI strip
Do Not Copy: screenshot, exact copy, brand asset, paid/private reference content

## Review

- Typography: existing fixed Pretendard/system scale and zero letter spacing
- Colors: existing semantic tokens only
- Spacing: existing compact panel rhythm
- Radius: existing sharp 6px/8px family
- Interaction states: read-only data panel reuses existing loading and empty states; disabled, waiting, fresh, and attention status copy is covered
- Responsive behavior: verified at 1440px and 390px; existing single-column fallback applies with no horizontal page scroll
- Accessibility: text labels describe enabled, waiting, fresh, and attention states; no interaction was added

## Exceptions

No accepted exceptions.
