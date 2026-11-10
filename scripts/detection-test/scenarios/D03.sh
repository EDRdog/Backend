#!/usr/bin/env bash
# id: D03
# attack: T1547.011
# atomic: SigmaHQ FILE_IN_AUTORUN_PATH (LaunchAgents drop)
# expect: detected
# rule: FILE_IN_AUTORUN_PATH
# type: file
# probe: {MARK}
# 감시 경로에 파일을 하나 만든다. 확장자가 .plist 가 아니라 launchd 가 읽지 않고, 적재(load)도 하지 않는다.
set -euo pipefail
LA="$HOME/Library/LaunchAgents"
case "${1:?run|cleanup}" in
  run)
    # 자동실행 경로에 양성 파일을 만든다(create 이벤트만 노린다). 비어 있고 로드하지 않는다
    mkdir -p "$LA"
    : > "$LA/edrtest-$MARK.not-a-plist"
    ;;
  cleanup)
    rm -f "$LA/edrtest-$MARK.not-a-plist"
    ;;
esac
