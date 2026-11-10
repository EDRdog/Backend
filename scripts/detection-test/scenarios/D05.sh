#!/usr/bin/env bash
# id: D05
# attack: T1555.001
# atomic: Atomic Red Team T1555.001 (Keychain access via security)
# expect: detected
# rule: MACOS_KEYCHAIN_DUMP
# type: process
# probe: {MARK}
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # security find-certificate 는 읽기 전용이다. -c 뒤 이름이 없어 아무 인증서도 내보내지 않는다
    security find-certificate -a -p -c "edrtest-$MARK" >/dev/null 2>&1 || true
    ;;
  cleanup)
    : # 읽기만 했다
    ;;
esac
