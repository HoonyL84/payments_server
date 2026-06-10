# TICKET: smoke-test-active-isolation

## Type
fix

## Goal
- 기존 active 티켓이 있는 프로젝트에서도 스모크 테스트를 안전하게 실행한다

## Scope
- PowerShell과 Bash 스모크 테스트에서 기존 active 티켓 수 감지
- 기존 active 티켓이 하나면 스모크 티켓 생성 전에 L5 체크포인트 검증
- 스모크 검증 메트릭에 스모크 티켓 ID 명시

## Out of Scope
- 둘 이상의 실제 active 티켓을 자동 정리
- 사용자 티켓 파일 이동 또는 수정

## Acceptance Criteria
- [x] active 티켓이 없는 원본 템플릿에서 스모크 테스트가 통과한다
- [x] active 티켓이 하나인 프로젝트에서 스모크 테스트가 통과한다
- [x] 스모크 테스트가 기존 active 티켓을 수정하거나 삭제하지 않는다

## Risk
- 낮음

## Notes
- Created from harness CLI.

## Completion
- Completed At: 2026-06-10T05:35:51Z
- Verify Result: pass
- Rework Count: 0
- Last Failure: none
