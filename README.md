# Payment Lifecycle Orchestration Core

> **High-Reliability Payment Orchestration Engine (Java 21 · Spring Boot 3.5)**  
> 단순한 커머스 CRUD를 배제하고, 결제 승인·취소 라이프사이클에서 발생하는 **초동시성 중복 요청, PG Timeout 미결 상태 격리, 복식부기 원장 정합성, Kafka 기반 트랜잭셔널 아웃박스 비동기 소비, 원장 대사 배치, k6 카오스 부하 검증**에 집중한 금융급 결제 코어 시스템입니다.

---

## 핵심 질문 및 설계 목표 (Design Rationale)

1. **초동시성 멱등성 (Idempotency)**: 동일한 승인/취소 요청이 밀리초 단위로 수십 번 몰려도 외부 PG 호출과 내부 원장 반영은 정확히 1번만 일어나는가?
2. **PG Timeout 미결 상태 격리 (`PENDING_CONFIRMATION`)**: 외부 PG 통신 실패/타임아웃을 단순 '실패'로 단정하지 않고 미결 상태로 격리하여 안전하게 수렴시키는가?
3. **트랜잭션 경계 분리 (Transaction Boundary)**: 외부 PG 통신을 DB 트랜잭션 밖으로 분리하여 DB 커넥션 고갈을 방지하고, 결과를 복식부기 원장 및 아웃박스와 원자적으로 확정하는가?
4. **2단계 취소 예약 모델 (Two-Phase Cancellation)**: 부분 취소 경쟁 상황에서 확정 취소액, 진행 중 예약액, 신규 요청액의 합이 승인액을 절대 초과하지 않도록 보장하는가?
5. **이벤트 무손실 비동기 파이프라인 (Transactional Outbox)**: 실패한 이벤트를 성공으로 속이지 않고, Kafka를 통한 중복 전제 멱등 소비 파이프라인을 구축했는가?
6. **장애 수렴 및 원장 대사 (Reconciliation Batch)**: PG 거래 스냅샷과 내부 원장 불일치를 주기적으로 자동 대사하고 누락을 수렴시키는가?

---

## 멀티 모듈 아키텍처 (Multi-Module Architecture)

```text
harness-payment-service/
├── src/main/java/            # [Core] 결제 코어 (승인/취소 API, 상태 머신, 복식부기 원장, 멱등 필터, Outbox 발행)
├── mock-pg-server/           # [Mock PG] 네트워크 지연, Timeout, 간헐적 에러를 주입하는 가상 PG 서버
├── payment-event-consumer/   # [Kafka Consumer] Transactional Outbox 이벤트를 비동기 수신하여 멱등 처리
├── reconciliation-batch/     # [Batch] 외부 PG 거래내역과 내부 원장 간의 데이터 불일치를 감지·대사하는 배치
├── load-tests/k6/            # [k6 Scenarios] 동시성 경합, 카오스 장애 전파, 포화 임계점 등 8대 부하 시나리오
└── infra/monitoring/         # [Observability] Prometheus & Grafana 모니터링 프로비저닝
```

---

## 핵심 라이프사이클 및 상태 전이 모델

### 1. 결제 승인 수렴 모델
- `REQUESTED` $\to$ `PENDING_CONFIRMATION` $\to$ `CONFIRMING` $\to$ `APPROVED` or `FAILED`
- PG 통신 중 타임아웃 발생 시 실패로 닫지 않고 `PENDING_CONFIRMATION` 상태로 격리.
- 이후 스케줄러 또는 Confirm 재시도 요청이 `CONFIRMING` 락을 획득하여 PG 최종 결과를 조회한 뒤 안전하게 최종 상태로 수렴.

### 2. 복식부기 원장 (Double-Entry Ledger) & 트랜잭션 경계
- **PG 호출 분리**: 외부 PG 네트워크 호출(수백 ms~초 단위)은 DB 커넥션을 점유하지 않도록 **DB 트랜잭션 외부**에서 수행.
- **원자적 확정**: PG 성공 응답 수신 후, 결제 상태 갱신 + 차변/대변 원장 기록(`ledger_entries`) + 아웃박스 이벤트(`outbox_events`)를 **단일 DB 트랜잭션으로 원자적 커밋**.

