# 실계좌 equity 원장과 MDD

## 변경 목적

실현 손익을 0에서 누적해 MDD를 계산하면 첫 거래가 손실인 경우 낙폭이 0으로 표시될 수 있다. 운영 성과는 Bybit unified account의 `totalEquity` 스냅샷을 별도 원장에 저장하고, 관측된 계좌 최고점 대비 실제 equity 하락을 계산해야 한다.

## 저장 흐름

```text
reconciliation
  -> /v5/account/wallet-balance (USDT)
  -> executionAccountSnapshots
  -> window별 baseline + snapshot 재생
  -> maxAccountDrawdownPct
```

잔고 조회에 실패해도 종료 손익·라이프사이클 원장 처리는 중단하지 않는다. 실패는 로그에 남기며 다음 reconciliation에서 재시도한다. 따라서 `accountEquity`가 비어 있으면 성과 화면은 MDD를 0으로 위조하지 않고 `null`로 표현할 수 있다.

## 성과 계약

`/performance/live/summary`는 기존 실현손익 지표를 유지하면서 다음 계좌 지표를 추가한다.

- `accountEquity`: 해당 창에서 마지막으로 관측한 total equity
- `accountPeakEquity`: 창의 시작 baseline을 포함한 최고 equity
- `maxAccountDrawdownPct`: 최고 equity 대비 최대 하락률
- `accountEquityCapturedAt`: 마지막 equity 관측 시각

`maxClosedTradeDrawdownPct`는 기존 실현손익 곡선 지표로 유지한다. 두 지표를 같은 MDD로 표시하지 않는다.

창이 `SESSION`, `7d`, `30d`인 경우 시작 시각 이전에 마지막으로 관측된 equity를 baseline으로 포함한다. `ALL`은 저장된 전체 스냅샷을 사용한다.

## 회귀 조건

- `100 -> 120 -> 90` equity 스냅샷은 account MDD `25%`를 산출한다.
- 실현손익 이력이 없어도 account MDD는 계산된다.
- 계좌 조회 실패가 closure persistence를 실패시키지 않는다.
- 기존 SQLite는 누락된 performance 컬럼과 account snapshot 테이블을 additive migration으로 보완한다.
