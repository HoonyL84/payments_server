# TICKET: active-ticket-ambiguity

## Type
fix

## Goal
- 복수 active 티켓의 암묵적 생성과 local 메트릭 오귀속을 차단한다

## Scope
- 두 번째 active 티켓 시작을 기본 차단
- 명시적 `--allow-parallel` 병렬 시작 지원
- `verify --task <ticket>` 명시 귀속 지원
- 복수 active에서 암묵적 verify/run-agent 차단
- PowerShell/Bash smoke flow의 병렬 옵션 회귀 검증

## Out of Scope
- 병렬 작업 자동 스케줄링
- worktree 자동 병합

## Acceptance Criteria
- [x] 두 번째 start-ticket은 기본 실패한다
- [x] --allow-parallel을 사용하면 두 번째 티켓을 시작할 수 있다
- [x] 복수 active에서 task 없는 verify는 local 메트릭을 만들지 않고 실패한다
- [x] verify --task가 지정 티켓 메트릭에 기록된다
- [x] 기존 active가 있는 smoke test가 명시적 병렬 모드로 통과한다

## Risk
- 낮음

## Notes
- Created from harness CLI.
