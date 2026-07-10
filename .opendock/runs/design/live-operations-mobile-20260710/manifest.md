# Design Run Manifest

Status: active

## Target Files

- `apps/dashboard/src/App.jsx`
- `apps/dashboard/src/styles.css`

## Design Contract

- DESIGN.md reviewed: yes
- STYLESEED.md reviewed or updated: reviewed; existing lock retained
- Key color/accent: `#F7A707`
- Radius personality: sharp, 6px controls and 8px panels
- Motion style: Snap with reduced-motion support

## Palette Planning

Palette Source: existing project brand contract and StyleSeed semantic-token guidance
Palette Mood: compact, calm, operational fintech; yellow accent with charcoal neutrals
Palette Role Map: canvas/surface/text/border use neutral ramps; yellow is primary action; green/red are PnL semantics only
Contrast Plan: body text and controls target WCAG AA; focus uses visible accent outline in both themes
Color Risks: avoid muddy warm palettes, extra or competing accents, low contrast, and semantic success/warning/error confusion

## Layout Planning

Layout Type: dashboard / mobile operations work tool
First Gaze: bot state, current BTC price, and account equity
Primary Action: refresh the active view; emergency stop remains immediately reachable
Section Architecture: 56px compact status header -> real view tabs -> active operational surface -> contextual actions and diagnostics
Reference Categories: dashboard, mobile app, component system
Reference Notes: preserve dense exchange-style scanning while moving infrequent diagnostics out of the first mobile viewport; polling follows the active view
Do Not Copy: screenshot, exact copy, brand asset, paid/private reference content

## Review

- Typography: Pretendard/system Korean stack, fixed app-chrome scale, zero letter spacing
- Colors: one yellow accent, neutral surfaces, semantic PnL colors only
- Spacing: 8px rhythm with compact grouped controls
- Radius: one sharp 6px/8px family
- Interaction states: hover, focus, disabled, loading, empty, and error states retained
- Responsive behavior: 56px mobile header, no page-level horizontal overflow, tables fold for 360px
- Accessibility: visible labels/focus, icon button names, 44px mobile targets, reduced-motion support

## Exceptions

No accepted exceptions.
