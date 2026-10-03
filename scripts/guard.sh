#!/usr/bin/env bash
# PreToolUse(Bash) 훅: 명령 어디에 있든 위험 패턴이 있으면 exit 2로 차단한다.
# settings.json의 deny는 명령 앞부분만 보므로 `cd x && rm -rf y` 같은 체인은 여기서 막는다.
cmd=$(jq -r '.tool_input.command // empty')

# 차단 패턴 (확장 정규식). 프로젝트에 맞게 추가한다.
patterns=(
  'rm -rf'
  'git push.*[[:space:]](-f|--force)([[:space:]]|$)'
  'git reset --hard'
  'DROP (TABLE|DATABASE)'
  'TRUNCATE TABLE'
  # 부하 테스트는 돌리는 순간 지표 카운터가 리셋돼 앞선 측정과 섞인다. 사용자 승인 뒤에 사람이 돌린다.
  'k6 run'
  # 배포서버 리소스 삭제. apply 로 못 고치는 걸 지울 때가 있는데, 무엇이 지워지는지 먼저 사람이 본다.
  'kubectl[^|]*[[:space:]]delete[[:space:]]'
)

# 패턴이 하나라도 맞으면 이유를 stderr로 알리고 차단
for p in "${patterns[@]}"; do
  if grep -qiE "$p" <<<"$cmd"; then
    echo "guard.sh 차단: '$p' 패턴. 꼭 필요하면 사용자에게 직접 실행을 요청할 것." >&2
    exit 2
  fi
done
exit 0
