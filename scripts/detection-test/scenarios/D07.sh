#!/usr/bin/env bash
# id: D07
# attack: T1543.001
# atomic: Atomic Red Team T1543.001 (LaunchAgent via PlistBuddy)
# expect: detected
# rule: MACOS_PLISTBUDDY_LAUNCHD_PERSISTENCE
# type: process
# probe: {MARK}
# /tmp 안의 파일에만 쓴다. 실제 LaunchAgents 경로에 설치하지 않으므로 지속성이 남지 않는다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 명령행에 RunAtLoad/true/LaunchAgents 가 들어가면 룰이 걸린다. 파일은 임시 경로에 둔다
    /usr/libexec/PlistBuddy -c "Add :RunAtLoad bool true" "$WORK/$MARK-LaunchAgents.plist" >/dev/null 2>&1 || true
    ;;
  cleanup)
    rm -f "$WORK/$MARK-LaunchAgents.plist"
    ;;
esac
