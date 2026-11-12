#!/usr/bin/env bash
# id: A05
# attack: T1539
# atomic: Atomic Red Team T1539 #5 (Copy Safari BinaryCookies files using AppleScript)
# expect: detected
# rule: MACOS_INFOSTEALER_COOKIE_THEFT
# type: process
# probe: {MARK}
# Safari 쿠키 파일을 Finder 가 임시 폴더로 복사한다(읽기만 한다). 터미널에 Finder 제어, 전체 디스크 접근 권한이 없으면 복사는 실패하지만 명령행은 남는다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 목적지는 ART 기본값 /private/tmp 대신 이 시험의 임시 폴더다
    osascript -e 'tell application "Finder"' -e 'set destinationFolderPath to POSIX file "'"$WORK"'"' -e 'set safariFolder to ((path to library folder from user domain as text) & "Containers:com.apple.Safari:Data:Library:Cookies:")' -e 'duplicate file "Cookies.binarycookies" of folder safariFolder to folder destinationFolderPath with replacing' -e 'end tell' >/dev/null 2>&1 || true
    ;;
  cleanup)
    rm -f "$WORK/Cookies.binarycookies"
    ;;
esac
