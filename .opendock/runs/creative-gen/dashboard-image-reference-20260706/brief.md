<!-- OPENDOCK:START id=files:.opendock/templates/creative-gen/GENERATION_BRIEF.md dock=opendock/creative-gen-ultrawork path=.opendock/templates/creative-gen/GENERATION_BRIEF.md -->
# Generation Brief

Status: active
Mode: image, asset-analysis

## Purpose

Bybit 선물 매매봇 운영 대시보드의 기준 시안을 이미지로 먼저 생성하고, 그 시안을 React/CSS 화면 구현의 시각 기준으로 사용한다.

## Audience

온프레미스 환경에서 봇의 계정 상태, 포지션, 주문, 체결, 전략 신호, 제어 명령을 확인하는 운영자.

## Modes

Allowed values: image, logo, favicon, video, audio, asset-analysis.

## Direction

- Subject: BTCUSDT 선물 매매봇 운영 대시보드.
- Style: professional trading terminal, dense operations dashboard, Korean UI, table-first.
- Tone: calm, precise, utilitarian.
- Brand or design references: Bybit-like yellow accent, charcoal neutrals, StyleSeed compact dashboard rules.
- Must include: exchange-style header, account equity focal panel, bot control panel, positions table, open orders table, recent fills, strategy signals, connection controls, light/dark mode indication.
- Must avoid: fake navigation pages, marketing hero, decorative chart art, fake sample trading rows, glassmorphism, gradients, oversized cards, pure black.

## Prompt Plan

- Prompt objective: 대시보드 구현 전에 명확한 시각 기준 이미지를 생성한다.
- Final generation prompt: A high-fidelity desktop web UI mockup for a Korean Bybit futures trading bot operations dashboard. Build it as a real trading terminal, not a marketing page. Use a thin exchange-style top header with brand on the left, market/status cells across the top, and action buttons on the right. Below it, a compact connection settings row with API base, control key, symbol BTCUSDT, and coin USDT. Main content uses a two-column operations layout: left wide column with a dominant account equity panel, positions table, open orders table, and recent fills table; right narrow column with a bot control panel and strategy signal panel. Use Bybit-like yellow as the only accent, charcoal neutrals, sharp 6-8px corners, hairline borders, no glow, no gradients, no fake charts. Korean UI labels only. Empty states should say that data has not been loaded yet, without fake sample trading rows. The image should look like a serious production operations dashboard that a trader would keep open during live monitoring.
- Negative prompt or avoid list: landing page, hero section, marketing copy, fake navigation, fake charts, candlestick decoration, generic AI dashboard, glassmorphism, neon glow, gradients, large rounded cards, sample trading rows, stock images, emoji icons, pure black.
- Quality bar: The generated image must make the dashboard role clear within the first glance, with one dominant account focal point and visible bot command area. It must be implementable in React/CSS using real data surfaces only.
- Planned generator or model: OpenAI image generation via Codex image tool.
- If the output is visual, use an image generation/editing model. Do not hand-draw a placeholder with SVG, HTML, CSS, or basic shapes unless the user explicitly asks for vector/source artwork.

## Output Requirements

- Target path: `assets/generated/images/bybit-dashboard-reference.png`
- Dimensions or aspect ratio: 16:9 desktop dashboard reference.
- Format: PNG.
- Quantity: 1.
- Accessibility: alt text recorded in manifest.
- Localization: Korean UI labels.

## Constraints

- Rights and license: generated image for this project only; no copied brand asset, no third-party screenshot.
- Privacy or sensitive content: no account number, API key, private token, or live balance in prompt.
- File size budget: under 10MB.
- Deadline: current implementation pass.

## Review Criteria

- Visual or audio quality: useful visual reference, not decorative.
- Brand fit: Bybit-like yellow and charcoal without copying official assets.
- Technical readiness: informs concrete React/CSS layout decisions.
- Handoff readiness: output path, prompt, model, date, rights, and review are documented.
<!-- OPENDOCK:END id=files:.opendock/templates/creative-gen/GENERATION_BRIEF.md dock=opendock/creative-gen-ultrawork path=.opendock/templates/creative-gen/GENERATION_BRIEF.md -->
