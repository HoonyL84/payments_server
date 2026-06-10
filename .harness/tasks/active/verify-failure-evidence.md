# TICKET: verify-failure-evidence

## Type
fix

## Goal
- verify 실패의 실제 원인과 재작업 이력을 성공 후에도 보존한다

## Scope
- 실패 단계의 stderr/stdout에서 핵심 오류 한 줄 추출
- 터미널과 verify trace에 실패 상세 출력
- 성공 재검증 시 이전 last_fail_reason과 rework_count 유지
- 토이 프로젝트의 의도적 실패 실험으로 end-to-end 검증

## Out of Scope
- 전체 로그를 metrics JSON에 저장
- AI 기반 오류 분류

## Acceptance Criteria
- [ ] 실패 verify 출력과 trace에 실제 AssertionError가 보인다
- [ ] 실패 metrics의 last_fail_reason에 실제 오류가 포함된다
- [ ] 성공 verify 후에도 이전 실패 원인과 rework_count가 유지된다
- [ ] complete-task archive에 같은 재작업 증거가 기록된다

## Risk
- 낮음

## Notes
- Created from harness CLI.
