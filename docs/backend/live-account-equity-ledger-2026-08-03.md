# 실계좌 equity 원장과 MDD

## 변경 목적

실현 손익을 0에서 누적해 MDD를 계산하면 첫 거래가 손실인 경우 낙폭이 0으로 표시될 수 있다. 운영 성과는 Bybit unified account의 `totalEquity` 스냅샷을 별도 원장에 저장하고, 관측된 계좌 최고점 대비 실제 equity 하락을 계산해야 한다.

## 저장 흐름

```text
reconciliation
  -> /v5/account/wallet-balance (USDT)
  -> executionAccountSnapshots
  -> /v5/account/transaction-log (24h bootstrap, 5m overlap)
  -> executionAccountTransactions (append-only, identity deduplicated)
  -> window별 baseline + snapshot 재생
  -> maxAccountDrawdownPct
```

잔고 조회에 실패해도 종료 손익·라이프사이클 원장 처리는 중단하지 않는다. 실패는 로그에 남기며 다음 reconciliation에서 재시도한다. 따라서 `accountEquity`가 비어 있으면 성과 화면은 MDD를 0으로 위조하지 않고 `null`로 표현할 수 있다.

계좌 스냅샷은 account-wide equity뿐 아니라 추적 통화인 USDT의 equity,
wallet balance, unrealized PnL, cumulative realized PnL과 account initial/
maintenance margin을 함께 저장한다. 거래 내역은 Bybit의 `id`만 신뢰하지
않고 type, trade/order id, 시각, change를 결합한 identity로 중복 제거한다.
거래소가 같은 조회 구간을 반복 반환하거나 reconciliation이 재시작돼도
동일 이벤트는 한 번만 원장에 남는다.

Bybit 거래 원장은 한 요청에서 최대 7일 구간과 50건 페이지를 사용하므로
클라이언트가 cursor를 끝까지 순회한다. 공식 계약상 `change = cashFlow +
funding - fee`이며, 이 값과 USDT wallet balance 사이의 대사는 다음 단계의
fail-closed 진입 게이트에서 사용한다. 거래 내역 적재만으로 입출금을 전략
수익에서 자동 분리했다고 간주하지 않는다.

Source: [Bybit Get Transaction Log](https://bybit-exchange.github.io/docs/v5/account/transaction-log),
[Bybit Get Wallet Balance](https://bybit-exchange.github.io/docs/v5/account/wallet-balance).

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
- 거래 내역 pagination과 overlap 재조회는 동일 event를 중복 저장하지 않는다.
- 기존 SQLite는 account transaction 테이블과 USDT 추적 잔고 컬럼을 additive migration으로 보완한다.
