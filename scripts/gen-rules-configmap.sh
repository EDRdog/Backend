#!/usr/bin/env bash
# detector 룰 파일(detector-service/src/main/resources/rules/*.yml)로 ConfigMap 매니페스트를 만들어 표준출력에 낸다.
# 룰을 고친 뒤 `scripts/gen-rules-configmap.sh > k8s/detector-rules.yaml` 로 갱신한다. check.sh 가 어긋남을 잡는다.
set -euo pipefail
cd "$(dirname "$0")/.."

cat <<'HEADER'
# 이 파일은 scripts/gen-rules-configmap.sh 가 만든다. 직접 고치지 않는다.
# 룰은 detector-service/src/main/resources/rules/ 에서 고치고 스크립트를 다시 돌린다.
# detector 가 /etc/edrdog/rules 로 마운트해 30초마다 다시 읽는다(k8s/detector-service.yaml). 재배포 없이 반영된다.
apiVersion: v1
kind: ConfigMap
metadata:
  name: detector-rules
  namespace: edrdog
  labels:
    app: detector-service
data:
HEADER

for f in detector-service/src/main/resources/rules/*.yml; do
  echo "  $(basename "$f"): |"
  # 빈 줄은 들여쓰지 않는다. 공백만 있는 줄이 diff 에 남는다.
  sed 's/^\(.\)/    \1/' "$f"
done
