#!/usr/bin/env bash
# KISA EDR 자체 탐지 시험 러너. 계획과 판정 기준은 docs/exec-plans/active/kisa-edr-detection-test.md.
# 기본은 dry-run(출력만)이다. 시나리오를 실제로 돌리려면 --run 을 줘야 한다.
#
#   scripts/detection-test/run.sh                       # dry-run: 무엇을 돌릴지 출력만
#   scripts/detection-test/run.sh --run                 # 맥에서 실제 실행 (환경변수는 계획 문서 참고)
#   scripts/detection-test/run.sh --run --respond       # 알림의 kill 권고를 대시보드 버튼처럼 실행까지
#   scripts/detection-test/run.sh --only D02,M01        # 고른 시나리오만
set -euo pipefail
DIR=$(cd "$(dirname "$0")" && pwd)

# 옵션
MODE=dry RESPOND=0 ONLY=""
GAP=${GAP:-330} WAIT=${WAIT:-120} POLL=${POLL:-3}
API=${API_BASE:-http://localhost:8084}   # 로컬 api-service (bootRun) 또는 kubectl port-forward svc/api-service 8084:80
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) MODE=dry ;;
    --run) MODE=run ;;
    --respond) RESPOND=1 ;;
    --only) ONLY=${2:?--only 뒤에 시나리오 ID}; shift ;;
    *) echo "사용법: $0 [--dry-run | --run] [--respond] [--only D01,M01]" >&2; exit 2 ;;
  esac
  shift
done

