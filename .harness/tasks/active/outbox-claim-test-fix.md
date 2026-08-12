# TICKET: outbox-claim-test-fix

## Type
fix

## Goal
- Make the concurrent outbox claim integration test accept valid partial batches

## Scope
- Outbox claim persistence integration test only

## Out of Scope
- Outbox claim production implementation and Step 18 reconciliation work

## Acceptance Criteria
- [ ] The test proves concurrent claims are disjoint and all pending events can be claimed and published

## Risk
- low

## Notes
- Created from harness CLI.
