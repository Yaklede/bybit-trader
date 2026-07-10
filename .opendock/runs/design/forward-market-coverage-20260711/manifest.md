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
Palette Role Map: existing neutral surfaces remain unchanged; existing semantic collection status remains the only status color
Contrast Plan: existing tokenized text, panel, focus, and semantic status contrast remains unchanged
Color Risks: avoid a second accent, status-by-color-only communication, and a fifth equal-weight metric card

## Layout Planning

Layout Type: dashboard / operations work tool
First Gaze: account equity and bot state remain unchanged
Primary Action: refresh current operations data remains unchanged
Section Architecture: compact status header -> real navigation -> overview panels -> forward-market collection timestamps and recent common-source coverage
Reference Categories: dashboard, component system
Reference Notes: place coverage as precise supporting rows beneath the existing four-stat collection panel; do not alter the primary account board or add navigation
Do Not Copy: screenshot, exact copy, brand asset, paid/private reference content

## Review

- Typography: existing fixed Pretendard/system scale and zero letter spacing
- Colors: existing semantic tokens only
- Spacing: existing compact detail-row rhythm; no new card or grid pattern
- Radius: existing sharp 6px/8px family
- Interaction states: read-only values retain existing disabled and waiting states through `집계 대기`
- Responsive behavior: verified at 1440px and 390px; rows use the existing mobile flex fallback and preserve bounded value text without horizontal scrolling
- Accessibility: all numeric values have Korean text labels and collection status remains explicit in text

## Exceptions

No accepted exceptions.
