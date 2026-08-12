# TICKET: ci-test-diagnostics

## Type
fix

## Goal
- Expose failing Gradle test details and retain CI reports

## Scope
- Gradle test logging and GitHub Actions test report artifacts

## Out of Scope
- Payment domain behavior and test assertions

## Acceptance Criteria
- [ ] Failed Gradle tests print the test name and full exception; CI preserves HTML reports on failure

## Risk
- low

## Notes
- Created from harness CLI.

## Completion
- Completed At: 2026-08-12T01:53:16Z
- Verify Result: pass
- Rework Count: 0
- Last Failure: none
