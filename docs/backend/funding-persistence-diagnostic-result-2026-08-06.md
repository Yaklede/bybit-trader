# Funding 지속성 진단 결과

## 판정

carry v4 연구는 진행하지 않는다. 2023~2025의 장기 funding 지속성은 관측됐지만 2026 상반기에
같은 고지속성 regime이 한 번도 나타나지 않아 신규 regime 수익 표본을 만들지 못했다. 장기 filter는
`NO_TRADE` 위험 필터로만 남길 수 있고 수익성 증거로 사용할 수 없다.

## 결과

주 분석은 후행 90 settlement와 미래 90 settlement를 사용했다. 각 관측은 90 settlement 간격으로
분리했고, 2023~2025 후행 합계 상위 20% threshold를 2026 H1에 그대로 적용했다.

| 자산 | 비중첩 상관 | 개발 threshold | 개발 회수 | 2026 H1 적격 관측 | H1 회수 |
|---|---:|---:|---:|---:|---:|
| BTCUSDT | 0.43033313 | 0.941314% | 7 / 7 | 0 | 0 |
| ETHUSDT | 0.47709914 | 0.953970% | 7 / 7 | 0 | 0 |
| SOLUSDT | 0.38230523 | 1.145076% | 7 / 7 | 0 | 0 |

개발 구간의 적격 관측은 모두 미래 30일 funding만으로 왕복 비용 0.41%를 넘었다. 그러나 H1에는
후행 30일 funding이 해당 threshold에 도달한 비중첩 관측이 없었다. 이는 v3가 사용한 3회 연속
양수 funding보다 장기 누적 filter가 regime을 더 잘 구분한다는 뜻이지만, H1 수익 재현을 입증하지는
못한다.

## 결론

현재 자산과 taker 비용에서 delta-neutral funding carry는 고금리 regime에서만 간헐적으로 작동한다.
그 regime이 없을 때 거래하지 않는 것은 올바른 위험 제어지만, 사용자가 요구한 독립적인 수익 엔진은
아니다. 같은 funding 자료에서 threshold를 더 낮추거나 H1에 맞춰 다시 설정하지 않는다.

다음 연구는 기존 raw `orderbook.50`·`publicTrade` 수집을 이용한 보수적 maker shadow 체결로
전환한다. queue position을 알 수 없으므로 touch 체결을 금지하고, 관측된 taker volume이 초기 queue
ahead와 주문 수량을 모두 소진한 경우에만 가상 체결해야 한다. 해당 shadow 결과가 비용과 adverse
selection을 통과하기 전에는 실제 post-only 주문을 구현하거나 활성화하지 않는다.
