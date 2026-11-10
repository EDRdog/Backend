#!/usr/bin/env bash
# id: D06
# attack: T1553.001
# atomic: Atomic Red Team T1553.001 (Remove com.apple.quarantine)
# expect: detected
# rule: MACOS_XATTR_REMOVE_QUARANTINE
# type: process
# probe: {MARK}
# 우리가 만든 임시 파일에만 손댄다. 사용자 파일은 건드리지 않는다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 임시 파일에 quarantine 속성을 붙였다가 지운다. 이 '지우기'가 룰에 걸린다
    : > "$WORK/$MARK"
    xattr -w com.apple.quarantine "0000;00000000;edrtest;" "$WORK/$MARK" 2>/dev/null || true
    xattr -d com.apple.quarantine "$WORK/$MARK" 2>/dev/null || true
    ;;
  cleanup)
    rm -f "$WORK/$MARK"
    ;;
esac
