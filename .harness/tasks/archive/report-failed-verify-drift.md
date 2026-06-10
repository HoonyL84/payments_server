# TICKET: report-failed-verify-drift

## Type
fix

## Goal
- 실패한 검증이 남긴 작업 트리 변경도 함께 보고한다

## Scope
- 주 실패 원인 보존과 추가 worktree drift 경고

## Out of Scope
- 테스트 산출물 자동 삭제

## Acceptance Criteria
- [x] 실패와 작업 트리 변경이 동시에 발생하면 둘 다 출력과 trace에 기록된다

## Risk
- low

## Notes
- Created from harness CLI.
- 주 실패 단계와 종료 코드는 유지하면서 추가 작업 트리 변경을 별도 경고로 기록한다.

## Completion
- Completed At: 2026-06-10T11:18:55Z
- Verify Result: pass
- Rework Count: 0
- Last Failure: none
