# TICKET: completion-order-docs

## Type
fix

## Goal
- 완료 메타데이터가 같은 커밋에 포함되도록 작업 순서를 일관되게 안내한다

## Scope
- AGENTS, 실행 모드, 가이드의 마감 순서를 실제 complete-task 동작과 일치시킨다
- CLI 시작·L5 안내와 complete-task 종료 메시지에 완료 기록 커밋 단계를 표시한다

## Out of Scope
- complete-task의 브랜치 안전 검사 완화
- 자동 커밋 또는 자동 푸시 강제

## Acceptance Criteria
- [x] 구현 변경과 완료 메타데이터가 모두 Git 이력에 남는 순서를 안내한다
- [x] 일반 티켓과 L5 안내가 같은 순서를 사용한다
- [x] complete-task 실행 후 다음 행동이 명확히 출력된다

## Risk
- 낮음

## Notes
- Created from harness CLI.
