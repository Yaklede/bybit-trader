# Volume-confirmed trend v1 컨테이너 스모크

## 판정

동결 후보 `vcte_4h_majority_001`의 public-data Shadow 경로, 승인 API, 대시보드 프록시와 반응형 화면을 로컬 Docker 네트워크에서 검증했다. 주문을 만들 수 있는 모든 경로는 비활성화했다.

스모크 결과는 운영 포장과 관측 경로가 동작한다는 증거다. 수익성 전진 검증을 대체하지 않으며 자동 주문 또는 실거래를 승인하지 않는다.

## 검증 빌드

| 항목 | 값 |
|---|---|
| 소스 커밋 | `8eadc7b` |
| 백엔드 로컬 이미지 | `sha256:d984973de873b2b379576672b58ae7c46aeb8ddc3e975f85272d13ed7d22a671` |
| 대시보드 로컬 이미지 | `sha256:9b26f8924c1a5a18362a62bb544f345170b7f51fe6a5539a3fe954cabb3db9ce` |
| 프로토콜 SHA-256 | `6cb43d081a9f36e2a89aa723438dacf6da2906fe82e6aeb19efa067aba13fd74` |
| 승인 정책 SHA-256 | `5ea2185fe9f4299f656ca89848a5ffd77acb578954517cd22432e5b4d64dc62b` |

이미지는 백엔드 `test`, `lint`, `build`와 대시보드 production build를 포함해 생성했다. 두 컨테이너의 healthcheck는 모두 `healthy`였다.

## 주문 차단 설정

다음 실행 조건으로 검증했다.

```text
BOT_MODE=PAPER
BOT_PRIVATE_EXECUTION_ENABLED=false
BOT_PRIVATE_EXECUTION_STREAM_ENABLED=false
BOT_EXECUTION_LOOP_ENABLED=false
BOT_EXECUTION_RECONCILIATION_ENABLED=false
BOT_PAPER_LOOP_ENABLED=false
BOT_FORWARD_MARKET_CAPTURE_ENABLED=false
BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED=true
```

private exchange client는 구성되지 않았고 주문 제출은 0건이었다. Shadow는 public REST 자료만 읽고 가상 원장에 기록했다.

## 실제 관측 결과

2026-08-07 KST 최초 기동에서 다음 결과를 확인했다.

| 항목 | 결과 |
|---|---:|
| 초기 동기화 funding snapshot | 3개 |
| 평가한 최근 H4 | 4개 |
| 초기 가상 equity | 660 USDT |
| 가상 포지션 | 없음 |
| 최근 이벤트 | `SESSION_STARTED` 1건 |
| 승인 상태 | `SHADOW_COLLECTING` |
| 통과 게이트 | 10 / 14 |
| 자동 실행 허용 | `false` |
| 실거래 허용 | `false` |

미통과 네 항목은 fresh 관측 90일, 종료 거래 5회, 방향 전환 6회, 종료 거래 Profit Factor 1 이상이다. 데이터가 아직 없어서 `PENDING`인 항목이며 통과로 간주하지 않는다.

## API·화면 검증

- `GET /dashboard/mobile-summary`는 private execution이 없어도 완전한 성과 필드와 200 응답을 제공했다.
- `GET /strategy/volume-confirmed-trend/approval`은 고정된 14개 게이트와 `liveExecutionAllowed=false`를 반환했다.
- `GET /strategy/volume-confirmed-trend/shadow`는 영속 세션, equity, 노출, 최근 이벤트를 반환했다.
- 대시보드는 별도 optional 실거래 성과 API를 호출하지 않고 모바일 요약 계약을 사용했다.
- 통제한 브라우저 세션의 warning/error 로그는 0건이었다.
- 1280px 화면은 `scrollWidth=clientWidth=1280`, 390px 화면은 `scrollWidth=clientWidth=390`이었다.
- 모바일 헤더 높이는 56px였고 **실거래 승인 안 됨** 상태와 승인 표가 표시됐다.

## 남은 승인 조건

이 스모크가 완료돼도 실거래 단계로 넘어가지 않는다. 같은 프로토콜·정책·DB 연속성을 유지한 fresh Bybit Shadow를 최소 90일 수집하고, 모든 정량 게이트를 통과한 뒤 `READY_FOR_HUMAN_REVIEW` 상태에서 별도의 human approval을 받아야 한다.

세션 연속성이 깨지거나 프로토콜 fingerprint가 바뀌면 기존 관측 기간을 이어 붙이지 않는다. 실패한 기간도 삭제하거나 유리한 기간만 선택하지 않는다.
