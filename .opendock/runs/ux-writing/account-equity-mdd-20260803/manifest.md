# UX Writing Run Manifest

Status: complete

## Target Files

- `apps/dashboard/src/App.jsx`

## Writing Contract

- WRITING.md reviewed: yes
- TERMS.md reviewed: yes
- Locale: ko
- Product concept: 선물 매매봇 운영 대시보드
- Tone: professional, easy, 해요체

## Copy Review

- Korean: distinguish account MDD from realized-PnL drawdown with short labels
- English: not changed
- Terms: use 공개 용어 `계좌`, `실현손익`, `낙폭`; avoid database/API terms
- Error messages: unchanged
- Buttons and CTAs: unchanged
- Empty/loading/success states: existing `조회 전` behavior remains
- Naming: labels describe the source metric, not an ambiguous generic MDD

## Rewrites

| Before | After | Reason |
| --- | --- | --- |
| 최대 종료 손실폭 | 계좌 MDD | Show the actual account-equity drawdown field. |
|  | 실현손익 곡선 낙폭 | Keep the realized-PnL-only metric explicit. |

## Exceptions

- No English copy was changed because this dashboard currently renders the Korean operations surface.
