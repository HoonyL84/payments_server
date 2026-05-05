#!/bin/bash
# ==============================================================================
# [Harness] 메모리 최적화 헬퍼 (Memory Consolidation)
# Usage: bash scripts/consolidate-memory.sh [--days <days>]
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR" || exit 1

DAYS=7

while [[ $# -gt 0 ]]; do
  case "$1" in
    --days)
      if [[ ! "$2" =~ ^[0-9]+$ ]]; then
        echo "❌ 오류: --days 옵션은 숫자여야 합니다."
        exit 1
      fi
      DAYS="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

echo "🧠 [Harness] 메모리 통합 스크립트 실행"
echo "  - 타겟: $DAYS 일 이상 수정되지 않은 working/episodic 메모리"

TARGET_FILES=()

# find 수정일 기준 검색
while IFS= read -r file; do
  TARGET_FILES+=("$file")
done < <(find memory/working memory/episodic -maxdepth 1 -type f -name "*.md" -mtime +$DAYS 2>/dev/null)

if [ ${#TARGET_FILES[@]} -eq 0 ]; then
  echo "✅ 대상 메모리가 없습니다. ($DAYS 일 경과 파일 없음)"
  exit 0
fi

echo "⚠️ 다음 파일들이 메모리 이관 대상입니다:"
for file in "${TARGET_FILES[@]}"; do
  echo "  - $file"
done

echo ""
echo "=== 🤖 에이전트 작업 가이드 ==="
echo "에이전트에게 다음 프롬프트를 전달하여 통합 작업을 요청하세요:"
echo "--------------------------------------------------------"
echo "다음 단기 메모리 파일들을 분석하여, 계속 유지해야 할 중요한 원칙, 지식, 문제 해결 패턴을 추출해 줘."
echo "- 대상 파일:"
for file in "${TARGET_FILES[@]}"; do
  echo "  * $file"
done
echo ""
echo "- 요구사항:"
echo "  1. 불필요한 단기 정보는 버리고 핵심만 요약할 것."
echo "  2. 결과물은 memory/semantic/ 또는 memory/procedural/ 폴더에 새 마크다운 파일(MEMORY_TEMPLATE.md 준수)로 작성할 것."
echo "  3. 통합이 완료되면 원본 대상 파일들은 삭제(또는 아카이브)할 것."
echo "--------------------------------------------------------"
