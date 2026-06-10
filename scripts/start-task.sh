#!/bin/bash
# ==============================================================================
# [Harness] 1단계: 태스크 시작 — 워크트리 + EXEC_PLAN 생성
# Usage: bash scripts/start-task.sh <task-name> <feat|fix|refactor|docs|chore|test|experiment> [--issue <number>]
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR" || exit 1

if [ $# -lt 2 ]; then
  echo "❌ 사용법: bash scripts/start-task.sh <task-name> <feat|fix|refactor|docs|chore|test|experiment> [--issue <number>]"
  exit 1
fi

TASK_NAME=$1
TASK_TYPE=$2

if [[ ! "$TASK_TYPE" =~ ^(feat|fix|refactor|docs|chore|test|experiment)$ ]]; then
  echo "❌ 오류: task type은 feat, fix, refactor, docs, chore, test, experiment 중 하나여야 합니다."
  exit 1
fi

ISSUE_NUM=""

shift 2
while [[ $# -gt 0 ]]; do
  case "$1" in
    --issue)
      if [ -z "$2" ] || [[ "$2" == --* ]]; then
        echo "❌ 오류: --issue 옵션은 이슈 번호가 필요합니다."
        exit 1
      fi
      ISSUE_NUM="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

BRANCH_NAME="${TASK_TYPE}/${TASK_NAME}"
WORKTREE_DIR=".worktrees/${TASK_NAME}"
PLAN_FILE=".harness/tasks/active/${TASK_NAME}.md"

echo "🚀 [Harness] Task 시작: $TASK_NAME"

# 워크트리 생성
mkdir -p .worktrees

DEFAULT_BRANCH=$(git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null | sed 's@^refs/remotes/origin/@@')
if [ -z "$DEFAULT_BRANCH" ]; then
  if git show-ref --verify --quiet refs/heads/main; then
    DEFAULT_BRANCH="main"
  else
    DEFAULT_BRANCH="master"
  fi
fi

git worktree add -b "$BRANCH_NAME" "$WORKTREE_DIR" "$DEFAULT_BRANCH"
if [ $? -ne 0 ]; then
  echo "❌ 워크트리 생성 실패."
  exit 1
fi

# EXEC_PLAN 생성
mkdir -p .harness/tasks/active

ISSUE_TITLE="[여기에 목표 작성]"
ISSUE_BODY=""
if [ -n "$ISSUE_NUM" ]; then
  if command -v gh >/dev/null 2>&1; then
    echo "🔍 GitHub Issue #${ISSUE_NUM} 정보 조회 중..."
    ISSUE_TITLE=$(gh issue view "$ISSUE_NUM" --json title -q ".title" 2>/dev/null || echo "[이슈 제목을 가져오지 못했습니다]")
    ISSUE_BODY=$(gh issue view "$ISSUE_NUM" --json body -q ".body" 2>/dev/null || echo "")
  else
    echo "⚠️ gh CLI가 설치되어 있지 않아 이슈를 가져올 수 없습니다."
  fi
fi

cat <<EOF > "$PLAN_FILE"
# EXEC_PLAN: $TASK_NAME ($TASK_TYPE)

## 🎯 목표 (Goal)
- $ISSUE_TITLE
${ISSUE_BODY:+- $ISSUE_BODY}

## 🛠️ 접근법 (Approach)
- [구현 방법 작성]

## ✅ 단계별 계획
- [ ] Step 1:
- [ ] Step 2:

## 💡 가정 (Assumptions)
- (모호한 요구사항에 대해 내린 가정 명시)

## 🏁 완료 기준
- [ ] 모든 테스트 통과
- [ ] Lint 에러 없음
- [ ] verify-task.sh 통과
EOF

echo "✅ 워크트리: $WORKTREE_DIR"
echo "✅ EXEC_PLAN: $PLAN_FILE"
echo "👉 작업 시작: cd $WORKTREE_DIR"

# ── 인사이트 로그: 시작 시각 기록 ───────────────────────────────────────────
mkdir -p observability/metrics
START_LOG="observability/metrics/${TASK_NAME}.start.json"
cat > "$START_LOG" <<EOF
{
  "task": "$TASK_NAME",
  "type": "$TASK_TYPE",
  "started_at": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "project": "$(basename $(git rev-parse --show-toplevel 2>/dev/null || echo 'unknown'))"
}
EOF
echo "📊 인사이트 로그 기록: $START_LOG"
