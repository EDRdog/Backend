#!/usr/bin/env bash
# 뉴렐릭 알림 조건을 만든다(멱등: 같은 이름이 있으면 건너뛴다).
#
# 왜 필요한가: #247 때 archiver 가 메시지마다 실패해 ClickHouse 적재가 통째로 멈췄는데,
# 로그를 뒤질 때까지 아무도 몰랐다. 계측만 있고 알림이 없으면 사람이 화면을 볼 때만 안다.
#
# 조건은 전부 "파이프라인 한 단이 멈췄다"를 본다. 지연이나 에러율이 아니라 정지를 본다.
# 이 시스템에서 가장 나쁜 상태가 조용히 멈춰 있는 것이기 때문이다.
#
# 실행:
#   NEW_RELIC_API_KEY=<User key> NEW_RELIC_ACCOUNT_ID=8385315 ./scripts/newrelic-alerts.sh
#
# ⚠️ 라이선스 키가 아니라 User key 다. 프로필 → API keys → Create a key → Key type: User.
set -euo pipefail

: "${NEW_RELIC_API_KEY:?User key 가 필요하다 (라이선스 키 아님)}"
ACCOUNT="${NEW_RELIC_ACCOUNT_ID:-8385315}"
POLICY_NAME="EDRdog 파이프라인 정지"
API=https://api.newrelic.com/graphql

nerdgraph() {
  curl -sS "$API" -H "Api-Key: $NEW_RELIC_API_KEY" -H 'Content-Type: application/json' \
    --data-binary @- <<< "$(jq -Rs '{query: .}' <<< "$1")"
}

# --- 정책 (없으면 만든다)
POLICY_ID=$(nerdgraph "{actor{account(id:$ACCOUNT){alerts{policiesSearch(searchCriteria:{name:\"$POLICY_NAME\"}){policies{id}}}}}}" \
  | jq -r '.data.actor.account.alerts.policiesSearch.policies[0].id // empty')

if [ -z "$POLICY_ID" ]; then
  POLICY_ID=$(nerdgraph "mutation{alertsPolicyCreate(accountId:$ACCOUNT,policy:{name:\"$POLICY_NAME\",incidentPreference:PER_CONDITION}){id}}" \
    | jq -r '.data.alertsPolicyCreate.id // empty')
  [ -n "$POLICY_ID" ] || { echo "정책 생성 실패" >&2; exit 1; }
  echo "정책 생성: $POLICY_ID"
else
  echo "정책 재사용: $POLICY_ID"
fi

# --- 조건
# 이름|설명|NRQL|threshold_duration(초)
#
# 전부 "5분 동안 0건" 이다. 값이 없는 것과 0인 것을 구분해야 해서 sinceValue 로 빈 구간도 위반으로 본다.
CONDITIONS=$(cat <<'EOF'
archiver ClickHouse 적재 중단|events 를 받아도 ClickHouse 로 안 넣고 있다. #247 이 이 상태였다.|SELECT count(*) FROM Span WHERE entity.name = 'archiver' AND name = 'External/clickhouse/JavaHttpClient/send'|300
detector 판정 중단|이벤트가 들어와도 판정이 안 돌고 있다. 탐지가 통째로 멈춘 상태다.|SELECT count(*) FROM Span WHERE entity.name = 'detector' AND nr.entryPoint IS TRUE AND name LIKE 'Custom/%KafkaTraceLink%'|300
collector 수집 중단|엔드포인트에서 이벤트가 안 들어오고 있다. 파이프라인 최상단이 끊겼다.|SELECT count(*) FROM Span WHERE entity.name = 'collector' AND name = 'MessageBroker/Kafka/Topic/Produce/Named/events'|300
서비스 5xx 발생|4xx 는 뺀다. 목적지 미등록 404 가 정상 흐름이라 같이 세면 상시 발화한다.|SELECT count(*) FROM Transaction WHERE http.statusCode >= 500|300
EOF
)

while IFS='|' read -r NAME DESC NRQL DURATION; do
  [ -n "$NAME" ] || continue
  EXISTS=$(nerdgraph "{actor{account(id:$ACCOUNT){alerts{nrqlConditionsSearch(searchCriteria:{name:\"$NAME\"}){nrqlConditions{id}}}}}}" \
    | jq -r '.data.actor.account.alerts.nrqlConditionsSearch.nrqlConditions[0].id // empty')
  if [ -n "$EXISTS" ]; then
    echo "  건너뜀(이미 있음): $NAME"
    continue
  fi

  # 5xx 는 "1건이라도 나오면" 이고, 나머지는 "0건이 이어지면" 이다. 방향이 반대다.
  #
  # fillOption 은 STATIC / LAST_VALUE / NONE 셋뿐이고 fillValue 는 STATIC 일 때만 받는다.
  # 5xx 는 없는 게 정상이라 빈 구간을 메울 이유가 없다(메우면 값이 안 변해 판정만 흐려진다).
  if [[ "$NAME" == *5xx* ]]; then
    OP=ABOVE; THRESHOLD=0; FILL='fillOption:NONE'
  else
    OP=BELOW; THRESHOLD=1; FILL='fillOption:STATIC,fillValue:0'   # 정지는 데이터가 없는 것으로 나타난다
  fi

  RESP=$(nerdgraph "mutation{alertsNrqlConditionStaticCreate(accountId:$ACCOUNT,policyId:\"$POLICY_ID\",condition:{
      name:\"$NAME\",
      description:\"$DESC\",
      enabled:true,
      nrql:{query:\"$NRQL\"},
      signal:{aggregationWindow:60,aggregationMethod:EVENT_FLOW,aggregationDelay:120,$FILL},
      terms:[{operator:$OP,threshold:$THRESHOLD,thresholdDuration:$DURATION,thresholdOccurrences:ALL,priority:CRITICAL}],
      violationTimeLimitSeconds:86400
    }){id}}")
  ID=$(jq -r '.data.alertsNrqlConditionStaticCreate.id // empty' <<< "$RESP")

  if [ -n "$ID" ]; then
    echo "  생성: $NAME ($ID)"
  else
    echo "  실패: $NAME" >&2
    jq -r '.errors[]?.message // empty' <<< "$RESP" | sed 's/^/    /' >&2
  fi
done <<< "$CONDITIONS"

echo
# 정책 상세는 엔티티 리다이렉트로만 열린다. GUID 는 "계정|AIOPS|POLICY|id" 를 base64 한 것이고
# 패딩(=)은 URL 에 안 들어간다. /alerts/policies/detail/<id> 같은 경로는 404 다.
GUID=$(printf '%s' "$ACCOUNT|AIOPS|POLICY|$POLICY_ID" | base64 | tr -d '=\n')
echo "정책: https://one.newrelic.com/redirect/entity/$GUID?account=$ACCOUNT"
echo "⚠️ 알림 채널(Slack·메일)은 아직 없다. Alerts → Destinations 에서 붙여야 실제로 도착한다."
