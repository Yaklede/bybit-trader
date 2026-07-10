# UX Writing Run Manifest

Status: completed

## Target Files

- `apps/dashboard/src/App.jsx`

## Writing Contract

- WRITING.md reviewed: yes
- TERMS.md reviewed: yes
- Locale: Korean primary; market symbol remains technical
- Product concept: on-premise futures trading bot operations dashboard
- Tone: short, direct, professional 해요체

## Copy Review

- Korean: `수집 꺼짐`, `첫 집계 대기`, `수집 확인됨`, and `수집 확인 필요` distinguish the operational states without exposing transport internals
- English: no new user-facing English copy
- Terms: avoid internal resource and transport names in the panel
- Error messages: no new error message; existing dashboard error behavior remains
- Buttons and CTAs: no new action is added
- Empty/loading/success states: says that the first one-minute aggregation is pending rather than implying market data exists
- Naming: `시장 흐름 수집` describes the observable feature without suggesting it controls orders

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| No visibility for forward-only capture | 시장 흐름 수집 with explicit status and latest timestamps | Let the operator verify data collection without implying strategy use |

## Exceptions

No accepted exceptions.
