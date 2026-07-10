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

- Korean: `최근 60분 공통 수집` describes completed shared minutes without implying profitable strategy data
- English: no new user-facing English copy
- Terms: avoids transport and storage implementation terms
- Error messages: no new error message; `집계 대기` distinguishes unavailable coverage from a successful zero result
- Buttons and CTAs: no new action is added
- Empty/loading/success states: values show `집계 대기` before the first completed observation window
- Naming: `호가 · 테이커 개별 수집` gives the order of the two values shown on the right

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| 최신 시각만 표시 | 최근 60분 공통 수집과 개별 수집 수 표시 | 데이터가 연속적으로 축적되는지 확인 |

## Exceptions

No accepted exceptions.
