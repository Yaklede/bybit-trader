<!-- OPENDOCK:START id=files:.opendock/templates/creative-gen/OUTPUT_MANIFEST.md dock=opendock/creative-gen-ultrawork path=.opendock/templates/creative-gen/OUTPUT_MANIFEST.md -->
# Output Manifest

## Generated Outputs

- `assets/generated/images/bybit-dashboard-reference.png`
- `ASSET_INVENTORY.md`
- `ASSET_REPORT.md`

Output spec: PNG, dimensions or aspect ratio: 16:9 desktop dashboard reference image, approximately 1.1MB.
Alt text: Korean dark trading terminal dashboard reference for a Bybit futures bot, with market status header, connection settings, account summary, bot control panel, positions, orders, fills, and strategy signal sections.

## Prompt

`[$opendock-creative-gen-ultrawork] -> 대시 보드 이미지 생성하고 그걸 옮겨`

## Prompt Draft

Create a desktop dashboard mockup image for a Korean Bybit futures trading bot. It should show account state, bot controls, positions, orders, fills, and signals. Use a Bybit-like yellow accent, dark/charcoal UI, and make it look like an operations dashboard rather than a generic AI-generated dashboard.

## Prompt Review

Strengthened subject to a BTCUSDT futures trading bot operations terminal. Composition now requires a thin exchange-style top header, compact connection settings row, one dominant account equity panel, table-first data surfaces, and a narrow bot control/signal column. Constraints explicitly ban fake navigation pages, marketing hero, fake charts, decorative candlestick art, fake sample trading rows, glassmorphism, gradients, neon glow, oversized rounded cards, emoji icons, and pure black. Quality criteria require the image to be directly translatable into React/CSS with real data surfaces and clear empty states.

## Final Prompt

A high-fidelity desktop web UI mockup for a Korean Bybit futures trading bot operations dashboard. Build it as a real trading terminal, not a marketing page. Use a thin exchange-style top header with brand on the left, market/status cells across the top, and action buttons on the right. Below it, a compact connection settings row with API base, control key, symbol BTCUSDT, and coin USDT. Main content uses a two-column operations layout: left wide column with a dominant account equity panel, positions table, open orders table, and recent fills table; right narrow column with a bot control panel and strategy signal panel. Use Bybit-like yellow as the only accent, charcoal neutrals, sharp 6-8px corners, hairline borders, no glow, no gradients, no fake charts. Korean UI labels only. Empty states should say that data has not been loaded yet, without fake sample trading rows. The image should look like a serious production operations dashboard that a trader would keep open during live monitoring. 16:9 desktop screenshot style, crisp UI, no browser chrome.

## Vector/SVG Notes

If SVG/source vector output was explicitly requested, record:

- Vector requested: yes/no
- Structure: paths/groups/defs strategy and why it is not a placeholder
- Accessibility: `<title>` or `aria-label`
- Palette: one accent and controlled colors
- Safety: no script, external href, base64 payload, event handler, raster embed, doctype/entity, or foreignObject

## Tool

Codex image generation tool.

## Model

unknown

## Date

2026-07-06

## Rights

Generated for this bybit-trader workspace. No copied third-party screenshot, logo, trading account data, API key, private token, or live balance was used. Bybit-like colors are used only as abstract visual direction.

## Review

Generated image was reviewed visually and used as the implementation reference. The React/CSS dashboard was updated to mirror the generated image's top market strip, connection settings row, account summary metrics, bot control panel, strategy signal panel, and table-first trade surfaces while keeping real data empty states and avoiding fake sample rows.

## Revision History

- Initial draft: prompt asked for a desktop operations dashboard reference image.
- Generated image: `assets/generated/images/bybit-dashboard-reference.png`.
- Implementation pass: applied the image's structure to `apps/dashboard/src/App.jsx` and `apps/dashboard/src/styles.css`.
<!-- OPENDOCK:END id=files:.opendock/templates/creative-gen/OUTPUT_MANIFEST.md dock=opendock/creative-gen-ultrawork path=.opendock/templates/creative-gen/OUTPUT_MANIFEST.md -->
