#!/usr/bin/env bash
# id: A07
# attack: T1555.003
# atomic: Atomic Red Team T1555.003 #2 (Search macOS Safari Cookies)
# expect: detected
# rule: MACOS_INFOSTEALER_COOKIE_THEFT
# type: process
# probe: Cookies.binarycookies
# Safari 쿠키 파일에서 문자열을 찾는다(-q 라 출력 없음, 읽기만 한다). 파일이 없거나 권한이 없어도 명령행은 남는다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    ( cd ~/Library/Cookies 2>/dev/null; grep -q "coinbase" "Cookies.binarycookies" 2>/dev/null ) || true
    ;;
  cleanup)
    : # 남기는 것이 없다
    ;;
esac
