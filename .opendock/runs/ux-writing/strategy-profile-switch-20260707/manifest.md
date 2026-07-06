# UX Writing Run Manifest

Status: active

## Target Files

- `apps/dashboard/src/App.jsx`

## Writing Contract

- WRITING.md reviewed: yes
- TERMS.md reviewed: yes
- Locale: ko
- Product concept: Bybit futures trading bot operations dashboard
- Tone: 해요체, professional but easy

## Copy Review

- Korean: strategy profile copy uses short, direct labels
- English: technical ids remain hidden from primary UI
- Terms: API remains allowed; internal endpoint/payload wording avoided
- Error messages: profile change failure tells the user to check access key and server status
- Buttons and CTAs: profile buttons use action text ending in `적용`
- Empty/loading/success states: empty profile state says `조회 전`
- Naming: `공격형`, `안정형 비교`, `운영 루프`, `적용 범위`

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| internal profile ids only | Korean profile names and scope labels | Operator-facing clarity |

## Exceptions

None.
