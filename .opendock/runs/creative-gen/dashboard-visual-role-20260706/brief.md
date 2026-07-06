<!-- OPENDOCK:START id=files:.opendock/templates/creative-gen/GENERATION_BRIEF.md dock=opendock/creative-gen-ultrawork path=.opendock/templates/creative-gen/GENERATION_BRIEF.md -->
# Generation Brief

Status: active
Mode: asset-analysis

## Purpose

현재 React 대시보드가 AI 생성물처럼 보이는 원인을 분석하고, 실제 선물 매매봇 운영 화면처럼 역할이 분명한 구조로 재구성한다.

## Audience

Bybit 선물 매매봇을 로컬/온프레미스에서 운영하면서 계정, 포지션, 주문, 체결, 전략 신호, 봇 제어를 확인하는 운영자.

## Modes

Allowed values: image, logo, favicon, video, audio, asset-analysis.

## Direction

- Subject: Bybit 선물 매매봇 운영 대시보드의 시각 구조와 역할 표현.
- Style: 거래 터미널형 작업 화면, compact, table-first, one focal account readout.
- Tone: 차분하고 실무적인 운영 도구.
- Brand or design references: Bybit-like yellow accent with charcoal neutrals, StyleSeed dashboard/work-tool rules.
- Must include: 계정 현황, 봇 제어, 포지션, 미체결 주문, 최근 체결, 최근 신호, 연결 설정.
- Must avoid: fake pages, fake charts, fake sample rows, all-even KPI card grid, decorative gradients, oversized marketing copy.

## Prompt Plan

- Prompt objective: 기존 UI를 운영 대시보드답게 재구성할 시각 기준을 만든다.
- Final generation prompt: Redesign the Bybit futures trading bot dashboard as a real operations terminal: a thin exchange-style header, compact connection controls, one dominant account equity board, a sticky bot control panel, and table-first surfaces for positions, open orders, fills, and strategy signals. Use a Bybit-like yellow accent with charcoal neutrals, light/dark tokens, sharp 6-8px radius, Pretendard Korean UI copy, no fake data, no fake pages, no decorative charts, no marketing hero, and clear empty/error/loading states.
- Negative prompt or avoid list: equal KPI-card grid, generic SaaS hero, fake navigation, fake chart placeholders, gradients, glow effects, stock imagery, sample trading rows, oversized rounded cards.
- Quality bar: 데스크톱에서 역할이 한눈에 보이고, 모바일에서 가로 스크롤이 없으며, 실제 데이터가 없어도 각 영역의 책임이 명확해야 한다.
- Planned generator or model: Codex local source analysis and implementation; no external image generation provider used.
- If the output is visual, use an image generation/editing model. Do not hand-draw a placeholder with SVG, HTML, CSS, or basic shapes unless the user explicitly asks for vector/source artwork.

## Output Requirements

- Target path: `ASSET_INVENTORY.md`, `ASSET_REPORT.md`, `apps/dashboard/src/App.jsx`, `apps/dashboard/src/styles.css`.
- Dimensions or aspect ratio: responsive desktop web dashboard, validated at wide desktop and mobile viewport.
- Format: Markdown analysis plus React/CSS implementation.
- Quantity: one revised dashboard surface.
- Accessibility: visible focus states, Korean labels, empty/error/loading states, reduced-motion support.
- Localization: Korean UI copy.

## Constraints

- Rights and license: no third-party asset copied; StyleSeed and Bybit-like color direction used as abstract design guidance.
- Privacy or sensitive content: no credential or private trading token included in prompts or manifests.
- File size budget: keep frontend source lightweight.
- Deadline: current iteration.

## Review Criteria

- Visual or audio quality: 화면이 AI 카드 모음이 아니라 운영 터미널/대시보드로 읽힌다.
- Brand fit: Bybit-like yellow/charcoal tokens remain controlled by semantic CSS variables.
- Technical readiness: Vite build and OpenDock harness pass.
- Handoff readiness: current target files and analysis documents are listed in the manifest.
<!-- OPENDOCK:END id=files:.opendock/templates/creative-gen/GENERATION_BRIEF.md dock=opendock/creative-gen-ultrawork path=.opendock/templates/creative-gen/GENERATION_BRIEF.md -->
