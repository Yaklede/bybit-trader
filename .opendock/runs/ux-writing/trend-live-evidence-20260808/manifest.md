# UX Writing Run Manifest

Status: completed

## Target Files

- `apps/dashboard/src/App.jsx`

## Writing Contract

- WRITING.md reviewed: yes
- TERMS.md reviewed: yes
- Locale: ko
- Product concept: private futures trading bot operations dashboard
- Tone: professional, direct, easy Korean in 해요체

## Copy Review

- Korean: explain account, risk, reconciliation, and H4 evidence in user-facing terms
- English: retain only approved market/API identifiers and provider values
- Terms: avoid internal storage/schema names and strategy code names in visible labels
- Error messages: state what is unavailable and tell the operator to refresh or reconcile
- Buttons and CTAs: keep refresh/reconcile actions short and verb-led
- Empty/loading/success states: distinguish not queried, unavailable, and no recorded trade
- Naming: use `4시간 추세 전략`, `신규 진입`, `잔고 기록 대사`, and `계좌 낙폭`

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| H4 실거래 상태 | 4시간 추세 전략 | Internal timeframe shorthand is expanded for operators. |
| risk state | 계좌 보호 상태 | Avoid internal implementation terms. |
| `TREND_ENTRY_FILL_RECONCILED` | 거래소에서 진입 체결과 포지션을 확인했어요. | Do not expose an internal lifecycle code as the primary operator message. |

## Review Result

- State, risk, wallet reconciliation, execution, closure, and account-transaction labels are written in Korean.
- Empty states distinguish an unconfigured execution path from an enabled path with no observations.
- Entry-blocking messages state the condition and direct the operator to synchronize or inspect the account.
- Exact provider identifiers are limited to diagnostic contexts where the operator needs them.

## Exceptions

Market symbols, `API`, `USDT`, and provider status identifiers may remain unchanged where exact identification is required.
