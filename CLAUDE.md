@AGENTS.md

이 아래는 Claude 전용 메모다. 명령어, 금지사항, 문서 지도는 AGENTS.md 에 있다.


이 레포를 만질 때 모르면 틀린 결론에 도달하는 것들. 코드나 README 를 읽어서는 안 나오는 것만 적는다.

## 운영 관측은 자바 에이전트만 받는다

매니페스트가 여섯 서비스 전부 `OTEL_ENABLED=false` 로 둔다. **Micrometer 미터는 운영에서 한 건도 안 보인다.**
로컬 LGTM 전용이다. `cep.*` 도 마찬가지다.

새 지표를 운영에서 봐야 하면 미터만 넣어서는 안 된다. `TraceAttribute.number(...)` 로 트랜잭션 속성을
한 번 더 심어야 조회된다(#181 에서 이걸 몰라 한 번 헛돌았다).

```
SELECT percentile(kafkaLagMs, 50, 95, 99) FROM Transaction WHERE appName = 'archiver'
```

벤더 API 를 쓰는 자리는 `KafkaTraceLink` 와 `TraceAttribute` 둘뿐이다. 관측 백엔드를 옮기면 그 둘만 걷어낸다.

## 뉴렐릭 값이 필요하면 Actions 로 돌린다

```
gh workflow run nrql.yml --ref dev -f query="SELECT ..."
```

화면의 쿼리 빌더는 자주 멈춘다. 그리고 NerdGraph 를 부를 User key 는 레포 시크릿에만 있고 노트북에는 없다.

## 배포 직후 10분의 detector 지연은 믿지 마라

Kafka Streams 가 재시작하면 상태 복원과 리밸런싱을 거치고, 그 대기가 그대로 지연으로 잡힌다.
실제로 배포 직후 `kafkaLagMs` p50 이 82.9초였다가 한 시간 뒤 105ms 였다. 790배 차이다.

압축·병렬성 같은 개선 효과를 잴 때 이 창을 피해야 한다. 안 그러면 개선이 아니라 리밸런싱을 재게 된다.

## 알림 조건은 배포로 반영되지 않는다

`Alerts` 워크플로를 손으로 돌려야 서버 조건이 바뀐다. 배포와 별개 경로다.

조건이 보는 스팬 이름이 실재하는지 스크립트가 먼저 확인하고, 없으면 종료 코드 1 을 낸다.
이 확인이 없던 시절 collector·detector 조건이 지어낸 이름을 보며 조용히 죽어 있었다(#285).