die() { echo "중단: $*" >&2; exit 1; }
# 시나리오 파일 머리의 `# key: value` 한 칸
# 외부 명령(sed 등)을 쓰지 않는다. 명령행에 시나리오 경로가 실리면 그것만으로 룰이 걸린다(D08 tmutil 오탐, 2026-11-10)
field() { local line; while IFS= read -r line; do [[ $line == "# $2: "* ]] && { printf '%s\n' "${line#"# $2: "}"; return; }; done < "$1"; }
# 고른 시나리오 파일 목록 (파일 이름순)
list() {
  local f id
  for f in "$DIR"/scenarios/*.sh; do
    id=$(field "$f" id)
    if [ -z "$ONLY" ] || [[ ",$ONLY," == *",$id,"* ]]; then echo "$f"; fi
  done
}

# dry-run: 시나리오마다 헤더와 실제로 돌 run 블록을 보여 주고 끝낸다
if [ "$MODE" = dry ]; then
  echo "dry-run 이다. 아무것도 실행하지 않는다. 실제 실행은 --run"
  echo "간격 ${GAP}s, 판정 대기 ${WAIT}s, 조회 주기 ${POLL}s, api ${API}, 응답 시험 $([ "$RESPOND" = 1 ] && echo 켬 || echo 끔)"
  for f in $(list); do
    echo
    echo "== $(field "$f" id)  $(field "$f" attack)  기대=$(field "$f" expect)  룰=$(field "$f" rule)"
    echo "   atomic: $(field "$f" atomic)"
    echo "   수집 확인: type=$(field "$f" type) 에서 '$(field "$f" probe)' 포함"
    sed -n '/^  run)/,/^    ;;/p' "$f"
  done
  exit 0
fi

# 실행 전 점검: 맥, 자격 증명(환경변수로만), 재시작 후 10분, 에이전트 기동
[ "$(uname)" = Darwin ] || die "맥에서만 돈다"
[ "$(id -u)" -ne 0 ] || die "root 로 돌리지 않는다. 시나리오는 일반 사용자 권한만 쓴다"
: "${EDRDOG_TOKEN:?로그인 토큰. 계획 문서의 로그인 한 줄로 받는다}"
: "${EDRDOG_HOST:?에이전트의 host_identifier (비워 뒀으면 hostname -s)}"
: "${LAST_RESTART_AT:?detector 마지막 기동 시각 (UTC, 예 2026-11-10T03:00:00Z). bootRun 이면 띄운 시각, 파드면 kubectl get pods 의 startTime}"
command -v jq >/dev/null || die "jq 가 없다 (macOS 15 이상은 /usr/bin/jq 기본 탑재)"
restart=$(date -j -u -f '%Y-%m-%dT%H:%M:%SZ' "$LAST_RESTART_AT" +%s) || die "LAST_RESTART_AT 형식이 틀렸다"
since=$(( $(date +%s) - restart ))
[ "$since" -ge 600 ] || die "마지막 재시작 후 ${since}s. 10분(600s)이 지나야 잰다 (CLAUDE.md, 상태 복원·리밸런싱 지연)"
pgrep -x edrdog-agent >/dev/null || die "edrdog-agent 가 돌고 있지 않다"

# api-service 호출. 실패해도 러너를 죽이지 않고 빈 값을 낸다
# api-service 는 Bearer 와 함께 X-API-Key 도 요구한다(로컬 기본값 dev-api-key)
api() { curl -sS --max-time 15 -H "Authorization: Bearer $EDRDOG_TOKEN" -H "X-API-Key: ${EDRDOG_API_KEY:-dev-api-key}" "$API$1" "${@:2}" || true; }
api /api/auth/me | jq -e .tenantId >/dev/null 2>&1 || die "토큰이 안 먹는다 (만료 또는 주소 오류)"

# 결과 파일과 시험 조건 기록
RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)
OUT=${OUT:-$PWD/dettest-$RUN_ID.md}
base=$(api "/api/alerts?host=$EDRDOG_HOST&from=$(( $(date +%s) - 600 ))000" | jq length 2>/dev/null) || base="?"
{
  echo "# 탐지 시험 결과 $RUN_ID"
  echo
  echo "- host: $EDRDOG_HOST, macOS $(sw_vers -productVersion), api: $API"
  echo "- 에이전트 바이너리 sha256: $(shasum -a 256 /usr/local/bin/edrdog-agent 2>/dev/null | cut -c1-64)"
  echo "- detector 마지막 기동: $LAST_RESTART_AT (시작 시점 기준 ${since}s 경과)"
  echo "- 시작 전 10분 이 host 의 알림: ${base}건 (0 이 아니면 배경 오탐이 섞인다)"
  echo "- 간격 ${GAP}s, 판정 대기 ${WAIT}s, 조회 주기 ${POLL}s, 응답 시험 $([ "$RESPOND" = 1 ] && echo 켬 || echo 끔)"
  echo "- 파이프라인 상태(시작 전): \`$(api /api/operations/health | jq -c '{kafkaLag, clickhouseIngestion}' 2>/dev/null)\`"
  echo
  echo "| ID | ATT&CK | 기대 | 시작(UTC) | 수집 | 시작→수집 s | 알림 룰 | 심각도/권고 | 시작→알림 s | 응답 | 알림→조치결과 s | 판정 |"
  echo "|---|---|---|---|---|---|---|---|---|---|---|---|"
} > "$OUT"
echo "결과 파일: $OUT"

# 시나리오 하나 돌리고 한 줄 기록
first=1
for f in $(list); do
  [ "$first" = 1 ] || { echo "다음 시나리오까지 ${GAP}s 대기 (5분 상관 창과 60초 쿨다운을 비운다)"; sleep "$GAP"; }
  first=0
  id=$(field "$f" id) attack=$(field "$f" attack) expect=$(field "$f" expect) want=$(field "$f" rule)
  type=$(field "$f" type) probe_tpl=$(field "$f" probe)
  export MARK="edrdogtest${RUN_ID//[^0-9]/}${id}"
  export WORK="/tmp/$MARK"
  probe=${probe_tpl//\{MARK\}/$MARK}
  mkdir -p "$WORK"
  # 어떤 경로로 끝나도 정리는 반드시 돈다
  trap 'bash "$f" cleanup; rm -rf "$WORK"' EXIT INT TERM

  # 실행
  start=$(date +%s)
  echo "[$id] 시작 $(date -u -r "$start" +%H:%M:%S)"
  bash "$f" run || echo "[$id] run 이 0 이 아닌 코드로 끝났다 (기록은 계속)"

  # 수집과 알림을 판정 대기 시간 동안 조회한다
  ingested="" alert="" seen=""
  while [ "$(date +%s)" -lt $(( start + WAIT )) ]; do
    now=$(date +%s)
    [ -n "$ingested" ] || ingested=$(api "/api/events?host=$EDRDOG_HOST&type=$type&from=${start}000&to=${now}000&limit=1000" \
      | jq -r --arg p "$probe" '[.[] | select([.cmdline, .process, .domain, .destIp] | map(. // "") | any(contains($p)))]
          | min_by(.ingestedAt) | .ingestedAt // empty' 2>/dev/null) || ingested=""
    if [ -z "$alert" ]; then
      alert=$(api "/api/alerts?host=$EDRDOG_HOST&from=${start}000&to=${now}000" | jq -c 'sort_by(.ts) | .[0] // empty' 2>/dev/null) || alert=""
      [ -z "$alert" ] || seen=$now
    fi
    [ -n "$ingested" ] && [ -n "$alert" ] && break
    sleep "$POLL"
  done
  # 창 안에 난 알림을 전부 모은다 (기대한 룰 말고 다른 룰도 기록)
  alerts=$(api "/api/alerts?host=$EDRDOG_HOST&from=${start}000&to=$(date +%s)000") || alerts="[]"
  rules=$(jq -r '[.[].ruleId] | unique | join(",")' <<<"$alerts" 2>/dev/null) || rules=""

  # 응답: kill 권고이고 대상이 이 시나리오의 시험 경로일 때만 대시보드 버튼과 같은 호출을 한다
  respond="-" rlat="-"
  if [ "$RESPOND" = 1 ]; then
    kill_alert=$(jq -c --arg m "$MARK" '[.[] | select(.action == "kill" and (.target | contains($m)))] | .[0] // empty' <<<"$alerts" 2>/dev/null) || kill_alert=""
    if [ -n "$kill_alert" ]; then
      aid=$(jq -r .id <<<"$kill_alert")
      exe=$(api "/api/alerts/$aid/respond" -X POST -H 'Content-Type: application/json' -d '{}' | jq -r '.executionId // empty' 2>/dev/null) || exe=""
      respond="요청 실패"
      for _ in $(seq 1 20); do
        [ -n "$exe" ] || break
        respond=$(api "/api/alerts/$aid/respond/$exe" | jq -r .status 2>/dev/null) || respond="조회 실패"
        [ "$respond" = PENDING ] || break
        sleep 3
      done
      rlat=$(( $(date +%s) - seen ))
      # KILLED 라도 프로세스가 정말 사라졌는지 본다
      if [ "$respond" = KILLED ] && [ -f "$WORK/pid" ] && kill -0 "$(cat "$WORK/pid")" 2>/dev/null; then respond="KILLED(살아 있음)"; fi
    elif [ -n "$rules" ]; then
      respond="건너뜀(kill 권고 아님 또는 대상이 시험 경로 밖)"
    fi
  fi

  # 정리
  bash "$f" cleanup || echo "[$id] cleanup 실패. 계획 문서의 수동 정리를 따른다" >&2
  rm -rf "$WORK"
  trap - EXIT INT TERM

  # 한 줄 기록
  got="X" clat="-" alat="-" sev="-"
  [ -z "$ingested" ] || { got="O"; clat=$(jq -n --argjson i "$ingested" --argjson s "$start" '($i - $s * 1000) / 1000'); }
  [ -z "$alert" ] || { alat=$(( seen - start )); sev=$(jq -r '"\(.severity)/\(.action)"' <<<"$alert"); }
  # 탐지는 기대한 룰이 창 안에 났을 때만 인정한다. 다른 룰만 났으면 우연한 알림일 수 있어 따로 적는다
  case "$expect:,$rules," in
    detected:,,) verdict="미탐" ;;
    detected:*",$want,"*) verdict="탐지" ;;
    detected:*) verdict="미탐(다른 룰만)" ;;
    missed:,,) verdict="미탐(예상대로)" ;;
    missed:*) verdict="탐지(예상 밖)" ;;
  esac
  echo "| $id | $attack | $expect | $(date -u -r "$start" +%H:%M:%S) | $got | $clat | ${rules:--} | $sev | $alat | $respond | $rlat | $verdict |" >> "$OUT"
  echo "[$id] 수집=$got 알림=${rules:--} 응답=$respond 판정=$verdict"
done

echo "끝. 결과: $OUT"
