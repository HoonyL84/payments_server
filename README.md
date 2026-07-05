# Payment Lifecycle Orchestration Core

Java 21과 Spring Boot 기반의 결제 코어 프로젝트입니다.
커머스 전체를 구현하는 것이 아니라, 결제 승인/취소 과정에서 발생하는 중복 요청, PG timeout unknown, 상태 전이, 원장 정합성, outbox, 복구 흐름을 검증하는 것이 목표입니다.

아직 모든 기능이 구현된 상태는 아닙니다.
현재는 프로젝트 목표와 경계를 정리하고, Spring Boot 실행 골격과 결제 상태 모델을 세팅한 단계입니다.

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

현재 존재하는 코드는 Spring Boot 애플리케이션, health endpoint, 공통 error response, trace id filter, 결제/취소 상태 모델입니다.

도메인 모델은 결제 승인과 취소를 CRUD가 아니라 상태 전이로 다룹니다.
금액은 정수 minor unit으로 처리하고, 결제 생성 이후 승인 금액은 변경하지 않습니다.
잘못된 상태 전이는 예외로 차단합니다.

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

Gradle wrapper가 포함되어 있으므로 별도 Gradle 설치 없이 실행할 수 있습니다.

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

## 검증

하네스 자체 검증은 다음 명령으로 수행합니다.

```powershell
npm.cmd test
```

Spring Boot 검증은 다음 명령으로 수행합니다.

```powershell
.\gradlew.bat test
```
