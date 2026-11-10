#!/usr/bin/env bash
# id: D02
# attack: T1059.004
# atomic: Atomic Red Team T1059.004 (Unix shell script execution)
# expect: detected
# rule: SCRIPT_FROM_TEMP_PATH
# type: script
# probe: {MARK}
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 임시 경로에 양성 스크립트를 두고 실행한다. 스크립트는 1초 자고 끝난다
    printf 'sleep 1\n' > "$WORK/$MARK.sh"
    bash "$WORK/$MARK.sh"
    ;;
  cleanup)
    rm -f "$WORK/$MARK.sh"
    ;;
esac
