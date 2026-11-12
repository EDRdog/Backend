#!/usr/bin/env bash
# id: A06
# attack: T1005
# atomic: Atomic Red Team T1005 #3 (Copy Apple Notes database files using AppleScript)
# expect: detected
# rule: MACOS_INFOSTEALER_LOCAL_DATA
# type: process
# probe: {MARK}
# 메모 DB 를 Finder 가 임시 폴더로 복사한다(읽기만 한다). 권한이 없으면 복사는 실패하지만 명령행은 남는다.
# ART 정리 명령은 따옴표 안의 * 라 파일이 안 지워진다. 여기서는 글롭이 펼쳐지게 따옴표 밖에 뒀다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    osascript -e 'tell application "Finder"' -e 'set destinationFolderPath to POSIX file "'"$WORK"'"' -e 'set notesFolderPath to (path to home folder as text) & "Library:Group Containers:group.com.apple.notes:"' -e 'set notesFolder to folder notesFolderPath' -e 'set notesFiles to {file "NoteStore.sqlite", file "NoteStore.sqlite-shm", file "NoteStore.sqlite-wal"} of notesFolder' -e 'repeat with aFile in notesFiles' -e 'duplicate aFile to folder destinationFolderPath with replacing' -e 'end' -e 'end tell' >/dev/null 2>&1 || true
    ;;
  cleanup)
    rm -f "$WORK"/NoteStore.sqlite*
    ;;
esac
