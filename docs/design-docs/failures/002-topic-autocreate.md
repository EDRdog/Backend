# 002. 토픽이 자동 생성돼 파티션 1개·비압축으로 굳었다

## 증상
토픽 생성 Job 은 성공으로 끝나고 로그에도 아무 말이 없는데, 운영 토픽이 파티션 1개에 압축이 꺼진 상태였다. 설계는 파티션 3 + zstd 였다.

## 원인
`auto.create.topics.enable=true` 였다. 서비스나 Streams 가 Job 보다 먼저 토픽을 건드리면 브로커가 기본 설정(파티션 1, 비압축)으로 먼저 만들어 버린다.

그 다음이 문제다. **`kafka-topics.sh --if-not-exists` 도 Kafka Streams 도 이미 있는 토픽의 설정을 바꾸지 않는다.** 실패가 조용해서 Job 은 성공으로 끝난다.

파티션 1 이면 병렬성 상한이 1 이고, 비압축이면 압축 결정(ADR 013)이 통째로 무효다.

## 해결
브로커의 자동 생성을 껐다(`514e2fe`). 그러면 Job 이 반드시 먼저 올바른 설정으로 만들어야 한다.

## 재발 방지
- CD 가 Kafka 롤아웃이 Ready 가 된 **뒤에** 토픽 Job 을 한 번 더 돌리고, `kubectl wait --for=condition=complete` 로 완료를 확인한다. 실패하면 배포를 실패시킨다.
- 자동 생성이 켜져 있던 동안은 이 경합을 서비스가 덮어 줬다. 끄고 나서야 순서를 강제해야 한다는 것이 드러났다.

## 관련
- `docs/design-docs/decisions/001-kafka-between-collection-and-detection.md`
