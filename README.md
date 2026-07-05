# Payment Lifecycle Orchestration Core

Java 21과 Spring Boot 기반의 결제 코어 프로젝트입니다.
커머스 전체를 구현하는 것이 아니라, 결제 승인/취소 과정에서 발생하는 중복 요청, PG timeout unknown, 상태 전이, 원장 정합성, outbox, 복구 흐름을 검증하는 것이 목표입니다.

아직 모든 기능이 구현된 상태는 아닙니다.
현재는 프로젝트 목표와 경계를 정리하고, Spring Boot 실행 골격을 세팅한 단계입니다.

## 목표

이 프로젝트의 목표는 단순 결제 API가 아닙니다.
외부 PG 응답이 지연되거나 실패하거나 timeout이 나도 내부 결제 상태가 모순 없이 수렴하는 구조를 만드는 것입니다.

핵심 질문은 다음과 같습니다.

- 같은 승인 요청이 여러 번 들어와도 PG 승인은 한 번만 나가는가?
- 같은 취소 요청이 여러 번 들어와도 PG 취소는 한 번만 나가는가?
- PG timeout을 단순 실패로 오판하지 않는가?
- payment 상태, PG attempt, ledger, outbox event가 서로 다른 사실을 말하지 않는가?
- 장애 이후 stuck 상태를 진단하고 복구할 수 있는가?
- 고동시성 상황에서도 중복 처리와 상태 전이가 깨지지 않는가?

## 테스트 데이터 정책

이 프로젝트는 상시 운영 서버가 아니라 반복 가능한 로컬/k6 검증 환경을 기준으로 합니다.
그래서 테스트 데이터는 두 종류로 나눕니다.

- 유지되는 seed data: `users`, `merchants`, `payment_methods`, `merchant_pg_configs`
- 실행마다 초기화되는 runtime data: `account_balances`, `payments`, `payment_attempts`, `idempotency_records`, `ledger_entries`, `outbox_events`, `payment_cancellations`

Redis, Kafka, Elasticsearch 상태도 k6 실행 전 flush/reset 또는 rebuild합니다.
이 정책은 운영 정책이 아니라 같은 seed와 같은 scenario에서 항상 같은 시작 조건을 보장하기 위한 로컬 검증 정책입니다.

## 현재 진행 상태

| Track | Step | Status |
|------|------|--------|
| Motivation | 0. Project Motivation Blog | Done |
| Core | 1. Problem Definition & System Boundary | Done |
| Core | 2. Request Entry & Idempotency Gate | Done |
| Core | 3. Foundation & Runtime Skeleton | Skeleton Ready |
| Core | 4. Domain Model & State Machine | Planned |
| Core | 5. Approval Flow & Core Processing | Planned |
| Core | 6. PG Timeout & Unknown Resolution | Planned |
| Core | 7. Ledger & Transactional Outbox | Planned |
| Core | 8. Cancellation Lifecycle | Planned |
| Core | 9. k6 Consistency Verification | Planned |
| Core | 10. Observability | Planned |
| Core | 11. Failure Recovery & Reconciliation | Planned |
| Core | 12. Portfolio Packaging | Planned |
| Scale Readiness | 13~15 | Planned |
| Optional Research | 16~21 | Optional |

메인 완성 기준은 Step 0~15입니다.
Step 16 이후는 결제 코어가 흔들리지 않을 때 선택적으로 확장합니다.

## 기술 스택

- Java 21
- Spring Boot 3.3.x
- Gradle Kotlin DSL
- MySQL 8
- Redis 7
- JPA
- Actuator
- k6
- Prometheus / Grafana

## 프로젝트 구조

```text
src/main/java/io/hoony/payment/
├── PaymentServiceApplication.java
├── domain/
├── application/
├── infrastructure/
└── presentation/
    ├── common/
    └── health/
```

현재 존재하는 코드는 Spring Boot 애플리케이션, health endpoint, 공통 error response, trace id filter입니다.
결제 도메인 구현은 Step 4에서 상태 모델을 확정한 뒤 추가합니다.

## 실행 준비

로컬 인프라는 Docker Compose로 준비합니다.

```powershell
docker compose up -d mysql redis
```

전체 인프라가 필요하면 다음을 사용합니다.

```powershell
docker compose up -d
```

## Spring Boot 실행

현재 저장소에는 Gradle 프로젝트 파일은 준비되어 있지만 Gradle wrapper는 아직 없습니다.
따라서 Spring compile/test/boot smoke 검증은 wrapper 준비 후 진행합니다.

Gradle 또는 Gradle wrapper가 준비된 뒤 다음 명령으로 확인합니다.

```powershell
.\gradlew.bat test
.\gradlew.bat bootRun
```

Health API:

```text
GET http://localhost:8080/api/v1/health
```

예상 응답:

```json
{
  "status": "UP",
  "service": "harness-payment-service",
  "checkedAt": "2026-07-05T00:00:00Z"
}
```

## 문서

- Plan: [docs/project/PLANS.md](docs/project/PLANS.md)
- Task Map: [docs/project/TASK_MAP.md](docs/project/TASK_MAP.md)
- Step 0 Blog: [docs/blog/vol0_project_motivation.md](docs/blog/vol0_project_motivation.md)
- Step 1 Blog: [docs/blog/vol1_payment_orchestration_problem.md](docs/blog/vol1_payment_orchestration_problem.md)
- Step 2 Blog: [docs/blog/vol2_request_entry_and_idempotency_gate.md](docs/blog/vol2_request_entry_and_idempotency_gate.md)
- Step 3 Blog: [docs/blog/vol3_foundation_and_boundaries.md](docs/blog/vol3_foundation_and_boundaries.md)

## 검증

현재 하네스 자체 검증은 다음 명령으로 수행합니다.

```powershell
npm.cmd test
```

Spring Boot 검증은 Gradle wrapper 준비 후 수행합니다.

## 인코딩 주의

Markdown과 Java 파일은 UTF-8로 저장합니다.
Windows PowerShell에서 한글 문서가 깨져 보이면 다음처럼 확인합니다.

```powershell
Get-Content -Encoding UTF8 -Path docs/project/TASK_MAP.md
```