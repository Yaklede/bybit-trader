# UX Writing Run Manifest

Status: complete

## Target Files

- `apps/dashboard/src/App.jsx`

## Writing Contract

- WRITING.md reviewed: yes
- TERMS.md reviewed: yes
- Locale: Korean primary; exchange identifiers remain English
- Product concept: private futures trading bot operations console
- Tone: calm, direct, professional 해요체

## Copy Review

- Korean: plain labels applied to entry readiness, loss limits, and account reconciliation
- English: only machine reason codes remain in secondary disclosure
- Terms: NAV is presented as `성과 기준`; reconciliation is presented as `잔고 기록 대사`
- Error messages: blocked states explain both the cause and the next check
- Buttons and CTAs: no new mutation action; refresh remains the primary operator action
- Empty/loading/success states: not collected, entry allowed, and entry blocked are distinct
- Naming: no internal strategy profile name appears in the readiness headline

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| No visible persisted risk gate | 자동 진입 준비 / 진입 가능 / 진입 차단 | Make the operational decision explicit |
| Raw reason code only | Korean reason plus raw code in secondary detail | Preserve diagnosis without making code the primary copy |

## Exceptions

None.
