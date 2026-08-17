#!/usr/bin/env bash
# 뉴렐릭 알림 조건을 만들고 갱신한다(멱등: 같은 이름이 있으면 내용을 덮어쓴다).
#
# 왜 필요한가: #247 때 archiver 가 메시지마다 실패해 ClickHouse 적재가 통째로 멈췄는데,
# 로그를 뒤질 때까지 아무도 몰랐다. 계측만 있고 알림이 없으면 사람이 화면을 볼 때만 안다.
#
# 조건은 전부 "파이프라인 한 단이 멈췄다"를 본다. 지연이나 에러율이 아니라 정지를 본다.
# 이 시스템에서 가장 나쁜 상태가 조용히 멈춰 있는 것이기 때문이다.
#
# 다만 "0건" 을 정지로 보면 안 된다. 엔드포인트가 개인 맥북이라 새벽에는 유입이 정상적으로 0 이고,
# 그때도 하단은 계속 0건이다. 그래서 건수가 아니라 들어온 것 대비 처리된 비율을 본다(#285).
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
# 이름|설명|NRQL|연산자|임계값|의존 스팬(;로 구분)
#
# "0건이 이어지면" 이 아니라 "들어온 것 대비 처리된 비율" 을 본다. 유입이 0 인 시간(엔드포인트가
# 꺼진 새벽)에도 0건은 계속 0건이라, 건수로 보면 정상 상태를 정지로 오해한다. 실제로 그렇게 3일간
# 9건이 헛울렸다(#285).
#
# 분자·분모에 각각 1 을 더한다. 유입이 0 이면 (0+1)/(0+1)=1 이 되어 확실히 정상으로 읽힌다.
# 이걸 안 하면 0/0 이 NULL 인지 0 인지에 판정이 달려서, 플랫폼 동작에 기대는 조건이 된다.
#
# 정상이면 1.0 근처, 처리가 멈추면 0 에 가까워진다. 0.5 는 그 사이다. 집계 창 경계에서 한 건이
# 다음 창으로 밀리는 정도로는 안 울리게 두려면 1.0 에 붙이면 안 된다.
CONDITIONS=$(cat <<'EOF'
archiver ClickHouse 적재 중단|소비는 하는데 ClickHouse 로 안 넣고 있다. #247 이 이 상태였다.|SELECT (filter(count(*), WHERE name = 'External/clickhouse/JavaHttpClient/send') + 1) / (filter(count(*), WHERE name = 'MessageBroker/SpringKafka/Topic/Consume/Named/events') + 1) FROM Span WHERE entity.name = 'archiver'|BELOW|0.5|External/clickhouse/JavaHttpClient/send;MessageBroker/SpringKafka/Topic/Consume/Named/events
detector 판정 중단|발행은 되는데 판정이 안 돌고 있다. 탐지가 통째로 멈춘 상태다.|SELECT (filter(count(*), WHERE entity.name = 'detector' AND name = 'Java/com.edrdog.schema.KafkaTraceLink/linked') + 1) / (filter(count(*), WHERE entity.name = 'collector' AND name = 'Java/org.apache.kafka.clients.producer.KafkaProducer/doSend') + 1) FROM Span WHERE entity.name IN ('collector', 'detector')|BELOW|0.5|Java/com.edrdog.schema.KafkaTraceLink/linked;Java/org.apache.kafka.clients.producer.KafkaProducer/doSend
collector 발행 중단|이벤트를 받고도 Kafka 로 안 내보내고 있다. 수집 입구에서 버려지는 상태다.|SELECT (filter(count(*), WHERE name = 'Java/org.apache.kafka.clients.producer.KafkaProducer/doSend') + 1) / (filter(count(*), WHERE name = 'Spring/Java/com.edrdog.collectorservice.agent.web.AgentController/events') + 1) FROM Span WHERE entity.name = 'collector'|BELOW|0.5|Java/org.apache.kafka.clients.producer.KafkaProducer/doSend;Spring/Java/com.edrdog.collectorservice.agent.web.AgentController/events
서비스 5xx 발생|4xx 는 뺀다. 목적지 미등록 404 가 정상 흐름이라 같이 세면 상시 발화한다.|SELECT count(*) FROM Transaction WHERE http.statusCode >= 500|ABOVE|0|
EOF
)

# 조건이 보는 스팬이 실제로 데이터에 있는지 먼저 확인한다.
#
# ⚠️ 이 확인이 없으면 이름 하나 어긋난 조건이 조용히 죽는다. 스크립트는 성공으로 끝나고 뉴렐릭은
#    초록불인데 그 단은 감시되지 않는다. collector·detector 조건이 실제로 그 상태였다(#285).
#    지어낸 이름이었다. 실제로는 MessageBroker/Kafka/... 가 아니라 KafkaProducer/doSend 이고,
#    Custom/%KafkaTraceLink% 가 아니라 Java/com.edrdog.schema.KafkaTraceLink/linked 다.
#
# 24시간을 보는 이유: 엔드포인트가 새벽에 꺼져서 짧은 창으로 보면 멀쩡한 이름도 0건으로 나온다.
signal_missing() {
  local SPAN="$1"
  local N
  N=$(nerdgraph "{actor{account(id:$ACCOUNT){nrql(query:\"SELECT count(*) FROM Span WHERE name = '$SPAN' SINCE 24 hours ago\"){results}}}}" \
    | jq -r '.data.actor.account.nrql.results[0].count // 0')
  [ "${N:-0}" = "0" ] || [ -z "$N" ]
}

FAILED=0

