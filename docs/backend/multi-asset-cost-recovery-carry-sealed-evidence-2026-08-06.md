# 비용 회수형 carry v3 2026 상반기 봉인 데이터

## 취득 결과

2026-01-01부터 2026-06-30까지 BTCUSDT, ETHUSDT, SOLUSDT의 공식 Bybit V5 REST 데이터를
취득했다. 이 시점에는 후보의 portfolio 성과를 계산하지 않았다.

| 항목 | 결과 |
|---|---:|
| 자산 | 3 |
| 가격 시계열 | 12 |
| 시계열당 M5 행 | 52,128 |
| 자산당 funding settlement | 543 |
| 자산별 일치 M5 timestamp | 52,128 |
| 전체 자산 일치 M5 timestamp | 156,384 |
| 전체 funding 행 | 1,629 |
| decision input 누락 | 0 |

모든 가격 행은 정확한 5분 grid에 있고 가격 오류는 0건이다. 각 자산의 spot, perpetual last,
mark, index timeline과 세 자산의 portfolio timeline이 일치한다. 원본 REST page와 정규화 content는
데이터셋별로 SHA-256을 기록했다.

## 봉인 식별자

- protocol: `7ec481bf092749a2b414b338d94b206b47f013e867aa53bcc57291ab38074041`
- acquisition report: `78b8acca8418864cee8b6ba302be42c58743d34f90d4adb8c1d40ce5aadd4530`
- normalized evidence: `082d463705ea8154934d837c9019f6542d5f2b992cdfa47c0b8bc44b72c835f8`
- snapshot: `182fa998d8dcdb594d55667f29a63f6d2768535950cf41b98d1a16c7cff2b2b5`

동일 취득기를 재실행했을 때 acquisition report와 snapshot hash가 유지되는 것을 확인했다.
다음 단계에서 replay 코드와 이 receipt를 함께 고정한 후 후보 018을 한 번만 평가한다.

자동 실행과 실거래 실행은 모두 금지 상태다.
