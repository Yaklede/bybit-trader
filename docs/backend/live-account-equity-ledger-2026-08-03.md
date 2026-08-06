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
  -> executionWalletReconciliationStates
  -> entry-only fail-closed gate
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
클라이언트가 cursor를 끝까지 순회한다. 최초에는 최근 24시간을 적재하고,
이후에는 마지막 거래에서 5분을 겹쳐 조회한다. 해결되지 않은 대사 기준점이
있을 때만 그 기준점까지 조회 범위를 넓힌다. 공식 계약상 `change = cashFlow +
funding - fee`이다.

첫 스냅샷은 `BASELINE`으로 저장되며 신규 진입을 허용하지 않는다. 다음
스냅샷부터 `observedWalletChange`와 기준점 이후 transaction `change` 합계를
비교한다. 허용 오차 안이면 `MATCHED`로 기준점을 전진시키고, 벗어나면
`MISMATCH`로 기존 기준점을 유지한다. 거래내역 동기화 실패, USDT wallet
데이터 부재, 오래된 대사 상태, 확인된 불일치는 모두 신규 진입을 차단한다.
기존 포지션 관리와 reduce-only 종료는 이 게이트보다 먼저 실행된다.

이 대사는 원장 누락을 탐지하는 장치다. 거래 내역 적재만으로 입출금을 전략
수익에서 자동 분리했다고 간주하지 않는다. 대사가 활성화된 운영 구성에서는
별도의 단위화 NAV가 입출금 영향을 제거한다.

## 현금흐름 조정 NAV

위험 회로차단기는 raw `totalEquity`를 감사용으로 계속 저장하되, 일손실과
계좌 낙폭은 unitized NAV로 평가한다. 최초 스냅샷에서 NAV `1`, units를 현재
equity로 기준화한다. 이후 각 스냅샷 구간의 외부 현금흐름 `F`에 대해 다음
순서로 갱신한다.

```text
preFlowEquity = currentEquity - F
periodFactor  = preFlowEquity / previousEquity
currentNAV    = previousNAV * periodFactor
currentUnits  = previousUnits + F / currentNAV
```

Bybit 거래 유형 중 `TRADE`, `SETTLEMENT`, `DELIVERY`, `LIQUIDATION`, `ADL`,
`FEE_REFUND`, `INTEREST`는 전략 성과로 포함한다. 그 밖의 USDT 잔고 변화는
봇 외부의 자본 흐름으로 단위화한다. 거래 원장 row ID를 체크포인트로
저장하므로 재조회·재시작 시 같은 현금흐름을 두 번 적용하지 않고, 늦게
도착한 과거 시각의 거래도 더 큰 row ID로 다음 주기에 처리한다.

스냅샷 간 현금흐름은 해당 짧은 구간의 끝에서 발생한 것으로 계산한다.
reconciliation 기본 간격은 60초이므로 장기 성과 왜곡은 제한되지만, 이
근사는 체결 단위 투자펀드 회계와 동일하지 않다. 봇 전용 Unified 계좌를
사용하는 것이 전제다.

Source: [Bybit Get Transaction Log](https://bybit-exchange.github.io/docs/v5/account/transaction-log),
[Bybit Get Wallet Balance](https://bybit-exchange.github.io/docs/v5/account/wallet-balance),
[Bybit Enums](https://bybit-exchange.github.io/docs/v5/enum).

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
- 첫 wallet snapshot은 `BASELINE`, 변화량과 원장이 일치하는 다음 snapshot은 `MATCHED`가 된다.
- 불일치, transaction sync 실패, stale reconciliation 상태에서는 신규 진입만 fail closed 된다.
- 기존 SQLite는 wallet reconciliation 상태 테이블을 additive migration으로 보완한다.
- 입금 후 raw equity가 증가해도 unitized NAV와 NAV MDD는 변하지 않는다.
- 출금 후 raw equity가 감소해도 unitized NAV와 NAV MDD는 변하지 않는다.
- 거래 손실은 입출금 뒤에도 unitized NAV 손실로 반영된다.
- 거래 원장 ID checkpoint는 재처리된 외부 현금흐름의 중복 적용을 막는다.
- 기존 risk state는 `UNAVAILABLE` NAV로 이관되고 새 기준점이 준비될 때까지 신규 진입을 차단한다.
