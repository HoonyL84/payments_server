# Payment Lifecycle Orchestration Core

Java 21과 Spring Boot 기반의 결제 코어 프로젝트입니다.
커머스 전체를 구현하는 것이 아니라, 결제 승인/취소 과정에서 발생하는 중복 요청, PG timeout unknown, 상태 전이, 원장 정합성, outbox, 복구 흐름을 검증하는 것이 목표입니다.

아직 모든 기능이 구현된 상태는 아닙니다.
현재는 프로젝트 목표와 경계를 정리하고, Spring Boot 실행 골격, 결제 상태 모델, 멱등 승인 흐름까지 구현한 단계입니다.

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

## 블로그

- [0. 결제 프로젝트를 시작하는 이유](https://velog.io/@hoonyl/0.-%EA%B2%B0%EC%A0%9C-%ED%94%84%EB%A1%9C%EC%A0%9D%ED%8A%B8%EB%A5%BC-%EC%8B%9C%EC%9E%91%ED%95%98%EB%8A%94-%EC%9D%B4%EC%9C%A0)
- [1. 결제 코어의 경계를 먼저 정한 이유](https://velog.io/@hoonyl/1.-%EA%B2%B0%EC%A0%9C-%EC%BD%94%EC%96%B4%EC%9D%98-%EA%B2%BD%EA%B3%84%EB%A5%BC-%EB%A8%BC%EC%A0%80-%EC%A0%95%ED%95%9C-%EC%9D%B4%EC%9C%A0)
- [2. 결제 요청은 어디서부터 통제해야 하는가](https://velog.io/@hoonyl/2.-%EA%B2%B0%EC%A0%9C-%EC%9A%94%EC%B2%AD%EC%9D%80-%EC%96%B4%EB%94%94%EC%84%9C%EB%B6%80%ED%84%B0-%ED%86%B5%EC%A0%9C%ED%95%B4%EC%95%BC-%ED%95%98%EB%8A%94%EA%B0%80)
- [3. 결제 코어를 올릴 실행 골격](https://velog.io/@hoonyl/3.-%EA%B2%B0%EC%A0%9C-%EC%BD%94%EC%96%B4%EB%A5%BC-%EC%98%AC%EB%A6%B4-%EC%8B%A4%ED%96%89-%EA%B3%A8%EA%B2%A9)
- [4. 결제는 상태 전이로 먼저 모델링해야 한다](https://velog.io/@hoonyl/4.-%EA%B2%B0%EC%A0%9C%EB%8A%94-%EC%83%81%ED%83%9C-%EC%A0%84%EC%9D%B4%EB%A1%9C-%EB%A8%BC%EC%A0%80-%EB%AA%A8%EB%8D%B8%EB%A7%81%ED%95%B4%EC%95%BC-%ED%95%9C%EB%8B%A4)
- [5. 결제 승인은 API보다 중복 호출 방지가 먼저다](https://velog.io/@hoonyl/5.-%EA%B2%B0%EC%A0%9C-%EC%8A%B9%EC%9D%B8%EC%9D%80-API%EB%B3%B4%EB%8B%A4-%EC%A4%91%EB%B3%B5-%ED%98%B8%EC%B6%9C-%EB%B0%A9%EC%A7%80%EA%B0%80-%EB%A8%BC%EC%A0%80%EB%8B%A4)

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
    ├── health/
    └── payment/
```

현재 존재하는 코드는 Spring Boot 애플리케이션, health endpoint, 공통 error response, trace id filter, 결제/취소 상태 모델, 멱등 승인 API 흐름입니다.

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
