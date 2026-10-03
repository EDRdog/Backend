# 010. 관측 스택이 관측 대상과 같이 죽어 사후 분석이 안 됐다

## 증상
서버가 죽었는데 왜 죽었는지 볼 Grafana 도 같이 죽어 있었다.

## 원인
관측 스택(otel-lgtm)이 관측 대상과 **같은 서버**에 살았다. 670Mi 를 쓰는 스택이 OOM 을 유발하고, 그 OOM 을 설명할 데이터를 같이 날렸다.

## 해결
운영 관측을 외부(뉴렐릭)로 내보냈다. 로컬은 otel-lgtm 을 그대로 쓴다.

## 재발 방지
관측 데이터는 관측 대상 바깥에 둔다. 같은 장애 도메인 안에 있으면 정작 필요한 순간에 없다.

같은 성격의 사건이 하나 더 있었다. ClickHouse 자체 진단 로그가 디스크의 대부분을 먹고 있었다. `text_log` 459MiB, `query_log` 69MiB, `metric_log` 52MiB 였는데 정작 `edrdog.alerts` 는 14KiB 였다. 관측 장치가 관측 대상보다 커진 상태다. 불필요한 system 로그를 끄고 TTL 을 넣었다.

## 관련
- `docs/design-docs/decisions/012-newrelic-java-agent-in-production.md`
