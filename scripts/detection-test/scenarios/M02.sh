#!/usr/bin/env bash
# id: M02
# attack: T1082
# atomic: Atomic Red Team T1082 (System Information Discovery)
# expect: missed
# rule: none
# type: process
# probe: {MARK}
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 시스템 정보 조회. 읽기 전용이고 룰이 노리지 않는다
    sw_vers >/dev/null 2>&1 || true
    uname -a >/dev/null 2>&1 || true
    /usr/bin/true "edrtest-$MARK" || true
    ;;
  cleanup)
    : # 읽기만 했다
    ;;
esac
