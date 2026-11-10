#!/usr/bin/env bash
# id: D10
# attack: T1567.002
# atomic: SigmaHQ DNS_MEGA_USERSTORAGE (MEGA upload DNS lookup)
# expect: detected
# rule: DNS_MEGA_USERSTORAGE
# type: dns
# probe: {MARK}
# DNS 질의만 한다. MEGA 에 파일을 올리지 않는다. 룰은 userstorage.mega.co.nz 가 질의명에 들어가면 걸린다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 마커를 앞에 붙여 질의한다. 질의명에 userstorage.mega.co.nz 가 들어가고 이벤트는 마커로 특정된다
    dig +short +time=3 "edrtest-$MARK.userstorage.mega.co.nz" >/dev/null 2>&1 || true
    ;;
  cleanup)
    : # 남기는 것이 없다
    ;;
esac
