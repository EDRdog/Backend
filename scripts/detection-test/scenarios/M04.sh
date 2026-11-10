#!/usr/bin/env bash
# id: M04
# attack: T1059.002
# atomic: Atomic Red Team T1059.002 (AppleScript via osascript)
# expect: missed
# rule: none
# type: script
# probe: {MARK}
# 스크립트 실행이지만 임시 경로가 아니라 SCRIPT_FROM_TEMP_PATH 가 노리지 않는다. 미탐이 정상이다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 인라인 osascript 는 양성 값을 돌려줄 뿐이다. 임시 경로 인자가 없다
    osascript -e "return \"edrtest-$MARK\"" >/dev/null 2>&1 || true
    ;;
  cleanup)
    : # 남기는 것이 없다
    ;;
esac
