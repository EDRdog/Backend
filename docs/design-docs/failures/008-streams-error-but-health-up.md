# 008. Kafka Streams 가 ERROR 로 죽어도 health 가 UP 이었다

## 증상
detector 의 Streams 태스크가 ERROR 상태인데 `/actuator/health` 는 UP 을 돌려줬다. 쿠버네티스는 파드를 정상으로 보고 그대로 뒀다. **탐지가 통째로 멈춘 채로** 지나갔다.

## 원인
기본 health 지표는 프로세스가 떠 있는지만 본다. Kafka Streams 의 상태는 들어 있지 않다. 애플리케이션은 살아 있고 판정만 죽은 상태를 구분하지 못했다.

## 해결
Streams 상태를 health 에 넣어 ERROR 면 DOWN 이 되게 했다(#214).

## 재발 방지
"떠 있다" 와 "일을 하고 있다" 는 다르다. 핵심 기능이 별도 스레드나 외부 런타임에서 돌면 그 상태를 health 에 넣는다.

같은 생각으로 CD 에 배포 후 점검을 하나 넣었다. archiver 가 떴으면 반드시 참이어야 할 사실(`EXISTS TABLE edrdog.alerts`)을 직접 확인한다. 이전에는 ClickHouse 연결이 깨져도 health 가 UP 이라 판정기록이 하나도 안 쌓이는 채로 배포가 초록으로 끝났고, 비밀번호가 갈린 것을 몇 시간 뒤에야 알았다.

## 관련
- `docs/design-docs/decisions/002-kafka-streams-processor-api.md`
