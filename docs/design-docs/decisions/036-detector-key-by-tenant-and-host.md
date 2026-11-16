# 036. detector 상관 버퍼의 키는 tenant/host 다

- 날짜: 2026-11-16
- 상태: 채택

## 배경
detector 는 `events` 를 host 로 다시 키를 잡아(`events-by-host`) 상관 버퍼를 host 별로 들고 있었다. 다른 조직에 같은 이름의 PC 가 있으면 두 PC 의 행동이 한 버퍼에 섞여, A 회사 PC 의 워드 실행과 B 회사 PC 의 셸 실행이 한 시퀀스로 묶여 B 회사에 알림이 나갔다(`DetectionTopologyTest.sameHostNameAcrossTenants_doesNotCorrelate` 로 재현). 여러 회사가 같이 쓰는 서비스에서 조직 분리가 깨지는 보안 결함이다.

## 결정
- `DetectionTopology` 의 `selectKey` 를 `tenantId + "/" + host` 로 바꾼다. 상태 저장소, 대기 목록이 모두 이 키를 쓴다. tenantId 는 양의 정수 PK 라 `/` 가 없어 경계가 모호하지 않다.
- `alerts` 레코드의 키는 지금처럼 host 다(`alert.host()`). 하류(alert, responder, archiver)는 키를 읽지 않고 값의 tenantId 를 쓴다.
- `events` 토픽의 파티션 키(collector 가 넣는 host)는 그대로 둔다.

## 근거
- 기술부채에서 미룬 이유는 키를 바꾸면 파티션 배치가 달라진다는 것이었다. 바뀌는 것은 detector 내부 리파티션 토픽의 배치뿐이다. 한 PC 의 이벤트는 여전히 `events` 의 한 파티션에서 와서 같은 키로 같은 리파티션 파티션에 순서대로 들어간다. README 파티션 키 절의 순서 보장은 그대로다. 파티션 수도 바꾸지 않는다.
- 판정 로직은 키를 해석하지 않으므로(불투명한 문자열로 get/put 만 한다) 키 한 곳만 바꾸면 된다.

## 버린 대안
- `events` 토픽 키까지 tenant/host 로 바꾸기: collector 와 archiver 까지 손대야 하고, 격리에는 detector 쪽 키만으로 충분하다.
- 리파티션 토픽 이름(`events-by-host`)을 바꾸기: 내부 토픽이 새로 생길 뿐 얻는 것이 없다.

## 결과/영향
- 배포 직후 기존 RocksDB 에 host 만으로 저장된 버퍼는 새 키로 다시 읽히지 않는다. 그 순간 진행 중이던 상관(최대 5분 윈도우)은 한 번 끊긴다. 대기 트리거가 남은 옛 버퍼는 재할당 때 한 번 판정되고, 이벤트만 남은 옛 버퍼는 단말 수만큼 스토어에 남는다. 실사용자가 없어 그대로 둔다.

## 관련
- `docs/requirements/detection.md` TEN-3, `README.md` 파티션 키 절