### 3. 2단계 취소 예약 (Reservation-First Cancellation)
- 취소 요청 시 `payment_cancellations`에 `CANCELING` 상태를 먼저 기록하여 잔여 취소 가능 한도를 즉시 차감(동시성 오버 리펀드 원천 차단).
- 외부 PG 취소 성공 확인 후 최종 원장 역분개 및 취소 확정.

---

## k6 부하 검증 및 카오스 엔지니어링 (8 Scenarios)

`load-tests/k6/` 디렉토리에 고동시성 및 네트워크 장애 시뮬레이션 스크립트를 완비하여 시스템 한계를 정량 검증했습니다.

| 시나리오 스크립트 | 검증 목적 및 테스트 내용 |
| :--- | :--- |
| **`hotspot-contention.js`** | 동일 가맹점/유저에 대한 초고밀도 동시 요청 시 멱등 락 및 DB 교착 상태 방지 |
| **`failure-propagation-chaos.js`** | PG 의존성 장애/타임아웃 발생 시 서킷 브레이커와 미결 상태 격리 동작 검증 |
| **`multi-instance-convergence.js`** | 결제 서버 다중화(Multi-Instance) 환경에서 분산 멱등성 및 상태 수렴 일관성 |
| **`capacity-saturation.js`** | TPS 한계치 도달 시 DB 커넥션 풀 및 스레드 풀 포화 지점 정량 측정 |
| **`load-shedding-backpressure.js`** | 과부하 상황에서 서버가 크래시되지 않고 안전하게 요청을 거절하는 배압(Backpressure) 검증 |
| **`failure-recovery.js`** | 외부 장애 복구 후 `PENDING_CONFIRMATION` 건들이 정상 복구·수렴되는 과정 |
| **`payment-consistency.js`** | 승인과 부분 취소가 반복적으로 얽히는 상황에서 원장 잔액의 수학적 정합성 |
| **`partition-pressure.js`** | 샤딩(Sharding) 도입 전 단일 DB의 파티션별 부하 분포 및 병목 측정 |

---

## 기술 블로그 시리즈 (Engineering Deep Dive, 24편)

설계 결정의 배경과 문제 해결 과정을 Velog에 연재하여 모든 아키텍처의 근거를 문서화했습니다.

