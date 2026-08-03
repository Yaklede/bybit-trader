# 실행 비용·순손익비 게이트

## 목적

신호의 표면상 목표 R만 보고 주문을 제출하면 수수료와 슬리피지가 작은 손절
구간의 기대값을 잠식할 수 있다. 런타임과 공격형 백테스트가 같은 비용 계약을
사용하고, 비용 차감 후 최소 순손익비가 낮은 거래를 주문 전에 차단하도록 했다.

이 변경은 손실 전략을 수익 전략으로 바꾸는 튜닝이 아니다. 비용 때문에 구조적으로
불리한 주문을 제거하고, 백테스트의 위험 예산을 실거래 계산과 맞추는 실행 안전
변경이다.

## 계산 규칙

왕복 비용률:

```text
2 × feeRate + entrySlippageRate + exitSlippageRate
```

단위당 비용 조정 손실:

```text
costAdjustedRiskPerUnit
  = stopDistance
  + entryPrice × 왕복 비용률
```

순손익비:

```text
netReward = grossTargetMove - entryPrice × 왕복 비용률
netRisk   = stopMove + entryPrice × 왕복 비용률
netRR     = netReward / netRisk
```

기본 최소값은 `1.0`이며 `BOT_EXECUTION_MIN_NET_RR`로 조정할 수 있다. 목표가
왕복 비용 이하이면 `TARGET_DOES_NOT_COVER_ROUND_TRIP_FEES`, 비용 차감 후
`netRR`이 기준 미만이면 `NET_RISK_REWARD_BELOW_MINIMUM`으로 거절한다.

## 적용 범위

- `ExchangeExecutionService` 자동 진입
- `VolumeFlowAggressiveBacktestService`의 absorption 및 macro 진입
- 런타임과 백테스트의 공격형 실행 계약 fingerprint
- 수량 계산의 손실 예산에 수수료·진입 슬리피지·종료 슬리피지 포함

수량이 최소 주문 수량 아래로 내려가면 기존처럼 `INVALID_EXECUTION_SIZE` 또는
백테스트 skip으로 처리한다. 최소 수량을 위험 예산에 맞지 않게 올려 주문하지 않는다.

## 회귀 검증

- 슬리피지가 양쪽에 모두 적용되는지 계산기 테스트
- 비용 차감 후 양수지만 `netRR < 1.0`인 주문 거절 테스트
- 자동 실행 서비스가 동일한 거절 사유를 반환하는 통합 테스트
- 공격형 백테스트가 동일한 게이트를 적용하는 테스트
- 기존 수량 단계·최대 명목가·청산 거리 테스트

## 운영 주의

이 변경은 현재 `REJECTED` 전략 프로필을 승인하지 않는다. `BOT_EXECUTION_MIN_NET_RR`
값을 낮춰 주문 수를 늘리는 것은 외부 검증을 통과했다는 의미가 아니며, 전략 승인
게이트와 별도로 검토해야 한다.
