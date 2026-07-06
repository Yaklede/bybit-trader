# UX Writing Run Manifest

Status: updated

## Target Files

- `apps/dashboard/src/App.jsx`
- `apps/dashboard/src/styles.css`

## Writing Contract

- WRITING.md reviewed: yes.
- TERMS.md reviewed: yes.
- Locale: ko.
- Product concept: Bybit 선물 매매봇 운영 대시보드.
- Tone: 해요체, clear before clever, calm money/error copy.

## Copy Review

- Korean: all visible labels, buttons, empty states, loading and errors are Korean.
- English: only API identifiers or exchange values remain where unavoidable.
- Terms: avoid payload/schema/endpoint/authentication token in public UI; API is allowed by TERMS.md.
- Error messages: explain what happened and how to retry.
- Buttons and CTAs: action-first labels such as 새로고침, 동기화, 평가 후 주문, 일시정지, 재가동.
- Empty/loading/success states: each table/data surface has a Korean empty/loading/error message and separates 조회 전, 조회 실패, and 실제 내역 없음.
- Naming: user-facing names avoid internal module names.

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| endpoint 호출에 실패했습니다 | 데이터를 불러오지 못했어요. 잠시 후 다시 시도해 주세요. | Avoid internal jargon and include recovery. |
| Submit | 평가 후 주문 | Button names the outcome. |
| No data | 아직 표시할 내역이 없어요. | Empty state explains what is missing. |
| 최근 신호가 없어요. | 새로고침하면 전략 신호와 거절 사유가 여기에 표시돼요. | Empty state explains the future live data without fake rows. |

## Exceptions

- API and BTCUSDT remain as allowed technical/product terms.
