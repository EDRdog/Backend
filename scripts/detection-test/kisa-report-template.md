# KISA EDR 탐지 항목 합/불 보고서 (템플릿)

로컬 스택에서 `run.sh --run [--respond]` 을 돌린 뒤 이 틀을 채운다.
설계·근거는 `docs/exec-plans/completed/kisa-edr-detection-test.md`. 측정 조건은 러너가 만든 `dettest-<UTC>.md` 에서 가져온다.

## 측정 조건 (dettest 파일에서 옮긴다)

- host / macOS 버전 / 에이전트 sha256:
- detector 마지막 기동(UTC), 시작까지 경과 초(600 이상):
- 시작 전 10분 이 host 알림 수(0 이어야 깨끗):
- otel-lgtm 지표 켜짐 여부(bootRun 또는 OTEL_ENABLED override):

## 항목별 판정

### EDR-BF-14.1 단말 행위 수집 합/불: ___

- 기준: 시나리오 행위가 이벤트로 수집된다.
- 근거: 결과표 `수집` 칸이 O 인 시나리오 비율. 최소 D01~D10 중 대부분 O.
- 스크린샷: (1) 러너 출력, (2) `GET /api/events?host=&type=` 또는 ClickHouse events 질의, (3) Grafana `agent_uplink_rtt_seconds` / `events_kafka_lag_seconds`.

### EDR-BF-15.1 탐지·분석 합/불: ___

- 기준: 수집된 행위로 알림이 난다.
- 근거: 탐지 기대 10개 중 알림이 난 수. 미탐 기대 6개는 알림이 없어야 정상.
- 스크린샷: (1) 러너 출력 `알림 룰` 칸, (2) `GET /api/alerts/{id}` 또는 ClickHouse alerts 질의, (3) Grafana `cep_event_lateness_*`, `increase(cep_alerts_total[5m])`.

### EDR-BF-16.1 대응 정책 수행 합/불: ___

- 기준: kill 권고 알림에 조치를 걸면 결과 상태가 돌아온다(가능하면 KILLED).
- 근거: `--respond` 로 D01 의 respond 결과. RESPONDER_EXECUTE_ENABLED=true 전제.
- 스크린샷: (1) 러너 `응답`·`알림→조치결과 s` 칸, (2) `GET /api/alerts/{id}/respond/{exe}` 응답. Grafana 패널은 없음(서버 미터 없음).

### EDR-SE-01 알려진 악성파일 탐지율 합/불(치환 기준): ___

- 원항목은 지문 기반 70% 다. 이 제품은 행위 기반이라 그대로 적용 불가(계획 문서 "SE-01 을 그대로 쓰지 않는 이유").
- 치환: ATT&CK 기법 샘플 16개에 대한 행위 탐지율 = 알림 난 시나리오 / 16.
- 수치: 탐지율 ___ % (분자 ___ / 분모 16). 참고로 노린 룰만 분모로 한 비율 ___ / 10.
- 판정은 "원항목 70% 통과" 가 아니라 "행위 탐지 커버리지가 설계대로 나왔는지"로 적는다.

## 종합

- 합격 항목: BF-14 / BF-15 / BF-16 중 ___
- 예상 밖(미탐이어야 하는데 탐지, 또는 반대): 시나리오 ___, 원인 한 줄.
- 측정 창·배경 오탐 관련 유의사항:
