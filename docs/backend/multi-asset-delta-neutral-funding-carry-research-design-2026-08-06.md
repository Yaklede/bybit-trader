# 다자산 델타 중립 funding carry 연구 설계

## 문제와 판정 경계

BTC 단일 pair v1은 2023과 2024 모두 비용 후 양수였지만, 2024에서 승리 포지션
한 건의 양수 이익 기여도가 `38.51%`로 사전 상한 `35%`를 넘었다. 단일 BTC의
진입 조건을 결과에 맞춰 다시 조정하지 않는다. 새 가설은 같은 총노출 한도에서
BTC, ETH, SOL의 서로 다른 funding 국면을 배분해 이익 원천을 분산하는 것이다.

이 문서는 다자산 bulk 2023 자료를 받기 전에 작성했다. 세 종목별 M5 자료 최대
3개와 funding 최대 4개는 endpoint, schema, 최소 주문 단위 확인용으로만 읽었다.
BTC 단일 pair의 2023·2024 결과는 이미 관측됐음을 숨기지 않는다. 따라서 이번
2023 결과는 새 family의 개발 자료일 뿐 독립 검증이 아니다.

## 목표

- 총 위험 노출을 늘리지 않고 세 종목으로 carry 원천을 분산한다.
- 660 USDT 계좌에서 실제 종목별 최소 수량과 1배 선물 증거금을 반영한다.
- 후보 24개, 누적 시험 335개, 평가 순서와 탈락 조건을 결과 전에 고정한다.
- 포트폴리오 MDD, 종목별 hedge 오차, 청산, 비용, leg 지연을 같은 재생에서 잰다.
- 2023 개발, 2024 내부, 2025 외부, 2026 봉인, 신규 shadow·paper를 순서대로
  통과한 fingerprint만 실행 후보로 다룬다.

## 비목표

- 목표 일복리 수치에 맞도록 gate나 후보를 사후 변경하지 않는다.
- BTC 단일 pair의 탈락 기준을 완화하지 않는다.
- 음수 funding을 받기 위한 현물 공매도나 차입 가능성을 가정하지 않는다.
- 두 leg 또는 여러 종목의 주문이 원자적으로 체결된다고 가정하지 않는다.
- 역사 검증 통과만으로 실거래 수익을 보장하지 않는다.

## 자료와 후보

고정 universe는 `BTCUSDT`, `ETHUSDT`, `SOLUSDT`다. 각 종목에서 spot last,
linear last, mark, index M5와 정산된 funding을 사용한다. 판단 시점에 닫힌 자료만
사용하며 진입과 종료는 최소 한 봉 뒤의 첫 연속 M5 시가다.

후보 축은 다음 네 개다.

- 연속 양수 funding: 3회 또는 6회
- 연속 구간 funding 중앙값: 0.01%, 0.02%, 0.03% 이상
- 최대 동시 pair: 1개 또는 2개
- 종료 확인: 연속 비양수 funding 1회 또는 2회

총 24개이며 결과 확인 후 후보를 추가하지 않는다. 각 정산 시점에 적격 종목을
다음 고정 점수로 정렬한다.

```text
score = funding 중앙값 * 90
      + max(진입 basis, 0)
      - 0.0041
```

점수가 0 이하이면 진입하지 않는다. 높은 점수 우선, 동점은 symbol 오름차순이다.
이는 월간 예상 funding과 basis 수렴이 현물·선물 네 leg 기준 비용을 넘는지를
보수적으로 확인하는 규칙이다.

## 자본과 체결 계약

- 시작 equity: 660 USDT
- 총 matched notional: 현재 equity의 최대 40%
- 동시 1개 후보: pair당 최대 40%
- 동시 2개 후보: pair당 최대 20%, 합계 최대 40%
- 포지션: 종목별 현물 Long + 동일 순 base 수량 선물 Short
- 선물 레버리지: 1배, 미사용 equity 최소 20%
- 주문 순서: 선물 Short 후 현물 Long
- 두 번째 leg 실패: 첫 leg 즉시 reduce-only 종료 후 신규 진입 중단
- 현물 taker fee 0.10%, 선물 taker fee 0.055%
- 현물/선물 leg당 슬리피지 0.03%/0.02%
- 네 leg 기준 비용: matched notional의 0.41%
- 비용 스트레스: 1.5배, 실행 스트레스: 두 번째 leg 한 M5 지연

수량은 선물 step으로 내림한다. 현물 매수 수수료 차감 후 순 base 수량이 선물
수량과 맞도록 현물 precision으로 gross 수량을 조정한다. 최소 수량·최소 명목가·
20% 준비금을 충족하지 못하면 해당 종목은 `NO_TRADE`다.

## 위험과 승인 기준

M5마다 세 종목의 open position을 합산해 실제 포트폴리오 equity와 MDD를
계산한다. 청산, basis 손절, 예정 종료가 같은 봉에서 충돌하면 그 순서대로
불리하게 처리한다. funding은 실제 정산률과 해당 시점 mark로만 반영한다.

개발과 내부 검증은 다음을 모두 통과해야 한다.

- 종료 포지션 20개 이상, 활성 180일 이상, funding 수취 60회 이상
- 세 종목 모두 거래, 양수 기여 종목 2개 이상
- 양수 분기 3/4 이상
- 비용 후 순수익과 평균 일수익 양수, profit factor 1.10 이상
- 7일 moving-block bootstrap 95% 평균 일수익 하한 양수
- MDD 15% 이하, 청산 0회
- 한 승리 포지션의 양수 이익 기여도 25% 이하
- 한 종목의 양수 이익 기여도 60% 이하
- 종목별 최대 hedge 오차가 각 spot precision 이하
- 비용 1.5배와 두 번째 leg 한 봉 지연에서도 순수익 양수

개발 통과 후보가 여러 개여도 사전 순위 규칙으로 하나만 동결한다. 2024에서 한
항목이라도 실패하면 2025와 2026 자료를 열지 않고 v1을 종료한다.

## 운영 승인 조건

역사 검증은 수익 가능성에 대한 제한된 증거일 뿐이다. 2026 봉인 이후에도 새로
도착한 자료의 shadow 신호, 비용을 포함한 paper 체결, 백테스트와 실행 core의
동일 이벤트 재생, 계좌 원장 대사, 미보호 포지션 0건을 확인해야 한다. 실제 계정
fee와 최신 instrument rule을 private/public API로 다시 읽은 fingerprint만 최소
수량 live 승인 대상으로 삼는다.

## 공식 근거

- Bybit Kline: https://bybit-exchange.github.io/docs/v5/market/kline
- Bybit Mark Price Kline: https://bybit-exchange.github.io/docs/v5/market/mark-kline
- Bybit Index Price Kline: https://bybit-exchange.github.io/docs/v5/market/index-kline
- Bybit Funding History: https://bybit-exchange.github.io/docs/v5/market/history-fund-rate
- Bybit Instrument Info: https://bybit-exchange.github.io/docs/v5/market/instrument
- Bybit Account Fee Rate: https://bybit-exchange.github.io/docs/v5/account/fee-rate
