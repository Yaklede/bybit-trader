# Design Run Manifest

Status: active

## Target Files

- `apps/dashboard/src/App.jsx`
- `apps/dashboard/src/styles.css`

## Design Contract

- DESIGN.md reviewed: yes
- STYLESEED.md reviewed or updated: reviewed
- Key color/accent: Bybit-like yellow `#F7A707`
- Radius personality: sharp, 6px controls and 8px panels
- Motion style: snap, reduced-motion respected by existing CSS

## Palette Planning

Palette Source: existing `DESIGN.md` semantic tokens
Palette Mood: compact exchange operations, calm and high-contrast
Palette Role Map: canvas/surface/text/border/accent/success/danger reused from existing dashboard tokens
Contrast Plan: strategy status uses text plus badge, not color alone; controls keep existing focus ring
Color Risks: no new accent, no gradient, no decorative color

## Layout Planning

Layout Type: dashboard
First Gaze: account status, bot state, active strategy profile
Primary Action: refresh state, then operate bot controls
Section Architecture: strategy profile block inside the existing bot control rail before operational actions
Reference Categories: dashboard, component
Reference Notes: use dense, table-first work-tool grouping; avoid fake pages and marketing composition
Do Not Copy: screenshot, exact copy, brand asset, paid/private reference content

## Review

- Typography: uses existing section scale and compact 13-16px support text
- Colors: uses semantic tokens only
- Spacing: follows existing 8px grid rhythm
- Radius: matches current sharp dashboard controls
- Interaction states: active/disabled strategy buttons use existing button states
- Responsive behavior: profile buttons stack on mobile
- Accessibility: section has aria-label, controls retain visible focus and 44px mobile touch target

## Exceptions

None.
