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

# 문서 (ERD 상태 칸, 테스트 참조)
scripts/check-docs.sh
