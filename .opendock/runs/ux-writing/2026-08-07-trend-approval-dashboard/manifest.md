# UX Writing Run Manifest

Status: complete

## Target Files

- `apps/dashboard/src/App.jsx`

## Writing Contract

- WRITING.md reviewed: yes
- TERMS.md reviewed: yes
- Locale: ko-KR
- Product concept: private futures trading bot operations console
- Tone: concise Korean 해요체 with explicit safety state

## Copy Review

- Korean: concise 해요체 used for descriptions, status, and recovery guidance
- English: strategy and gate identifiers are translated at the UI boundary; market symbols remain standard notation
- Terms: no internal candidate or policy identifiers are exposed in visible copy
- Error messages: unavailable state identifies API deployment as the next check
- Buttons and CTAs: no new command button introduced; existing refresh action remains the primary command
- Empty/loading/success states: first observation, disabled collection, unavailable API, and loading states covered
- Naming: `전진 검증`, `승인 기준`, and `최근 관측 기록` distinguish observation from live execution

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| No validation view | 전진 검증 and 승인 기준 labels | Separate observation progress from live trading permission |

## Exceptions

None.
