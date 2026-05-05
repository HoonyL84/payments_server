#!/bin/bash
# ==============================================================================
# [Harness] 5단계: 태스크 완료 및 정리 (Garbage Collection)
# Usage: bash scripts/complete-task.sh <task-name> [--force]
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR" || exit 1

TASK_NAME=$1
FORCE=0

if [ "$2" == "--force" ]; then
  FORCE=1
fi

if [ -z "$TASK_NAME" ]; then
  echo "❌ 사용법: bash scripts/complete-task.sh <task-name> [--force]"
  exit 1
fi

WORKTREE_DIR=".worktrees/${TASK_NAME}"
PLAN_FILE=".harness/tasks/active/${TASK_NAME}.md"
ARCHIVE_DIR=".harness/tasks/archive"

source "$SCRIPT_DIR/utils.sh"

echo "🧹 [Harness] Task 완료 처리: $TASK_NAME"

# VERIFY_LOG 경로 탐색 (루트 우선, 없으면 워크트리 내부)
VERIFY_LOG="observability/metrics/${TASK_NAME}.verify.json"
if [ ! -f "$VERIFY_LOG" ]; then
  VERIFY_LOG="${WORKTREE_DIR}/observability/metrics/${TASK_NAME}.verify.json"
fi

REWORK_COUNT=0
LAST_FAIL_REASON="none"
VERIFY_RESULT="none"

# 워크트리를 삭제하기 전에 먼저 verify.json 내용을 파싱합니다.
if [ -f "$VERIFY_LOG" ]; then
  REWORK_COUNT=$(grep -o '"rework_count":[0-9]*' "$VERIFY_LOG" | grep -o '[0-9]*' || echo 0)
  LAST_FAIL_REASON=$(grep -o '"last_fail_reason":"[^"]*"' "$VERIFY_LOG" | cut -d'"' -f4 || echo "none")
  if grep -E -q '"result": ?"pass"' "$VERIFY_LOG"; then
    VERIFY_RESULT="pass"
  fi
fi

if [ $FORCE -eq 0 ]; then
  if [ "$VERIFY_RESULT" != "pass" ]; then
    echo "❌ 오류: 태스크 검증(verify-task.sh)이 완료되지 않았습니다."
    echo "커밋되지 않은 작업 내역을 실수로 잃을 위험이 있습니다."
    echo "그래도 강제로 삭제(완료)하려면 --force 옵션을 사용하세요."
    echo "예: bash scripts/complete-task.sh $TASK_NAME --force"
    exit 1
  fi
fi

send_slack_notification "success" "🎊 Task [$TASK_NAME] 완료. GC 시작..."

# 워크트리 삭제
if [ -d "$WORKTREE_DIR" ]; then
  git worktree remove -f "$WORKTREE_DIR"
  git branch -D "feat/$TASK_NAME" 2>/dev/null || \
  git branch -D "fix/$TASK_NAME" 2>/dev/null || \
  git branch -D "refactor/$TASK_NAME" 2>/dev/null || \
  git branch -D "docs/$TASK_NAME" 2>/dev/null || \
  git branch -D "chore/$TASK_NAME" 2>/dev/null || \
  git branch -D "experiment/$TASK_NAME" 2>/dev/null
  echo "✅ 워크트리 삭제 완료"
else
  echo "⚠️ 워크트리 없음: $WORKTREE_DIR"
fi

# EXEC_PLAN 아카이브
if [ -f "$PLAN_FILE" ]; then
  mkdir -p "$ARCHIVE_DIR"
  mv "$PLAN_FILE" "$ARCHIVE_DIR/${TASK_NAME}.md"
  echo "✅ EXEC_PLAN 아카이브 완료"
fi

echo "✅ Task [$TASK_NAME] 정리 완료."

# PR 초안 생성 안내
echo "💡 PR 마크다운 초안을 생성하려면 다음을 실행하세요:"
echo "   bash scripts/generate-pr.sh $TASK_NAME"
echo ""

# ── 인사이트 로그: 최종 기록 생성 ───────────────────────────────────────────
START_LOG="observability/metrics/${TASK_NAME}.start.json"
INSIGHT_LOG="observability/metrics/${TASK_NAME}.done.json"

STARTED_AT="unknown"
TASK_TYPE="unknown"
PROJECT="unknown"

if [ -f "$START_LOG" ]; then
  STARTED_AT=$(grep -o '"started_at":"[^"]*"' "$START_LOG" | cut -d'"' -f4)
  TASK_TYPE=$(grep -o '"type":"[^"]*"' "$START_LOG" | cut -d'"' -f4)
  PROJECT=$(grep -o '"project":"[^"]*"' "$START_LOG" | cut -d'"' -f4)
fi

COMPLETED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

cat > "$INSIGHT_LOG" <<EOF
{
  "task": "$TASK_NAME",
  "type": "$TASK_TYPE",
  "project": "$PROJECT",
  "started_at": "$STARTED_AT",
  "completed_at": "$COMPLETED_AT",
  "rework_count": $REWORK_COUNT,
  "last_fail_reason": "$LAST_FAIL_REASON",
  "verify_result": "$VERIFY_RESULT"
}
EOF

# 임시 로그 정리
rm -f "$START_LOG" "observability/metrics/${TASK_NAME}.verify.json"
echo "📊 인사이트 기록 완료: $INSIGHT_LOG"