while IFS='|' read -r NAME DESC NRQL OP THRESHOLD SIGNALS; do
  [ -n "$NAME" ] || continue

  MISSING=""
  if [ -n "$SIGNALS" ]; then
    IFS=';' read -ra SPANS <<< "$SIGNALS"
    for SPAN in "${SPANS[@]}"; do
      if signal_missing "$SPAN"; then
        MISSING="$MISSING $SPAN"
      fi
    done
  fi
  if [ -n "$MISSING" ]; then
    echo "  건너뜀(스팬이 데이터에 없음): $NAME ->$MISSING" >&2
    FAILED=1
    continue
  fi

  # fillOption 은 STATIC / LAST_VALUE / NONE 셋뿐이고 fillValue 는 STATIC 일 때만 받는다.
  # 비율 조건은 빈 구간을 1(정상)로 메운다. 데이터가 아예 없는 것은 "넣을 게 없다"는 뜻이다.
  # 5xx 는 없는 게 정상이라 메울 이유가 없다(메우면 값이 안 변해 판정만 흐려진다).
  if [ "$OP" = ABOVE ]; then
    FILL='fillOption:NONE'
  else
    FILL='fillOption:STATIC,fillValue:1'
  fi

  BODY="name:\"$NAME\",
      description:\"$DESC\",
      enabled:true,
      nrql:{query:\"$NRQL\"},
      signal:{aggregationWindow:60,aggregationMethod:EVENT_FLOW,aggregationDelay:120,$FILL},
      terms:[{operator:$OP,threshold:$THRESHOLD,thresholdDuration:300,thresholdOccurrences:ALL,priority:CRITICAL}],
      violationTimeLimitSeconds:86400"

  EXISTS=$(nerdgraph "{actor{account(id:$ACCOUNT){alerts{nrqlConditionsSearch(searchCriteria:{name:\"$NAME\"}){nrqlConditions{id}}}}}}" \
    | jq -r '.data.actor.account.alerts.nrqlConditionsSearch.nrqlConditions[0].id // empty')

  # 있으면 갱신한다. 예전에는 건너뛰었는데, 그러면 NRQL 을 고쳐도 서버 조건이 그대로여서
  # 고친 줄 알고 넘어가게 된다.
  if [ -n "$EXISTS" ]; then
    RESP=$(nerdgraph "mutation{alertsNrqlConditionStaticUpdate(accountId:$ACCOUNT,id:\"$EXISTS\",condition:{$BODY}){id}}")
    ID=$(jq -r '.data.alertsNrqlConditionStaticUpdate.id // empty' <<< "$RESP")
    ACTION=갱신
  else
    RESP=$(nerdgraph "mutation{alertsNrqlConditionStaticCreate(accountId:$ACCOUNT,policyId:\"$POLICY_ID\",condition:{$BODY}){id}}")
    ID=$(jq -r '.data.alertsNrqlConditionStaticCreate.id // empty' <<< "$RESP")
    ACTION=생성
  fi

  if [ -n "$ID" ]; then
    echo "  $ACTION: $NAME ($ID)"
  else
    echo "  실패: $NAME" >&2
    jq -r '.errors[]?.message // empty' <<< "$RESP" | sed 's/^/    /' >&2
    FAILED=1
  fi
done <<< "$CONDITIONS"

# 이름을 바꾼 조건은 옛 이름이 서버에 그대로 남는다. 지우지 않으면 죽은 조건이 목록에 남아
# 감시되고 있다고 착각하게 된다.
OLD_NAMES=$(cat <<'EOF'
collector 수집 중단
EOF
)
while read -r OLD; do
  [ -n "$OLD" ] || continue
  OLD_ID=$(nerdgraph "{actor{account(id:$ACCOUNT){alerts{nrqlConditionsSearch(searchCriteria:{name:\"$OLD\"}){nrqlConditions{id}}}}}}" \
    | jq -r '.data.actor.account.alerts.nrqlConditionsSearch.nrqlConditions[0].id // empty')
  [ -n "$OLD_ID" ] || continue
  nerdgraph "mutation{alertsConditionDelete(accountId:$ACCOUNT,id:\"$OLD_ID\"){id}}" > /dev/null
  echo "  삭제(이름이 바뀐 옛 조건): $OLD ($OLD_ID)"
done <<< "$OLD_NAMES"

echo
# 정책 상세는 엔티티 리다이렉트로만 열린다. GUID 는 "계정|AIOPS|POLICY|id" 를 base64 한 것이고
# 패딩(=)은 URL 에 안 들어간다. /alerts/policies/detail/<id> 같은 경로는 404 다.
GUID=$(printf '%s' "$ACCOUNT|AIOPS|POLICY|$POLICY_ID" | base64 | tr -d '=\n')
echo "정책: https://one.newrelic.com/redirect/entity/$GUID?account=$ACCOUNT"
# 채널은 2026-08-12 에 붙었다. "파이프라인 정지 → Slack" 워크플로가 Slack 으로 보낸다.
#
# 그 워크플로는 조건 이름을 나열하지 않고 정책 하나(EDRdog 파이프라인 정지, id 7881843)로 거른다.
# 그래서 이 스크립트가 만드는 조건은 이름을 바꿔도 자동으로 알림이 간다. 조건 이름으로 거르는
# 방식이었다면 이름을 바꿀 때마다 워크플로도 같이 고쳐야 했다.
echo "알림: '파이프라인 정지 → Slack' 워크플로가 이 정책 전체를 받아 Slack 으로 보낸다."

# 초록불로 끝내면 안 된다. 조건 하나가 안 붙은 것을 모르고 넘어가는 것이 #285 였다.
exit "$FAILED"
