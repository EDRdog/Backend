#!/usr/bin/env bash
# id: M05
# attack: T1105
# atomic: Atomic Red Team T1105 (Ingress tool transfer, no execution)
# expect: missed
# rule: none
# type: process
# probe: {MARK}
# 내려받기만 하고 실행하지 않는다. DOWNLOAD_AND_EXECUTE 는 실행 단계가 있어야 걸린다. 대상은 example.com 뿐이다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # example.com 에서 받아 임시 파일에 저장만 한다. 실행하지 않는다
    curl -s --max-time 8 -o "$WORK/$MARK.bin" https://example.com/ 2>/dev/null || true
    ;;
  cleanup)
    rm -f "$WORK/$MARK.bin"
    ;;
esac
