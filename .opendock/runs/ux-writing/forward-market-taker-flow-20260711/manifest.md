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

- Korean: `테이커 체결 집계` and `최근 테이커 체결 수집` describe observable data without implying an order action or a proven signal
- English: no new user-facing English copy
- Terms: public UI avoids WebSocket, payload, and internal model names
- Error messages: no new error message; existing dashboard error behavior remains
- Buttons and CTAs: no new action is added
- Empty/loading/success states: `첫 집계 대기`, `수집 확인됨`, and `수집 확인 필요` now require both order-book and taker-trade bars
- Naming: `시장 흐름 수집` continues to describe collection rather than automated strategy control

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| 호가와 강제청산 데이터 | 호가, 테이커 체결, 강제청산 데이터 | 수집 범위를 정확하게 표현 |
| 호가 수집만 확인 | 호가와 테이커 체결 수집 모두 확인 | 불완전한 수집을 정상으로 보이지 않게 함 |

## Exceptions

No accepted exceptions.
