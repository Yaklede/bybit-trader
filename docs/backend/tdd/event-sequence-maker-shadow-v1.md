# 이벤트 순서 기반 메이커 섀도 v1 기술 설계

## 1. 범위

이 설계는 Bybit 공개 `orderbook.50`과 `publicTrade` 이벤트를 같은 수집 경로에서 순서대로 소비해,
실주문 없이 PostOnly 메이커 주문의 체결 가능성과 비용 차감 후 손익을 보수적으로 측정하는
`maker-shadow-v1`을 정의한다.

현재 캔들·1분 집계 전략은 비용 차감 후 음수 기대값으로 탈락했다. 메이커 수수료와 스프레드 회수가
경제성을 바꿀 가능성은 이벤트 순서를 보존한 체결 모델 없이는 검증할 수 없다. 이 기능은 수익 전략을
승인하거나 private 주문을 제출하지 않는다.

## 2. 목표와 비목표

### 목표

- 최우선 bid/ask 가격과 표시 수량, book epoch, update ID, cross sequence를 정규화 이벤트에 보존한다.
- public trade의 trade ID와 cross sequence를 보존하고 중복 체결을 제거한다.
- 단순 touch가 아닌 관측된 반대편 taker 거래량으로만 선행 큐를 소진한다.
- 부분 체결, 재호가, 데이터 gap, snapshot reset, stale event를 명시적인 상태 전이로 기록한다.
- maker fee, 강제 taker 종료 비용, mark-out을 포함한 append-only 섀도 원장을 만든다.
- 동일 이벤트 재생이 동일한 주문·체결·손익을 생성하는 결정적 테스트를 제공한다.

### 비목표

- 공개 L2로 개별 주문 ID, 정확한 queue position, 취소량, RPI/hidden liquidity를 추정하지 않는다.
- 호가 수량 감소만으로 체결을 만들지 않는다.
- 실계정 주문, PostOnly 주문 제출, 레버리지 또는 포지션 변경을 구현하지 않는다.
- 이 단계 결과만으로 전략을 `VERIFIED` 또는 실거래 가능 상태로 바꾸지 않는다.
- 아직 열람하지 않은 외부·봉인 구간을 후보 고정 전에 사용하지 않는다.

## 3. 근거

