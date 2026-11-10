#!/usr/bin/env bash
# lint + test 를 한 번에 돌린다. 에이전트와 CI 가 같은 명령을 쓴다.
set -euo pipefail
cd "$(dirname "$0")/.."

# lint — 자바 쪽은 린터를 두지 않았다(spotless·checkstyle 설정이 없다). 에이전트(Go)만 vet 을 돈다.
(cd agent && go vet ./...)

# 에이전트는 윈도우도 내보낸다. 맥에서만 보면 윈도우 전용 파일이 깨진 걸 머지 전까지 모른다(ci.yml 과 같은 검사).
(cd agent && GOOS=windows GOARCH=amd64 go vet ./...)

# test
./gradlew test
(cd agent && go test ./...)

# detector 룰 ConfigMap 이 룰 파일과 같은지. 어긋나면 운영은 옛 룰을 쓴다.
scripts/gen-rules-configmap.sh | diff -q - k8s/detector-rules.yaml >/dev/null \
  || { echo "k8s/detector-rules.yaml 이 룰 파일과 다르다: scripts/gen-rules-configmap.sh > k8s/detector-rules.yaml" >&2; exit 1; }

# 문서 (ERD 상태 칸, 테스트 참조)
scripts/check-docs.sh

# 손으로 까는 Windows 설치 스크립트는 UTF-8 BOM 이 있어야 한다. 없으면 한국어 Windows 의 PowerShell 5.1 이 cp949 로 읽어 한글 문자열이 깨지고 구문 오류로 멈춘다.
# 서버가 내려주는 api-service/.../install/windows.ps1 은 irm | iex 로 charset=utf-8 응답을 읽으므로 BOM 을 붙이지 않는다.
for f in agent/packaging/*.ps1; do
  [ "$(head -c3 "$f" | od -An -tx1 | tr -d ' ')" = efbbbf ] || { echo "$f 에 UTF-8 BOM 이 없다" >&2; exit 1; }
done
