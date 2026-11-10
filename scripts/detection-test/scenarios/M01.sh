#!/usr/bin/env bash
# id: M01
# attack: T1033
# atomic: Atomic Red Team T1033 (System Owner/User Discovery)
# expect: missed
# rule: none
# type: process
# probe: {MARK}
# 룰이 노리지 않는 흔한 기법이다. 미탐이 정상이다(분모를 진짜 비율로 만든다).
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # whoami/id 는 읽기 전용 식별 행위다. 마커는 주석 인자로만 실어 이벤트를 특정한다
    whoami >/dev/null 2>&1 || true
    id >/dev/null 2>&1 || true
    /usr/bin/true "edrtest-$MARK" || true
    ;;
  cleanup)
    : # 읽기만 했다
    ;;
esac
