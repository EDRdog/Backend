#!/usr/bin/env bash
# id: A02
# attack: T1555.001
# atomic: Atomic Red Team T1555.001 #2 (Export Certificate Item(s))
# expect: detected
# rule: MACOS_KEYCHAIN_DUMP
# type: process
# probe: find-certificate
# 키체인의 인증서(공개 인증서)를 PEM 으로 내보낸다. 사본은 임시 폴더에만 둔다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    security find-certificate -a -p > "$WORK/certs.pem" 2>/dev/null || true
    ;;
  cleanup)
    rm -f "$WORK/certs.pem"
    ;;
esac
