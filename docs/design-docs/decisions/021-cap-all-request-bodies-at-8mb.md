# 021. collector 는 압축 여부와 상관없이 요청 본문을 8MB 로 자른다

- 날짜: 2026-11-10
- 상태: 채택

## 배경
`GzipRequestFilter` 의 8MB 상한이 `Content-Encoding: gzip` 에만 걸렸다. 압축 안 한 본문은 그대로 통과해 `EventsRequest` 가 통째로 JsonNode 로 파싱했다. 인증된 노드 하나가 수백 MB 를 보내면 collector 힙이 터진다. `docs/agent-protocol.md` 는 8MB 초과를 413 이라고 적고 있었다(KISA H6).

## 결정
- 같은 상수(`MAX_DECOMPRESSED_BYTES`)를 비압축 본문에도 쓴다. 상한 스트림(`LimitedStream`)이 gzip 과 비압축을 같이 감싼다.
- `Content-Length` 가 상한을 넘으면 읽기 전에 413 으로 끊는다. 길이를 모르는 청크 본문은 읽으면서 센다.
- 헤더부터 깨진 gzip 본문(`ZipException`, `EOFException`)은 500 이 아니라 400 으로 돌린다.

## 근거
**Tomcat 설정(`max-http-form-post-size`, `maxSwallowSize`)을 버린 이유**: 폼 본문과 중단된 요청 소비에만 걸린다. `application/json` 본문을 읽는 양은 막지 못한다.

**필터에서 세는 방법을 고른 이유**: gzip 상한이 이미 같은 자리에서 스트림을 세고 있어, 그 스트림을 비압축 본문에도 씌우면 새 구조가 필요 없다.

## 결과/영향
- 정상 배치는 1MB 언저리라 영향이 없다. 압축 본문의 `Content-Length`(압축 크기)가 8MB 를 넘어도 413 이다. 푼 크기는 그보다 크므로 어차피 상한을 넘는다.
- 필드 하나의 길이 상한과 detector 의 바이트 기준 버퍼 상한은 하지 않았다(tech-debt).

## 관련
- [013](013-compression-by-segment.md), `docs/security/kisa-2021-review.md` H6
