# Volume-confirmed trend v1 런타임 재생 패리티

## 판정

고정 후보 `vcte_4h_majority_001`의 외부 Binance USD-M 이력을 Kotlin 기준 시뮬레이터와 영속 Shadow 런타임에 각각 재생한 결과, 실행 패리티가 통과했다.

이 결과는 전략 수익성을 추가로 증명하는 자료가 아니다. 동일한 신호와 시장 입력이 백테스트 어댑터와 Shadow 어댑터에서 같은 주문 방향, 수량, 체결가, 비용, 펀딩 손익과 현금 잔고를 만든다는 실행 정합성 증거다.

## 고정 입력

- 구현 커밋: `2d43de628cee554e113a8f301cee897ee3734276`
- 프로토콜 SHA-256: `6cb43d081a9f36e2a89aa723438dacf6da2906fe82e6aeb19efa067aba13fd74`
- 외부 결과 SHA-256: `1a4a49029e7a24020e21fb90f23490dddeb7c27a98f02a20438fd28bf9cc2cd1`
- 외부 DB SHA-256: `9c80bd330b8edb3a3a081b88ff4493ad7e1035da2d4d807e4ff625ee844bbbb6`
- 재생 결과 SHA-256: `df04122c20b8816049ec390eb1d1e27c2884f0e62642ef7ead80e1b9c461305c`

재생 결과는 동일 커밋에서 두 번 생성했고 두 파일의 SHA-256이 일치했다.

## 결과

| 항목 | 결과 |
|---|---:|
| 전체 H4 | 14,424 |
| 펀딩 레코드 | 7,212 |
| bootstrap H4 | 540 |
| Shadow 평가 H4 | 13,883 |
| 예상 방향 전환 | 164 |
| 실제 방향 전환 | 164 |
| 비교 종료 거래 | 163 |
| 불일치 | 0 |
| 최대 수치 오차 | 0.0 |
| 허용 오차 | 1e-8 |

비교 필드는 방향, 진입·종료 시각, 수량, 진입·종료 체결가, 총손익, 펀딩 손익, 수수료, 순손익, 종료 사유, 누적 수수료, 누적 슬리피지, 누적 펀딩, 현금 잔고와 종료 시점 보유 포지션이다.

## 전진 검증 기준

`config/volume-confirmed-trend-ensemble-v1-forward-policy.json`은 신선한 Bybit Shadow 결과를 보기 전에 고정했다.

- 연속 90일 이상
- 종료 거래 5회 이상, 방향 전환 6회 이상
- 세션 수익률 0% 초과
- 종료 거래 손익계수 1 이상
- MDD 35% 이하
- 진입 노출 85% 이하, 불리한 노출 120% 이하
- 청산 0회
- 마지막 관측이 300분보다 오래되지 않음

통과 상태는 `READY_FOR_HUMAN_REVIEW`다. 자동 실거래 승격과 live 실행은 계속 금지한다.

## 재현 명령

```bash
./gradlew :modules:bot-app:runVolumeConfirmedTrendRuntimeParity --args="--protocol config/volume-confirmed-trend-ensemble-v1.json --external-result config/volume-confirmed-trend-ensemble-v1-external-result.json --db build/research/binance-volume-confirmed-trend-external-v1.sqlite --out build/research/volume-confirmed-trend-runtime-parity.json"
```
