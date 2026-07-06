# DESIGN.md

This project uses a compact Korean operations dashboard style for the Bybit futures trading bot.

## Typography

- Primary font: Pretendard with system Korean fallbacks.
- Surface: desktop app-chrome.
- Page title: 24px, 700.
- Section title: 18px, 700.
- Body and table text: 14-16px.
- Letter spacing: 0 for Korean text.
- Do not use viewport-based font sizing.

## Color

Use semantic CSS tokens and support light and dark mode. The visual direction is Bybit-like yellow plus charcoal neutrals, without copying brand assets or using pure black.

Light mode:

- Canvas: `#F4F6F8`.
- Surface: `#FFFFFF`.
- Raised surface: `#FFFFFF`.
- Muted surface: `#EEF1F4`.
- Primary text: `#171A1F`.
- Muted text: `#69707D`.
- Border: `#D6DBE1`.
- Primary accent: `#F7A707`.
- Primary accent hover: `#DF9300`.
- Accent soft background: `#FFF3D2`.
- Accent text: `#171A1F`.

Dark mode:

- Canvas: `#0F1115`.
- Surface: `#171A21`.
- Raised surface: `#1C2029`.
- Muted surface: `#242936`.
- Primary text: `#F2F4F7`.
- Muted text: `#A8B0BD`.
- Border: `#303746`.
- Primary accent: `#F7A707`.
- Primary accent hover: `#FFC45A`.
- Accent soft background: `rgba(247, 167, 7, 0.16)`.
- Accent text: `#181B20`.

Semantic colors:

- Success: `#16834A` light, `#31C77B` dark.
- Success background: `#E5F7ED` light, `rgba(49, 199, 123, 0.14)` dark.
- Danger: `#D83B3B` light, `#FF6969` dark.
- Danger background: `#FFEBEB` light, `rgba(255, 105, 105, 0.14)` dark.
- Warning: `#9B6400` light, `#F7C65C` dark.
- Warning background: `#FFF5D7` light, `rgba(247, 198, 92, 0.15)` dark.
- Do not use pure black, gradients, rainbow status rows, or extra decorative accents.

## Layout

- Layout type: dashboard / work tool.
- First gaze: account equity and bot state.
- Primary action: refresh or reconcile the current account state.
- Tables remain the primary inspection surface.
- Cards and panels use 8px grid spacing and compact grouping.
- Use an exchange-style status header, compact connection controls, one dominant account board, bot control rail, and table-first trade surfaces.
- Avoid equal-weight KPI card grids as the primary dashboard composition.
- Navigation must only expose real, working surfaces. Do not add placeholder pages or fake links.
- Wide desktop layouts should use the available workspace width rather than leaving a large blank right edge.
- Mobile layout must stack without horizontal page scroll.

## Components

- Radius personality: sharp.
- Panel radius: 8px.
- Button/input radius: 6px.
- Button/input height: 40-44px.
- Icons: lucide line icons only, currentColor style.
- Focus states must be visible on links, buttons, and inputs.

## Motion

- Motion style: Snap.
- Keep motion short and functional.
- Respect `prefers-reduced-motion`.

## Copy

- Primary UI language: Korean.
- Tone: 해요체, professional but easy.
- API and market symbols may remain as technical terms.
- Error copy must include a next action.
- Empty states must distinguish "not queried yet", "query failed", and "no live data" without inserting fake trading rows.

## Do Not

- Do not add a marketing hero to the dashboard.
- Do not use emoji as icons.
- Do not place UI cards inside other UI cards.
- Do not use decorative gradient or glow backgrounds.
- Do not expose private keys or raw provider errors in the UI.
