#!/bin/bash
# ==============================================================================
# [Harness] Smoke Test for CLI Flow
# Usage: bash scripts/smoke-test.sh
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

TICKET_NAME="smoke-test-ticket"

check_l5_checkpoint() {
  echo "[Smoke Test] Checking interactive L5 checkpoint..."
  HARNESS_AUTONOMY_LEVEL=5 node tools/harness-cli/index.js autonomy
  HARNESS_AUTONOMY_LEVEL=5 node tools/harness-cli/index.js autonomy --verify-current
}

cleanup() {
  echo "[Smoke Test] Cleaning up test files..."
  rm -f ".harness/tasks/backlog/$TICKET_NAME.md"
  rm -f ".harness/tasks/active/$TICKET_NAME.md"
  rm -f ".harness/tasks/archive/$TICKET_NAME.md"
  rm -f "observability/metrics/$TICKET_NAME.verify.json"
  rm -f "observability/metrics/$TICKET_NAME.start.json"
  rm -f "observability/metrics/$TICKET_NAME.done.json"
  rm -f ".harness/valid-auto-fix.patch"
  rm -f ".harness/blocked-auto-fix.patch"
  rm -f ".harness/new-file-auto-fix.patch"
  rm -f ".harness/safe-l5.patch"
  rm -f ".harness/risky-l5.patch"
  rm -f ".harness/protected-l5.patch"
  rm -f "observability/autonomy/state.json"
}
trap cleanup EXIT

echo "[Smoke Test] Cleaning up old test files..."
cleanup

echo "[Smoke Test] 1. Running check..."
bash scripts/check-environment.sh

PRE_EXISTING_ACTIVE_COUNT="$(
  find ".harness/tasks/active" -maxdepth 1 -type f -name '*.md' \
    ! -name '.gitkeep' ! -name "$TICKET_NAME.md" | wc -l | tr -d ' '
)"
if [ "$PRE_EXISTING_ACTIVE_COUNT" -gt 1 ]; then
  echo "Error: Smoke test requires at most one pre-existing active ticket."
  exit 1
fi
if [ "$PRE_EXISTING_ACTIVE_COUNT" -eq 1 ]; then
  check_l5_checkpoint
fi

echo "[Smoke Test] 2. Creating ticket..."
bash scripts/create-ticket.sh "$TICKET_NAME" "test" \
  --goal "Verify cli quoting works" \
  --scope "Test scope with spaces" \
  --out-of-scope "Test out of scope" \
  --acceptance "Test criteria" \
  --risk "low"

if [ ! -f ".harness/tasks/backlog/$TICKET_NAME.md" ]; then
  echo "Error: Backlog ticket file was not created."
  exit 1
fi

echo "[Smoke Test] 3. Starting ticket..."
if [ "$PRE_EXISTING_ACTIVE_COUNT" -eq 1 ]; then
  if bash scripts/start-ticket.sh "$TICKET_NAME" >/dev/null 2>&1; then
    echo "Error: Parallel ticket started without explicit opt-in."
    exit 1
  fi
  START_OUTPUT="$(bash scripts/start-ticket.sh "$TICKET_NAME" --allow-parallel)"
else
  START_OUTPUT="$(bash scripts/start-ticket.sh "$TICKET_NAME")"
fi
echo "$START_OUTPUT"
if git remote get-url origin >/dev/null 2>&1; then
  EXPECTED_START_GUIDANCE="commit and push"
else
  EXPECTED_START_GUIDANCE="commit, run complete-task"
fi
if [[ "$START_OUTPUT" != *"$EXPECTED_START_GUIDANCE"* ]]; then
  echo "Error: Start guidance did not match remote availability."
  exit 1
fi

if [ ! -f ".harness/tasks/active/$TICKET_NAME.md" ]; then
  echo "Error: Active ticket file was not created."
  exit 1
fi

if [ "$PRE_EXISTING_ACTIVE_COUNT" -eq 0 ]; then
  check_l5_checkpoint
fi

echo "[Smoke Test] 4. Verifying task..."
if [ "$PRE_EXISTING_ACTIVE_COUNT" -eq 1 ]; then
  if node tools/harness-cli/index.js verify --offline >/dev/null 2>&1; then
    echo "Error: Ambiguous verification ran without an explicit task."
    exit 1
  fi
fi
export TASK_ID="$TICKET_NAME"
bash scripts/verify-task.sh --offline

