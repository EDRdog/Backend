#!/usr/bin/env bash
# docs/requirements(ERD) 점검: 기준마다 상태 칸이 정해진 값인지, 검증 칸의 테스트 참조가 실제로 있는지 본다.
# 검증 칸 참조 형식: `테스트클래스.메서드` (Java/Kotlin/TS 등) 또는 `경로::테스트이름` (pytest)
set -uo pipefail
cd "$(dirname "$0")/.."

dir=docs/requirements
[ -d "$dir" ] || exit 0
test_dirs=(*-service/src/test event-schema/src/test agent)
statuses='자동|자동 \(환경변수 있을 때\)|수동|미구현|일부 미구현|미충족|미정|해당 없음'
fail=0

while IFS= read -r line; do
  file=${line%%:*}
  row=${line#*:}
  id=$(awk -F'|' '{gsub(/ /, "", $2); print $2}' <<<"$row")
  verify=$(awk -F'|' '{print $4}' <<<"$row")
  status=$(awk -F'|' '{s = $(NF - 1); gsub(/^ +| +$/, "", s); print s}' <<<"$row")

  # 상태 칸
  if ! grep -qxE "$statuses" <<<"$status"; then
    echo "$file $id: 상태 칸이 비었거나 모르는 값 ($status)" >&2
    fail=1
  fi

  # `클래스.메서드`: 테스트 폴더에 그 이름의 파일이 있고 메서드 이름이 들어 있어야 한다
  # README.md 같은 파일 이름은 테스트 참조가 아니다
  for ref in $(grep -oE '`[A-Z][A-Za-z0-9_]*\.[a-z_][A-Za-z0-9_]*`' <<<"$verify" | tr -d '`' \
      | grep -vE '\.(md|json|ya?ml|txt|java|kt|py|ts|tsx|js|go|rs|sh|xml|csv|pdf|png|hwp|hwpx)$'); do
    cls=${ref%%.*}
    method=${ref#*.}
    src=$(find "${test_dirs[@]}" -name "$cls.*" -type f 2>/dev/null | head -1)
    if [ -z "$src" ] || ! grep -qw "$method" "$src"; then
      echo "$file $id: 없는 테스트 $ref" >&2
      fail=1
    fi
  done

  # `경로::테스트이름`
  for ref in $(grep -oE '`[A-Za-z0-9_./-]+::[A-Za-z0-9_]+`' <<<"$verify" | tr -d '`'); do
    path=${ref%%::*}
    name=${ref#*::}
    if [ ! -f "$path" ] || ! grep -qw "$name" "$path"; then
      echo "$file $id: 없는 테스트 $ref" >&2
      fail=1
    fi
  done
done < <(grep -HE '^\| [A-Z]{2,4}-M?[0-9]+ \|' "$dir"/*.md)

[ "$fail" -eq 0 ] && echo "docs/requirements 점검 통과"
exit "$fail"
