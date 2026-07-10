# UX Writing Run Manifest

Status: active

## Target Files

- `apps/dashboard/src/App.jsx`

## Writing Contract

- WRITING.md reviewed: yes
- TERMS.md reviewed: yes
- Locale: Korean primary; market symbols and API remain technical terms
- Product concept: on-premise futures trading bot operations dashboard
- Tone: calm, direct, professional 해요체

## Copy Review

- Korean: commands and status labels use short, natural Korean
- English: limited to market symbols, runtime modes, and API labels where translation would reduce clarity
- Terms: public copy avoids payload, endpoint, schema, and internal resource names
- Error messages: state what failed and tell the operator what to check or retry
- Buttons and CTAs: action-first labels such as 새로고침, 봇 정지, 재가동
- Empty/loading/success states: distinguish not loaded, no live data, request failure, and completed action
- Naming: 개요, 매매, 활동, 설정/진단 map to real working views only

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| One long operations page | 개요 / 매매 / 활동 / 설정·진단 | Separate real workflows and reduce mobile scanning load |
| Generic request completion | Requested action plus refreshed-state result | Confirm the operation and expose partial refresh failure |

## Exceptions

No accepted exceptions.