if [ ! -f "observability/metrics/$TICKET_NAME.verify.json" ]; then
  echo "Error: Verify metric JSON file was not created."
  exit 1
fi

echo "[Smoke Test] 5. Completing task..."
COMPLETE_OUTPUT="$(node tools/harness-cli/index.js complete-task "$TICKET_NAME")"
echo "$COMPLETE_OUTPUT"
if git remote get-url origin >/dev/null 2>&1; then
  EXPECTED_COMPLETE_GUIDANCE="commit and push the archived"
else
  EXPECTED_COMPLETE_GUIDANCE="commit the archived"
fi
if [[ "$COMPLETE_OUTPUT" != *"$EXPECTED_COMPLETE_GUIDANCE"* ]]; then
  echo "Error: Completion guidance did not match remote availability."
  exit 1
fi

if [ ! -f ".harness/tasks/archive/$TICKET_NAME.md" ]; then
  echo "Error: Archive ticket file was not created."
  exit 1
fi
if ! grep -q "## Completion" ".harness/tasks/archive/$TICKET_NAME.md"; then
  echo "Error: Archive ticket completion metadata was not recorded."
  exit 1
fi

if [ ! -f "observability/metrics/$TICKET_NAME.done.json" ]; then
  echo "Error: Done metric JSON file was not created."
  exit 1
fi

echo "[Smoke Test] 6. Validating L4.5 auto-fix policy..."
cat > ".harness/valid-auto-fix.patch" <<'EOF'
diff --git a/packages/example/src/example.js b/packages/example/src/example.js
--- a/packages/example/src/example.js
+++ b/packages/example/src/example.js
@@ -1 +1 @@
-const value = 1;
+const value = 2;
EOF
node tools/harness-cli/index.js validate-auto-fix ".harness/valid-auto-fix.patch"

cat > ".harness/blocked-auto-fix.patch" <<'EOF'
diff --git a/package.json b/package.json
--- a/package.json
+++ b/package.json
@@ -1 +1 @@
-{}
+{"scripts":{}}
EOF
if node tools/harness-cli/index.js validate-auto-fix ".harness/blocked-auto-fix.patch"; then
  echo "Error: Protected package.json patch was not rejected."
  exit 1
fi

cat > ".harness/new-file-auto-fix.patch" <<'EOF'
diff --git a/src/new-file.js b/src/new-file.js
new file mode 100644
--- /dev/null
+++ b/src/new-file.js
@@ -0,0 +1 @@
+export const created = true;
EOF
if node tools/harness-cli/index.js validate-auto-fix ".harness/new-file-auto-fix.patch"; then
  echo "Error: New file patch was not rejected."
  exit 1
fi

echo "[Smoke Test] 7. Validating L5 policy and opt-in gate..."
node tools/harness-cli/index.js validate-prompts
node tools/harness-cli/index.js validate-api-retry
node tools/harness-cli/index.js validate-recovery
if grep -Eq 'git reset --hard|git clean -fd' tools/harness-cli/index.js; then
  echo "Error: Destructive Git recovery command was introduced."
  exit 1
fi
cat > ".harness/safe-l5.patch" <<'EOF'
diff --git a/src/new-feature.js b/src/new-feature.js
new file mode 100644
--- /dev/null
+++ b/src/new-feature.js
@@ -0,0 +1 @@
+export const enabled = true;
EOF
node tools/harness-cli/index.js validate-l5-patch ".harness/safe-l5.patch"

cat > ".harness/risky-l5.patch" <<'EOF'
diff --git a/package.json b/package.json
--- a/package.json
+++ b/package.json
@@ -1 +1 @@
-{}
+{"private":true}
EOF
node tools/harness-cli/index.js validate-l5-patch ".harness/risky-l5.patch"

cat > ".harness/protected-l5.patch" <<'EOF'
diff --git a/.env.local b/.env.local
--- a/.env.local
+++ b/.env.local
@@ -1 +1 @@
-SECRET=old
+SECRET=new
EOF
if node tools/harness-cli/index.js validate-l5-patch ".harness/protected-l5.patch"; then
  echo "Error: Protected secret patch was not rejected."
  exit 1
fi

if HARNESS_AUTONOMY_LEVEL=4.5 node tools/harness-cli/index.js autonomy; then
  echo "Error: L5 autonomy ran without explicit opt-in."
  exit 1
fi

echo "[Smoke Test] Success! Complete harness flow works seamlessly."
