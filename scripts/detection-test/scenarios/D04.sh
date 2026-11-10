#!/usr/bin/env bash
# id: D04
# attack: T1573
# atomic: SigmaHQ WEAK_TLS_HANDSHAKE (legacy TLS ClientHello)
# expect: detected
# rule: WEAK_TLS_HANDSHAKE
# type: l7
# probe: {MARK}
# example.com 에 TLS 1.1 ClientHello 만 보낸다. 서버가 거절해도 ClientHello 는 이미 캡처된다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 구형 TLS 로 핸드셰이크를 시도한다. SNI 에 마커를 실어 l7 이벤트를 특정한다
    openssl s_client -tls1_1 -connect example.com:443 -servername "${MARK}.example.com" </dev/null >/dev/null 2>&1 || true
    ;;
  cleanup)
    : # 남기는 것이 없다
    ;;
esac
