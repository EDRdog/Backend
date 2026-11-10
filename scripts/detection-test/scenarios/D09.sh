#!/usr/bin/env bash
# id: D09
# attack: T1090.003
# atomic: SigmaHQ DNS_TOR_GATEWAY (tor2web DNS lookup)
# expect: detected
# rule: DNS_TOR_GATEWAY
# type: dns
# probe: {MARK}
# DNS 질의만 한다. tor2web 호스트에 접속하지 않는다. .tor2web.org 은 룰의 endswith 목록에 있다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 설정된 리졸버에 tor2web 도메인을 묻는다. 없는 이름이라 NXDOMAIN 이 와도 응답 이벤트가 난다
    dig +short +time=3 "edrtest-$MARK.tor2web.org" >/dev/null 2>&1 || true
    ;;
  cleanup)
    : # 남기는 것이 없다
    ;;
esac
