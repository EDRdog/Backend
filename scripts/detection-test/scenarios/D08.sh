#!/usr/bin/env bash
# id: D08
# attack: T1490
# atomic: SigmaHQ MACOS_TMUTIL_DELETE_BACKUP (Time Machine tamper)
# expect: detected
# rule: MACOS_TMUTIL_DELETE_BACKUP
# type: process
# probe: {MARK}
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 존재하지 않는 경로를 지우라고 한다. 실제 백업은 없고 exec 이벤트만 남긴다
    tmutil delete "/tmp/edrtest-$MARK-nonexistent" >/dev/null 2>&1 || true
    ;;
  cleanup)
    : # 남기는 것이 없다
    ;;
esac
