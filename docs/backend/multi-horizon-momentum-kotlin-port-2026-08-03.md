# 멀티 호라이즌 모멘텀 Kotlin 기준선

> 2026-08-06 갱신: 이 문서의 `v1` 포트는 외부 검증에서 `REJECTED`되었다. 현재 교차 엔진 기준선은 `multi-horizon-momentum-development-v2`와 `causal-next-contiguous-open-v3` 계약이며, 상세 근거는 `multi-horizon-momentum-execution-parity-v3-2026-08-06.md`에 있다.

## 상태

`multi-horizon-momentum-development-v1`을 Kotlin `TradingStrategy`와 공통 `BacktestRunner`로 옮겼다. 이후 외부 검증 실패로 `REJECTED`되었으며 자동 실행 프로필이나 실거래 루프에 연결하지 않는다.

## 신호 계약

- 입력: 단일 종목의 닫힌 M5 캔들
- 기간: 288, 2,016, 8,640개 캔들
- 기준 수익률: 1%, 3%, 8%에 `thresholdScale=0.75` 적용
- 세 기간 중 3개가 같은 방향이면 합의 후보
- EMA 288이 EMA 1,152보다 위·아래이고 EMA 288의 288캔들 기울기가 같은 방향이어야 함
- 직전 닫힌 캔들의 방향과 달라지는 순간만 신호 발생
- 기준선 side mode: `LONG_ONLY`
- 손절 기준: 구조적 고저점과 ATR 20 × 8 중 더 보수적인 값
- 기대 R: 12, trailing ATR: 16, 최대 보유: 4,032개 M5 캔들

## 실행 정합성

`BacktestRunner`는 공통 `CausalReplay`를 통해 다음을 보장한다.

- 신호 판단 시점 이후 캔들을 전략에 전달하지 않음
- 다음 캔들이 정확히 이어지지 않으면 진입하지 않음
- 진입·청산 슬리피지는 방향에 불리하게 적용
- 같은 캔들에서 손절과 목표가 모두 닿으면 손절 우선

## 한계

기존 Node 연구 결과는 개발 구간에서만 산출된 기준선이었다. `v1`에서 남아 있던 EMA 시드, trailing 갱신 순서, 일일 거래 제한, 종료 슬리피지 차이는 `v2`에서 수정했다. 포트 결과가 좋더라도 봉인 외부 구간과 비용 스트레스 게이트를 통과하기 전에는 승격하지 않는다.
