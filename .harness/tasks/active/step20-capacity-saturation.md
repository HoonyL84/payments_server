# Step 20. Capacity & Saturation Measurement

## 목표

고정된 로컬 Docker 환경에서 정상, PG 지연, 가맹점 편중 부하를 반복 측정해 안전 처리 구간과 최초 포화 지점을 수치로 구분한다.

## 완료 기준

- [x] Git SHA, image, Docker CPU와 memory, pool과 timeout 조건 기록
- [x] 정상, PG 150ms 지연, merchant skew 시나리오 분리
- [x] 10, 50, 100, 200, 400 req/s 단계 부하 구성
- [x] 각 조건 warm-up 뒤 15초 측정, 3회 반복
- [x] p95, p99, 오류와 거부율 측정
- [x] Hikari, executor, Redis, Outbox, Kafka, CPU와 heap 측정
- [x] 3회 중 2회 이상 기준 위반을 포화로 판정
- [x] 안전 구간과 최초 포화 지점 기록
- [x] 정합성 값과 의도적 Outbox backlog 기록
- [x] 로컬 결과를 운영 TPS나 SLO로 표현하지 않음
- [x] History, Study, Blog, evidence 작성
- [x] 45회 측정과 인수 판정 통과

## 검증 결과

- 전체 실행: 3개 시나리오 x 5개 부하 단계 x 3회 = 45회
- 정상: 안전 100 req/s, 최초 포화 200 req/s
- PG 150ms 지연: 안전 10 req/s, 최초 포화 50 req/s
- merchant skew: 안전 100 req/s, 최초 포화 200 req/s
- Hikari active 최대 10, pending 최대 0
- Kafka consumer 최종 lag 0
- ledger drift, 중복 결제와 취소, 처리 중 멱등성, 확인 대기: 모두 0
- 결과: PASS