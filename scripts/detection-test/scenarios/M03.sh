#!/usr/bin/env bash
# id: M03
# attack: T1057
# atomic: Atomic Red Team T1057 (Process Discovery)
# expect: missed
# rule: none
# type: process
# probe: {MARK}
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 프로세스 목록 조회. 결과는 쓰지 않고 버린다. 룰이 노리지 않는다
    ps aux >/dev/null 2>&1 || true
    /usr/bin/true "edrtest-$MARK" || true
    ;;
  cleanup)
    : # 읽기만 했다
    ;;
esac