- [0. 결제 프로젝트를 시작하는 이유](https://velog.io/@hoonyl/0.-%EA%B2%B0%EC%A0%9C-%ED%94%84%EB%A1%9C%EC%A0%9D%ED%8A%B8%EB%A5%BC-%EC%8B%9C%EC%9E%91%ED%95%98%EB%8A%94-%EC%9D%B4%EC%9C%A0)
- [1. 결제 코어의 경계를 먼저 정한 이유](https://velog.io/@hoonyl/1.-%EA%B2%B0%EC%A0%9C-%EC%BD%94%EC%96%B4%EC%9D%98-%EA%B2%BD%EA%B3%84%EB%A5%BC-%EB%A8%BC%EC%A0%80-%EC%A0%95%ED%95%9C-%EC%9D%B4%EC%9C%A0)
- [2. 결제 요청은 어디서부터 통제해야 하는가](https://velog.io/@hoonyl/2.-%EA%B2%B0%EC%A0%9C-%EC%9A%94%EC%B2%AD%EC%9D%80-%EC%96%B4%EB%94%94%EC%84%9C%EB%B6%80%ED%84%B0-%ED%86%B5%EC%A0%9C%ED%95%B4%EC%95%BC-%ED%95%98%EB%8A%94%EA%B0%80)
- [3. 결제 코어를 올릴 실행 골격](https://velog.io/@hoonyl/3.-%EA%B2%B0%EC%A0%9C-%EC%BD%94%EC%96%B4%EB%A5%BC-%EC%98%AC%EB%A6%B4-%EC%8B%A4%ED%96%89-%EA%B3%A8%EA%B2%A9)
- [4. 결제는 상태 전이로 먼저 모델링해야 한다](https://velog.io/@hoonyl/4.-%EA%B2%B0%EC%A0%9C-%EB%8A%94-%EC%83%81%ED%83%9C-%EC%A0%84%EC%9D%B4%EB%A1%9C-%EB%A8%BC%EC%A0%80-%EB%AA%A8%EB%8D%B8%EB%A7%81%ED%95%B4%EC%95%BC-%ED%95%9C%EB%8B%A4)
- [5. 결제 승인은 API보다 중복 호출 방지가 먼저다](https://velog.io/@hoonyl/5.-%EA%B2%B0%EC%A0%9C-%EC%8A%B9%EC%9D%B8%EC%9D%80-API%EB%B3%B4%EB%8B%A4-%EC%A4%91%EB%B3%B5-%ED%98%B8%EC%B6%9C-%EB%B0%A9%EC%A7%80%EA%B0%80-%EB%A8%BC%EC%A0%80%EB%8B%A4)
- [6. PG timeout은 실패가 아니라 확인이 필요한 상태다](https://velog.io/@hoonyl/6.-PG-timeout%EC%9D%80-%EC%8B%A4%ED%8C%A8%EA%B0%80-%EC%95%84%EB%8B%88%EB%9D%BC-%ED%99%95%EC%9D%B8%EC%9D%B4-%ED%95%84%EC%9A%94%ED%95%9C-%EC%83%81%ED%83%9C%EB%8B%A4)
- [7. PG 호출을 DB 트랜잭션 밖으로 분리한 이유](https://velog.io/@hoonyl/7.-PG-%ED%98%B8%EC%B6%9C%EC%9D%84-DB-%ED%8A%B8%EB%9E%9C%EC%9E%AD%EC%85%98-%EB%B0%96%EC%9C%BC%EB%A1%9C-%EB%B6%84%EB%A6%AC%ED%95%9C-%EC%9D%B4%EC%9C%A0)
- [8. 취소 금액을 PG 응답 전에 예약한 이유](https://velog.io/@hoonyl/8.-%EC%B7%A8%EC%86%8C-%EA%B8%88%EC%95%A1%EC%9D%84-PG-%EC%9D%91%EB%8B%B5-%EC%A0%84%EC%97%90-%EC%98%88%EC%95%BD%ED%95%9C-%EC%9D%B4%EC%9C%A0)
- [9. 결제 서버의 느린 지점을 나눠서 보기](https://velog.io/@hoonyl/9.-%EA%B2%B0%EC%A0%9C-%EC%84%9C%EB%B2%84%EC%9D%98-%EB%8A%90%EB%A6%B0-%EC%A7%80%EC%A0%90%EC%9D%84-%EB%82%98%EB%88%A0%EC%84%9C-%EB%B3%B4%EA%B8%B0)
- [10. 부하 테스트에서 TPS보다 먼저 확인한 것](https://velog.io/@hoonyl/10.-%EB%B6%80%ED%95%98-%ED%85%8C%EC%8A%A4%ED%8A%B8%EC%97%90%EC%84%9C-TPS%EB%B3%B4%EB%8B%A4-%EB%A8%BC%EC%A0%80-%ED%99%95%EC%9D%B8%ED%95%9C-%EA%B2%83)
- [11. 장애 뒤에 남은 결제를 모두 자동 복구하지 않은 이유](https://velog.io/@hoonyl/11.-%EC%9E%A5%EC%95%A0-%EB%92%A4%EC%97%90-%EB%82%A8%EC%9D%80-%EA%B2%B0%EC%A0%9C%EB%A5%BC-%EB%AA%A8%EB%91%90-%EC%9E%90%EB%8F%99-%EB%B3%B5%EA%B5%AC%ED%95%98%EC%A7%80-%EC%95%8A%EC%9D%80-%EC%9D%B4%EC%9C%A0)
- [12. 기능을 더하기 전에 결제 코어를 다시 확인한 이유](https://velog.io/@hoonyl/12.-%EA%B8%B0%EB%8A%A5%EC%9D%84-%EB%8D%94%ED%95%98%EA%B8%B0-%EC%A0%84%EC%97%90-%EA%B2%B0%EC%A0%9C-%EC%BD%94%EC%96%B4%EB%A5%BC-%EB%8B%A4%EC%8B%9C-%ED%99%95%EC%9D%B8%ED%95%9C-%EC%9D%B4%EC%9C%A0)
- [13. 같은 결제 요청이 몰릴 때 DB 앞에서 한 번 더 막은 이유](https://velog.io/@hoonyl/13.-%EA%B0%99%EC%9D%80-%EA%B2%B0%EC%A0%9C-%EC%9A%94%EC%B2%AD%EC%9D%B4-%EB%AA%B0%EB%A6%B4-%EB%95%8C-DB-%EC%95%9E%EC%97%90%EC%84%9C-%ED%95%9C-%EB%B2%88-%EB%8D%94-%EB%A7%89%EC%9D%80-%EC%9D%B4%EC%9C%A0)
- [14. 결제 서버를 두 대 띄우면 같은 결과가 나오는가](https://velog.io/@hoonyl/14.-%EA%B2%B0%EC%A0%9C-%EC%84%9C%EB%B2%84%EB%A5%BC-%EB%91%90-%EB%8C%80-%EB%9D%84%EC%9A%B0%EB%A9%B4-%EA%B0%99%EC%9D%80-%EA%B2%B0%EA%B3%BC%EA%B0%80-%EB%82%98%EC%98%A4%EB%8A%94%EA%B0%80)
- [15. 결제 요청이 몰릴 때 모두 받지 않기로 한 이유](https://velog.io/@hoonyl/15.-%EA%B2%B0%EC%A0%9C-%EC%9A%94%EC%B2%AD%EC%9D%B4-%EB%AA%B0%EB%A6%B4-%EB%95%8C-%EB%AA%A8%EB%91%90-%EB%B0%9B%EC%A7%80-%EC%95%8A%EA%B8%B0%EB%A1%9C-%ED%95%9C-%EC%9D%B4%EC%9C%A0)
- [15-1. 결제 코어는 무엇을 통과해야 끝났다고 할 수 있는가](https://velog.io/@hoonyl/15-1.-%EA%B2%B0%EC%A0%9C-%EC%BD%94%EC%96%B4%EB%8A%94-%EB%AC%B4%EC%97%87%EC%9D%84-%ED%86%B5%EA%B3%BC%ED%95%B4%EC%95%BC-%EB%81%9D%EB%82%AC%EB%8B%A4%EA%B3%A0-%ED%95%A0-%EC%88%98-%EC%9E%88%EB%8A%94%EA%B0%80)
- [16. PG timeout을 실제 네트워크 경계에서 다시 검증한 이유](https://velog.io/@hoonyl/16.-PG-timeout%EC%9D%84-%EC%8B%A4%EC%A0%9C-%EB%84%A4%ED%8A%B8%EC%9B%8C%ED%81%AC-%EA%B2%BD%EA%B3%84%EC%97%90%EC%84%9C-%EB%8B%A4%EC%8B%9C-%EA%B2%80%EC%A6%9D%ED%95%9C-%EC%9D%B4%EC%9C%A0)
- [17. Outbox를 Kafka에 보낸 뒤에도 중복을 전제로 한 이유](https://velog.io/@hoonyl/17.-Outbox%EB%A5%BC-Kafka%EC%97%90-%EB%B3%B4%EB%82%B8-%EB%92%A4%EC%97%90%EB%8F%84-%EC%A4%91%EB%B3%B5%EC%9D%84-%EC%A0%84%EC%A0%9C%EB%A1%9C-%ED%95%9C-%EC%9D%B4%EC%9C%A0)
- [18. 결제 대사 배치를 처음부터 다시 돌리지 않게 만든 이유](https://velog.io/@hoonyl/18.-%EA%B2%B0%EC%A0%9C-%EB%8C%80%EC%82%AC-%EB%B0%B0%EC%B9%98%EB%A5%BC-%EC%B2%98%EC%9D%8C%EB%B6%80%ED%84%B0-%EB%8B%A4%EC%8B%9C-%EB%8F%8C%EB%A6%AC%EC%A7%80-%EC%95%8A%EA%B2%8C-%EB%A7%8C%EB%93%A0-%EC%9D%B4%EC%9C%A0)
- [19. 의존성 장애를 실제 네트워크에서 끊어 본 이유](https://velog.io/@hoonyl/19.-%EC%9D%98%EC%A1%B4%EC%84%B1-%EC%9E%A5%EC%95%A0%EB%A5%BC-%EC%8B%A4%EC%A0%9C-%EB%84%A4%ED%8A%B8%EC%9B%8C%ED%81%AC%EC%97%90%EC%84%9C-%EB%81%8A%EC%96%B4-%EB%B3%B8-%EC%9D%B4%EC%9C%A0)
- [20. 결제 서버의 최대 TPS보다 포화가 시작되는 지점을 본 이유](https://velog.io/@hoonyl/20.-%EA%B2%B0%EC%A0%9C-%EC%84%9C%EB%B2%84%EC%9D%98-%EC%B5%9C%EB%8C%80-TPS%EB%B3%B4%EB%8B%A4-%ED%8F%AC%ED%99%94%EA%B0%80-%EC%8B%9C%EC%9E%91%EB%90%98%EB%8A%94-%EC%A7%80%EC%A0%90%EC%9D%84-%EB%B3%B8-%EC%9D%B4%EC%9C%A0)
- [21. Sharding을 구현하지 않기로 결정한 이유](https://velog.io/@hoonyl/21.-Sharding%EC%9D%84-%EA%B5%AC%ED%98%84%ED%95%98%EC%A7%80-%EC%95%8A%EA%B8%B0%EB%A1%9C-%EA%B2%B0%EC%A0%95%ED%95%9C-%EC%9D%B4%EC%9C%A0)
- [22. 결제 이벤트를 잃지 않으려면 실패를 성공으로 기록하지 말아야 한다](https://velog.io/@hoonyl/22.-%EA%B2%B0%EC%A0%9C-%EC%9D%B4%EB%B2%A4%ED%8A%B8%EB%A5%BC-%EC%9E%83%EC%A7%80-%EC%95%8A%EC%9C%BC%EB%A0%A4%EB%A9%B4-%EC%8B%A4%ED%8C%A8%EB%A5%BC-%EC%84%B1%EA%B3%B5%EC%9C%BC%EB%A1%9C-%EA%B8%B0%EB%A1%9D%ED%95%98%EC%A7%80-%EB%A7%90%EC%95%84%EC%95%BC-%ED%95%9C%EB%8B%A4)

---

## 기술 스택 (Tech Stack)

- **언어 및 런타임**: Java 21 (Records, Pattern Matching, Sealed Types)
- **프레임워크**: Spring Boot 3.5.x, Spring Data JPA
- **데이터베이스 및 캐시**: MySQL 8.4 (LTS), Redis 7 (멱등키 관리 및 분산락)
- **메시징 브로커**: Apache Kafka (Transactional Outbox Event)
- **데이터베이스 마이그레이션**: Flyway 12
- **테스트 및 검증**: k6 (부하/카오스 테스트 8종), Testcontainers, JUnit 5, AssertJ
- **관측성**: Spring Actuator, Prometheus, Grafana

---

## 실행 및 검증 가이드

### 1. 인프라 실행 (Docker Compose)

```powershell
docker compose up -d mysql reconciliation-mysql redis kafka zookeeper
```

### 2. 전체 멀티 모듈 빌드 및 단위/통합 테스트 (17 Tasks)

Gradle wrapper가 포함되어 있으므로 별도 Gradle 설치 없이 전 모듈 테스트를 수행합니다.

```powershell
.\gradlew.bat test
```

### 3. 모듈별 실행

```powershell
# 1) 결제 코어 서비스 실행 (포트 8080)
.\gradlew.bat bootRun

# 2) 가상 Mock PG 서버 실행 (포트 8090)
.\gradlew.bat :mock-pg-server:bootRun

# 3) Kafka 결제 이벤트 비동기 컨슈머 실행
.\gradlew.bat :payment-event-consumer:bootRun

# 4) PG-원장 대사 배치 실행
.\gradlew.bat :reconciliation-batch:bootRun
```

### 4. k6 부하 및 카오스 검증 실행

```powershell
# 결제 멱등성 및 원장 일관성 검증
k6 run load-tests/k6/payment-consistency.js

# PG 장애 카오스 전파 및 서킷 브레이커 검증
k6 run load-tests/k6/failure-propagation-chaos.js

# 다중 인스턴스 분산 수렴 검증
k6 run load-tests/k6/multi-instance-convergence.js
```

---

## 테스트 데이터 및 로컬 재현성 정책

이 프로젝트는 반복 가능한 로컬/k6 검증 환경을 기준으로 합니다.

- **영속 DB Seed 데이터**: `merchants` 및 PG 라우팅 설정
- **실행 단위 초기화 데이터**: `payments`, `payment_attempts`, `idempotency_records`, `ledger_entries`, `outbox_events`, `payment_cancellations`
- **Mock PG 상태**: `pg_test_balances`
- 동일한 시나리오에서 항상 동일한 시작 조건을 보장하도록 설계되어 있습니다.
