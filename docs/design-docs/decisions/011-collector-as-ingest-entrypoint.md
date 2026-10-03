# 011. 수집 입구를 api-service 에서 collector-service 로 분리한다

- 날짜: 2026-08-02
- 상태: 채택

## 배경
예전에는 api-service 가 에이전트 요청을 받아 `events-raw` 로 흘리고, collector 가 그걸 소비해 검증했다. 프론트 조회와 엔드포인트 수집이 같은 프로세스에 있었다.

## 결정
에이전트는 collector-service 에 직접 붙는다(HTTPS 8443, NodePort 30443). collector 가 인증·tenant 태깅·검증까지 하고 통과한 것만 `events` 로 발행한다. `events-raw` 토픽은 없앴다.

## 근거
> 프론트 조회가 몰려 api-service 가 흔들릴 때 엔드포인트 수집까지 같이 멈추는 게 가장 나쁜 조합이었다.

수집은 멈추면 복구되지 않는다. 그 사이 커널에서 일어난 일은 다시 받을 방법이 없다. 조회는 느려도 되지만 수집은 아니다. 토픽을 한 단계 줄인 것은 덤이다.

**검증 실패를 어떻게 다루나**: 버리되 건수를 로그에 남긴다. 한 건이 이상하다고 요청 전체를 실패시키면 같은 배치의 정상 이벤트까지 못 받는다.

## 결과/영향
- api-service 에 남아 있던 `api-service-agent` Service 가 NodePort 30443 을 계속 붙들고 있어서 새 Service 가 "port is already allocated" 로 안 만들어졌다(#184). `apply` 는 매니페스트에 없는 것을 지우지 않는다. CD 에 명시적 삭제 줄을 남겼다.
- collector 는 enroll 때만 api-service 에 tenant 해석을 묻는다. 매 요청이 아니다.

## 관련
- [004](004-server-resolves-tenant-from-node-key.md), `k8s/README.md`
