# UX Writing Run Manifest

Status: updated

## Target Files

- `apps/dashboard/src/App.jsx`
- `modules/bot-api/src/main/kotlin/dev/yaklede/bybittrader/api/execution/ExecutionRoutes.kt`

## Writing Contract

- WRITING.md reviewed: yes.
- TERMS.md reviewed: yes.
- Locale: ko.
- Product concept: Bybit 선물 매매봇 운영 테스트.
- Tone: 해요체, direct operational copy, clear risk wording.

## Copy Review

- Korean: manual test labels, confirmation dialogs, and validation messages are Korean.
- English: acknowledgement constants and API route names remain internal contract values.
- Terms: visible copy avoids payload/schema/auth wording.
- Error messages: new validation errors explain what to correct.
- Buttons and CTAs: 수동 시장가 주문, 취소, 청산 name the action.
- Empty/loading/success states: existing table empty/loading states are preserved.
- Naming: strategy order and manual order are separated in copy.

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| TESTNET 시장가 주문 | 수동 시장가 주문 | Same control now works for TESTNET and LIVE with acknowledgement. |
| reduce-only 시장가 | 포지션 감소 전용 시장가 | Korean explanation is clearer in a high-risk confirmation. |
| Quantity must be greater than 0. | 수량은 0보다 커야 해요. | User-facing validation should be Korean and actionable. |

## Exceptions

- `LIVE_MARKET_ORDER`, `LIVE_CLOSE_POSITION`, `TESTNET_MARKET_ORDER`, `TESTNET_CLOSE_POSITION` remain exact acknowledgement constants.
