# Step 19. Failure Propagation & Chaos Verification

## 목표

격리된 실제 네트워크 장애 환경에서 PG, Redis, MySQL, Kafka 장애가 retry storm과 자원 고갈로 번지지 않고 정해진 실패 상태와 복구 경로로 수렴하는지 검증한다.

## 완료 기준

- [x] Toxiproxy와 chaos 전용 MySQL, Redis, Kafka, mock PG 구성
- [x] PG 지연, timeout, connection reset, 중단 검증
- [x] Redis 지연과 중단 시 DB 멱등성 fallback 검증
- [x] MySQL 지연, pool 고갈, 중단과 복구 검증
- [x] Kafka 중단, consumer 정지, app 재시작 검증
- [x] Outbox 발행 뒤 상태 갱신 전 중단과 lease 회수 검증
- [x] retry, circuit, admission, queue 상한 검증
- [x] unknown과 manual review 분리 검증
- [x] 장애 해제 뒤 payment, ledger, outbox, consumer 수렴 시간 기록
- [x] History, Study, Blog, evidence 작성
- [x] 격리된 전체 chaos 검증 통과

## 검증 결과

- k6 네트워크 장애 검사: 35/35 통과
- 의존성 장애 응답 p99: 559.07ms
- Outbox claim lease 회수: 31.09초
- Kafka 복구: 8.48초
- consumer 재시작 복구: 9.80초
- app 재시작 복구: 9.57초
- 최종 ledger drift, pending outbox, pending confirmation, processing idempotency: 모두 0
- 최종 processed event 5건과 side effect 5건 일치