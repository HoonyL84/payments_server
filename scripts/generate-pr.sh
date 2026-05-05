#!/bin/bash
# ==============================================================================
# [Harness] PR 자동 생성기
# Usage: bash scripts/generate-pr.sh <task-name>
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR" || exit 1

TASK_NAME=$1

if [ -z "$TASK_NAME" ]; then
  echo "❌ 사용법: bash scripts/generate-pr.sh <task-name>"
  exit 1
fi

PLAN_FILE_ACTIVE=".harness/tasks/active/${TASK_NAME}.md"
PLAN_FILE_ARCHIVE=".harness/tasks/archive/${TASK_NAME}.md"
PR_FILE=".harness/tasks/archive/${TASK_NAME}-PR.md"

if [ -f "$PLAN_FILE_ARCHIVE" ]; then
  PLAN_FILE="$PLAN_FILE_ARCHIVE"
elif [ -f "$PLAN_FILE_ACTIVE" ]; then
  PLAN_FILE="$PLAN_FILE_ACTIVE"
else
  echo "❌ 태스크 문서를 찾을 수 없습니다: $TASK_NAME ($PLAN_FILE_ACTIVE 또는 $PLAN_FILE_ARCHIVE)"
  exit 1
fi

echo "🚀 [Harness] PR 초안 생성 중: $TASK_NAME"

GOAL_CONTENT=$(awk '/^##.*(목표|Goal)/{flag=1; next} /^##/{flag=0} flag' "$PLAN_FILE")
[ -z "$GOAL_CONTENT" ] && GOAL_CONTENT="- 목표 내용 누락"

APPROACH_CONTENT=$(awk '/^##.*(접근|해결|Approach)/{flag=1; next} /^##/{flag=0} flag' "$PLAN_FILE")
[ -z "$APPROACH_CONTENT" ] && APPROACH_CONTENT="- 접근법 내용 누락"

ASSUMPTIONS_CONTENT=$(awk '/^##.*(가정|결정|Trade-off|Assumption)/{flag=1; next} /^##/{flag=0} flag' "$PLAN_FILE")
[ -z "$ASSUMPTIONS_CONTENT" ] && ASSUMPTIONS_CONTENT="- 특별한 가정이나 트레이드오프 없음"

VERIFY_LOG="observability/metrics/${TASK_NAME}.verify.json"
DONE_LOG="observability/metrics/${TASK_NAME}.done.json"
CHECK_BOX="[ ]"
if [ -f "$VERIFY_LOG" ]; then
  if grep -q '"result":"pass"' "$VERIFY_LOG" || grep -q '"result": "pass"' "$VERIFY_LOG"; then
    CHECK_BOX="[x]"
  fi
elif [ -f "$DONE_LOG" ]; then
  if grep -q '"verify_result":"pass"' "$DONE_LOG" || grep -q '"verify_result": "pass"' "$DONE_LOG"; then
    CHECK_BOX="[x]"
  fi
fi

# 기본 PR 템플릿 생성
mkdir -p "$(dirname "$PR_FILE")"
cat <<EOF > "$PR_FILE"
# PR: $TASK_NAME

## 🎯 목표 (Goal)
$GOAL_CONTENT

## 🛠️ 해결 방식 (Approach)
$APPROACH_CONTENT

## 💡 주요 결정 사항 (Trade-offs & Assumptions)
$ASSUMPTIONS_CONTENT

## ✅ 테스트/검증 내역 (Verification)
- $CHECK_BOX 모든 테스트 통과
- $CHECK_BOX Lint 검사 통과
- $CHECK_BOX \`verify-task.sh\` 통과 (Harness 가드레일)

---
*이 PR은 Harness Agent OS의 [generate-pr.sh]에 의해 초안이 작성되었습니다.*
EOF

echo "✅ PR 초안이 생성되었습니다: $PR_FILE"
if command -v gh >/dev/null 2>&1; then
  echo "💡 다음 명령어로 즉시 PR을 올릴 수 있습니다:"
  echo "   gh pr create --title \"$TASK_NAME\" --body-file \"$PR_FILE\""
fi
