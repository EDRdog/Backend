#!/usr/bin/env bash
# id: A08
# attack: T1555.003
# atomic: Atomic Red Team T1555.003 #14 (Simulating Access to Chrome Login Data - MacOS)
# expect: detected
# rule: MACOS_INFOSTEALER_BROWSER_CREDENTIALS
# type: process
# probe: {MARK}
# Chrome 의 Login Data(암호화된 DB)를 임시 폴더로 복사한다. 사본은 소유자만 읽게 만들고 cleanup 에서 지운다. 원본은 읽기만 한다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 사본 권한을 600 으로 만든다. 목적지는 ART 기본값 /tmp 대신 이 시험의 임시 폴더다
    umask 077
    cp ~/Library/"Application Support/Google/Chrome/Default/Login Data" "$WORK/T1555.003_Login Data" 2>/dev/null || true
    cp ~/Library/"Application Support/Google/Chrome/Default/Login Data For Account" "$WORK/T1555.003_Login Data For Account" 2>/dev/null || true
    ;;
  cleanup)
    rm -f "$WORK/T1555.003_Login Data" "$WORK/T1555.003_Login Data For Account"
    ;;
esac
