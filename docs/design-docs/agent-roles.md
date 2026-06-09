# Agent Roles

이 문서는 하네스 안에서 AI가 상황별로 어떤 역할을 수행해야 하는지 정의한다.

역할은 별도 프로세스가 아니라, `scripts/run-agent.sh --role <role>`로 주입되는 시스템 프롬프트 계약이다. 멀티에이전트 런타임을 도입하지 않아도 하나의 AI가 작업 단계마다 다른 책임을 수행할 수 있게 한다.

## 역할 목록

| Role | 책임 | 주요 입력 | 주요 출력 |
|------|------|-----------|-----------|
| `planner` | 목표, 범위, 성공 기준 정리 | `PLANS.md`, active EXEC_PLAN | 단계별 계획, 가정, 완료 기준 |
| `architect` | 설계와 고위험 결정 검토 | design docs, ADR, tech stack | 선택지, 트레이드오프, 승인 필요 여부 |
| `implementer` | 최소 범위 코드 구현 | EXEC_PLAN, core beliefs, tech stack | 코드 변경, 검증 방법 |
| `reviewer` | 버그/회귀/테스트 누락 검토 | diff, 요구사항, 테스트 | severity 순 finding |
| `verifier` | 테스트/빌드/CI/보안 확인 | scripts, CI 결과, 로그 | 통과/실패 원인, 재시도 계획 |
| `recorder` | 작업 로그와 결과 기록 | traces, metrics, task context | 요약, changelog 후보, observability 기록 |
| `memory` | 장기 기억 갱신 판단 | memory governance, 반복 지식 | memory 업데이트 제안 |
| `release` | 커밋/PR/완료 처리 | git diff, verify 결과 | 커밋/PR 요약, 완료 체크리스트 |

## 기본 라우팅

`run-agent.sh`에서 `--role`을 생략하면 `--type`에 따라 기본 역할을 추론한다.

| Task Type | Default Role |
|-----------|--------------|
| `code` | `implementer` |
| `architect` | `architect` |
| `review` | `reviewer` |
| `docs` | `recorder` |
| `default` | `implementer` |

## 승인 규칙

아래 상황은 역할과 무관하게 사용자의 명시 승인을 받아야 한다.

- DB 스키마 변경
- 인프라 또는 배포 설정 변경
- 인증/권한 정책 변경
- 보안 규칙 완화
- 비용이 발생하는 외부 API 추가
- 프로젝트 범위가 커지는 기능 추가

## 사용 예시

```bash
bash scripts/run-agent.sh --role planner "PLANS.md 기준으로 첫 태스크를 쪼개줘"
bash scripts/run-agent.sh --role architect --type architect "Redis 캐싱 전략을 검토해줘"
bash scripts/run-agent.sh --role reviewer --type review "현재 diff를 리뷰해줘"
bash scripts/run-agent.sh --role verifier "최근 CI 실패 원인을 정리해줘"
```