- Bybit [Orderbook](https://bybit-exchange.github.io/docs/v5/websocket/public/orderbook)은 snapshot/delta,
  `u`, `seq`, `cts`를 제공하지만 공개 호가에는 RPI 주문이 포함되지 않는다.
- Bybit [Public Trade](https://bybit-exchange.github.io/docs/v5/websocket/public/trade)는 taker side,
  trade ID, 가격, 수량, `seq`를 제공하며 여러 메시지가 같은 sequence를 가질 수 있다.
- Bybit [Create Order](https://bybit-exchange.github.io/docs/v5/order/create-order)의 PostOnly 주문은 즉시
  체결될 상황이면 취소된다. 따라서 섀도 주문은 관측 시점의 반대 호가를 넘을 수 없다.
- 실제 계정 maker/taker 수수료는 [Get Fee Rate](https://bybit-exchange.github.io/docs/v5/account/fee-rate)
  응답을 승인 증거에 고정해야 한다. 초기 연구 기본값은 명시적 설정이며 계정 수수료를 대신하지 않는다.

## 4. 아키텍처

```text
Bybit WebSocket frame
  -> BybitPublicMarketCaptureParser
     -> ForwardMarketRawEvent (원문 증거)
     -> rich normalized event (best level, trade id, sequence)
  -> ForwardMarketCaptureService
     -> raw archive
     -> MakerShadowObserver
        -> MakerShadowEngine
           -> quote/fill/position/mark-out events
           -> MakerShadowLedger
     -> 기존 1분 집계
```

기존 수집·집계 경로는 유지한다. `ForwardMarketCaptureService`는 선택적 observer를 먼저 호출한 뒤 기존
minute accumulator를 갱신한다. observer 실패는 해당 배치를 성공으로 처리하지 않으며 수집 루프가
재연결되어 원문과 섀도 원장의 순서가 갈라지는 것을 막는다.

## 5. 도메인 모델

### 시장 이벤트

`OrderBookDepthSnapshot`에 다음 필드를 추가한다.

```text
bestBidPrice / bestBidQuantity
bestAskPrice / bestAskQuantity
updateId / crossSequence / bookEpoch
matchingEngineTimestamp / receivedAt
quality
```

`TakerTradeEvent`에 다음 필드를 추가한다.

```text
tradeId / crossSequence
matchingEngineTimestamp / receivedAt
```

기존 테스트와 집계 호출자를 깨지 않도록 새 필드는 nullable 또는 안전한 기본값을 사용한다. 섀도 엔진은
필수 필드가 하나라도 없으면 이벤트를 집계에는 허용하되 체결 판단에는 사용하지 않는다.

### 섀도 상태

```text
DISABLED
WAITING_FOR_BOOK
QUOTING
PARTIALLY_FILLED
INVENTORY_OPEN
HALTED_DATA_QUALITY
```

주문 상태는 `ACTIVE`, `PARTIALLY_FILLED`, `FILLED`, `CANCELLED`, `INVALIDATED`로 분리한다. 포지션은
최대 한 주문 수량의 long 또는 short만 허용하며, inventory가 있으면 반대편 청산 quote만 유지한다.

## 6. 보수적 큐·체결 계약

### 주문 생성

1. `SNAPSHOT_RESET` 또는 `VALID` book만 사용한다.
2. bid/ask가 양수이고 교차하지 않으며 수신 지연과 book age가 한도 이내여야 한다.
3. spread가 사전 고정된 최소값 이상일 때만 현재 best price에 PostOnly 섀도 quote를 만든다.
4. 초기 `queueAhead = displayedBestQuantity * queueMultiplier + queueBufferQuantity`로 둔다.
5. quote 생성 시점의 `bookEpoch`, `crossSequence`, 가격을 고정한다.

`queueMultiplier`는 최소 1이다. 공개 book에 보이지 않는 유동성을 낙관적으로 0으로 두지 않도록 기본값은
1보다 크게 둔다. 파라미터는 개발 결과를 보기 전에 연구 manifest에 고정한다.

### 큐 소진과 체결

- BUY quote는 같은 가격의 `takerSide=SELL`만 소비한다.
- SELL quote는 같은 가격의 `takerSide=BUY`만 소비한다.
- quote 생성 sequence 이후의 trade만 인정한다.
- 처음 관측한 trade ID만 인정한다. ID가 없으면 체결 근거로 쓰지 않는다.
- 관측 거래량은 먼저 `queueAhead`를 차감한다. 남은 수량만 섀도 주문을 부분 체결한다.
- book 수량 감소, 가격 touch, 캔들 고가·저가, quote보다 불리한 가격의 trade는 체결량으로 추정하지 않는다.

이 모델은 실제 체결을 과소 추정할 수 있지만, 공개 L2만으로 얻을 수 없는 queue priority를 낙관적으로
가정해 허위 수익을 만드는 것보다 적합하다.

### 주문 무효화

다음 사건은 활성 quote를 즉시 `INVALIDATED` 또는 `CANCELLED`로 전환한다.

- book epoch 변경 또는 `SNAPSHOT_RESET`
- gap, empty book, crossed book
- quote가 더 이상 best price가 아님
- 최대 quote age 초과
- event receive delay 또는 book staleness 한도 초과
- 최대 inventory, 손실 한도 또는 엔진 중단

재호가 시 이전 quote의 queue progress는 승계하지 않는다.

## 7. 손익과 원장

원장은 append-only 이벤트로 다음을 기록한다.

```text
SHADOW_STARTED / BOOK_ACCEPTED / BOOK_REJECTED
QUOTE_OPENED / QUOTE_CANCELLED / QUOTE_INVALIDATED
QUEUE_DEPLETED / PARTIAL_FILL / FILL
POSITION_OPENED / POSITION_CLOSED / FORCED_TAKER_EXIT
MARK_OUT_1S / MARK_OUT_5S / MARK_OUT_30S
SHADOW_HALTED
```

각 이벤트에는 `engineVersion`, `configFingerprint`, symbol, event time, receive time, book epoch, sequence,
quote/fill ID와 금액 필드를 넣는다. trade ID와 ledger event ID는 중복 방지 키다.

손익은 다음처럼 계산한다.

```text
makerFillFee = abs(price * qty) * makerFeeRate
takerExitFee = abs(price * qty) * takerFeeRate
realizedPnl  = side-adjusted price difference - all fees - modeled slippage
equity       = initialEquity + realizedPnl + markToMarketUnrealizedPnl
```

수수료율이 음수인 rebate 계정도 표현할 수 있지만 실제 fee-rate 증거 없이는 승인 입력으로 사용할 수 없다.
미청산 inventory는 최대 보유 시간에 반대편 best price와 보수적 taker slippage로 종료한다.

## 8. 정합성·동시성·복구

- 한 symbol의 배치는 bounded FIFO consumer 한 곳에서 직렬 처리한다.
- 원문 archive append가 성공한 뒤 섀도 이벤트를 계산하고, 그 뒤 1분 집계를 갱신한다.
- ledger append 실패 시 배치 처리를 실패시켜 다음 이벤트로 진행하지 않는다.
- 마지막 처리 raw event fingerprint, book epoch, sequence, 활성 quote, inventory를 checkpoint한다.
- 재시작 시 checkpoint 뒤 원문 이벤트만 재생하고 trade ID 및 ledger event ID로 중복을 제거한다.
- checkpoint와 append-only ledger가 불일치하면 `HALTED_DATA_QUALITY`로 시작한다.

초기 구현은 인터페이스와 결정적 엔진을 먼저 제공한다. 파일 영속 원장과 재시작 checkpoint는 별도 커밋에서
추가하며, 영속화 전에는 애플리케이션 설정으로 섀도 엔진을 활성화하지 않는다.

## 9. 실패 처리

| 실패 | 처리 |
|---|---|
| 필수 정규화 필드 누락 | 집계만 수행, quote/fill 판단 제외 |
| sequence 역행 또는 epoch 불일치 | 모든 quote 무효화, 새 snapshot까지 중단 |
| 중복 trade ID | 무시하고 중복 카운터 증가 |
| ledger 쓰기 실패 | 수집 배치 실패, 재연결, 경고 |
| stale market event | quote 취소, 신규 quote 차단 |
| max holding 초과 | 보수적 taker 종료 또는 가격 증거 없으면 미결 상태로 중단 |
| 설정 fingerprint 변경 | 기존 session 종료 후 새 session으로만 시작 |

## 10. 검증 계획

### 단위·회귀 테스트

- parser가 best price/quantity, epoch, update ID, sequence, trade ID를 보존한다.
- touch와 book 감소만으로 체결되지 않는다.
- 올바른 taker side와 정확한 가격만 queue를 소진한다.
- queue 소진 후 잔여량만 부분 체결되고 주문 수량을 넘지 않는다.
- 중복 trade ID가 손익을 두 번 바꾸지 않는다.
- epoch reset, gap, stale event, best-price 변경이 quote를 무효화한다.
- 동일 입력 재생 결과의 ledger fingerprint가 같다.
- maker fee, taker 종료, mark-to-market, 1/5/30초 mark-out 계산이 일치한다.

### 연구 게이트

1. 개발 raw-event 구간에서 데이터 품질과 최소 체결 표본을 확인한다.
2. 파라미터 후보 수와 실험 manifest를 결과 조회 전에 고정한다.
3. 개발 통과 후보만 아직 읽지 않은 외부 구간으로 이동한다.
4. 비용 1.0/1.5/2.0배, queue 1.0/1.5/2.0배, receive-delay 스트레스를 모두 통과한다.
5. 새로운 forward shadow와 paper의 주문·체결 차이가 허용 범위 안일 때만 런타임 후보를 만든다.

### 승인 경계

역사 재생 또는 섀도 결과가 양수여도 자동 주문은 계속 `false`다. `research-evidence-contract-v1`의 외부,
봉인, 통계, 비용, 위험, Kotlin/연구 실행 패리티와 별도의 forward shadow/paper 승인을 모두 통과한 동일
fingerprint만 후속 실거래 승인 검토 대상이 된다.

## 11. 수용 기준

- 수집 파서와 기존 1분 집계 회귀 테스트가 모두 통과한다.
- 공개 데이터에서 증명할 수 없는 queue priority를 코드가 만들지 않는다.
- 데이터 품질 이상 뒤 snapshot 전에는 체결이 생성되지 않는다.
- 원장으로 모든 quote, fill, fee, 종료와 mark-out을 재구성할 수 있다.
- 활성화 설정과 private execution 경로가 물리적으로 분리된다.
- 실거래 승인 상태는 변경되지 않는다.

## 12. 위험

- 보수적 모델은 실제 메이커 체결률을 낮게 추정할 수 있다.
- 공개 L2에 없는 RPI/hidden liquidity 때문에 queue와 adverse selection을 완전히 재현할 수 없다.
- 이벤트 archive가 충분하지 않으면 역사 검증이 아니라 forward shadow 기간이 필요하다.
- 낮은 BTCUSDT spread와 양의 maker fee가 결합되면 전략은 체결 모델과 무관하게 비용 후 음수일 수 있다.
- 수익이 소수 fill이나 특정 시간대에 집중되면 외부·봉인 게이트에서 탈락시켜야 한다.
