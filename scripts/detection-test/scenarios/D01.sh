#!/usr/bin/env bash
# id: D01
# attack: T1105+T1204
# atomic: Atomic Red Team T1105 (Download a file / execute from temp)
# expect: detected
# rule: DOWNLOAD_AND_EXECUTE
# type: process
# probe: {MARK}
# 외부로 나가는 유일한 곳은 example.com(IANA 문서용 도메인)뿐이다. 실제 파일을 내려받지 않는다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 네트워크 근거: example.com:443 에 TCP 를 열어 두어 netsnap 폴링(1초)이 한 번은 보게 한다
    ( exec 3<>/dev/tcp/example.com/443 2>/dev/null; sleep 3; exec 3>&- ) &
    sleep 4
    # 트리거: 임시 경로에 양성 바이너리를 복사해 실행한다(kill 대상이 되도록 오래 산다)
    cp /bin/sleep "$WORK/$MARK"
    # 시스템 바이너리 복사본은 서명 검사로 macOS 가 바로 SIGKILL 한다. 임시 서명을 다시 해야 살아 있다
    codesign -f -s - "$WORK/$MARK" 2>/dev/null
    "$WORK/$MARK" 300 &
    echo $! > "$WORK/pid"
    ;;
  cleanup)
    [ -f "$WORK/pid" ] && kill "$(cat "$WORK/pid")" 2>/dev/null || true
    rm -f "$WORK/$MARK" "$WORK/pid"
    ;;
esac
