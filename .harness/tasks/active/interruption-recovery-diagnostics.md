# TICKET: interruption-recovery-diagnostics

## Type
feat

## Goal
- 강제 종료 후 실제 상태를 바탕으로 안전한 재개 행동을 판정한다

## Scope
- recover 진단 명령과 회귀 테스트 및 문서

## Out of Scope
- 자동 reset clean 및 프로세스 자동 재시작

## Acceptance Criteria
- [x] 중단 상태에서 재시도 재검증 완료 수동점검을 구분한다.
- [x] 기존 active 티켓, 체크포인트, 작업 트리 가드, verify 콘텐츠 지문을 재사용한다.
- [x] 파괴적 Git 복구 없이 실제 중단 시뮬레이션을 통과한다.

## Risk
- medium

## Notes
- Created from harness CLI.
- 토이 저장소에서 `retry_agent`, `inspect_and_verify`, `fix_and_reverify`, `reverify_required`, `ready_to_complete`를 실제로 확인했다.
