# 거래량 확인형 추세 v1 Node·Kotlin 패리티

## 판정

동결 프로토콜의 Node 연구기와 Kotlin 공통 코어가 Binance 외부 자료 전체에서 패리티를 통과했다. 상태는 `KOTLIN_CORE_PARITY_PASS_PAPER_RUNTIME_REQUIRED`다. 이 결과만으로 자동 주문이나 실거래를 허용하지 않는다.

## 비교 범위

| 항목 | 결과 |
|---|---:|
| H4 봉 | 14,424개 |
| Funding rate | 7,212개 |
| 방향 명령 | 165개 |
| 시작 자본·비용 조합 | 9개 |
| 비교 거래 | 1,485개 |
| 수치 허용오차 | `1e-8` |

다음 필드는 완전 일치를 요구했다.

- 프로토콜 SHA-256과 venue
- 판단·실행·진입·종료 시각
- Long/Short 방향
- 판단·실행 인덱스
- 종료 사유
- 봉·funding·명령·거래 개수

가격, 수량, 수수료, 슬리피지, funding, 거래별 손익, 종료 equity, MDD와 노출은 절대 `1e-8` 또는 값의 `1e-10` 상대오차 중 큰 값 이내인지 비교했다.

## 증거

| 항목 | SHA-256 |
|---|---|
| 프로토콜 | `6cb43d081a9f36e2a89aa723438dacf6da2906fe82e6aeb19efa067aba13fd74` |
| 외부 DB | `9c80bd330b8edb3a3a081b88ff4493ad7e1035da2d4d807e4ff625ee844bbbb6` |
| Node trace | `24d11f7f845f0bf747aff188fe1460c7c6de20f10b9995673369a4762eb546b7` |
| Kotlin trace | `2f4f0687dad4dcdaee62b4b1e20c0ab2ab700ba12e133516a411c77ed93b47bb` |
| 구현 Git SHA | `204dc044aa093e9f2dfb6e246df3f1f474cf1349` |

재현 명령:

```bash
node scripts/volume-confirmed-trend-node-parity.mjs \
  --protocol=config/volume-confirmed-trend-ensemble-v1.json \
  --db=build/research/binance-volume-confirmed-trend-external-v1.sqlite \
  --out=build/research/volume-confirmed-trend-node-parity.json

./gradlew :modules:bot-app:runVolumeConfirmedTrendParity \
  --args='--protocol config/volume-confirmed-trend-ensemble-v1.json --db build/research/binance-volume-confirmed-trend-external-v1.sqlite --out build/research/volume-confirmed-trend-kotlin-parity.json'

node scripts/verify-volume-confirmed-trend-parity.mjs \
  --node=build/research/volume-confirmed-trend-node-parity.json \
  --kotlin=build/research/volume-confirmed-trend-kotlin-parity.json
```

## 남은 차이

이번 패리티는 순수 계산 코어를 검증했다. 다음은 아직 검증되지 않았다.

- 운영 DB에서 확정 M15를 읽어 H4를 닫는 실시간 경로
- 프로세스 재시작 후 현재 목표 방향과 가상 포지션 복원
- 중복 H4 평가의 멱등성
- paper fill 및 원장 이벤트와 연구기 거래의 일치
- Bybit 실제 수수료·수량 규칙·교차 마진 사전 점검

따라서 다음 단계는 공통 코어를 사용하는 전용 shadow 상태 머신과 영속 원장을 구현하는 것이다.
