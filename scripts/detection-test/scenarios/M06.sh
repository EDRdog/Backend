#!/usr/bin/env bash
# id: M06
# attack: T1518.001
# atomic: Atomic Red Team T1518.001 (Security software discovery, read-only)
# expect: missed
# rule: none
# type: process
# probe: {MARK}
# spctl --status 는 읽기 전용이다. MACOS_DISABLE_SECURITY_TOOLS 는 disable 류 명령만 노린다. 미탐이 정상이다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # Gatekeeper 상태만 읽는다. 끄지 않는다
    spctl --status >/dev/null 2>&1 || true
    /usr/bin/true "edrtest-$MARK" || true
    ;;
  cleanup)
    : # 읽기만 했다
    ;;
esac
