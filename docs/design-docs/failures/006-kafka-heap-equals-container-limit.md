# 006. 힙 상한과 컨테이너 상한이 같아 Kafka 가 메모리를 다 썼다

## 증상
Kafka 파드의 메모리 사용량이 상한 1Gi 의 82% 에 붙어 있었다.

## 원인
Kafka 이미지의 기본 힙이 `-Xmx1G` 인데 컨테이너 상한도 1Gi 였다. **힙만으로 상한을 다 채우는 설정**이다. 힙 바깥(메타스페이스, 스레드 스택, 다이렉트 버퍼, 페이지 캐시)이 쓸 몫이 없다.

## 해결
상한을 2Gi 로 올리고 왜 그런지를 매니페스트에 남겼다.

```yaml
# 기본 힙이 -Xmx1G 라 상한이 1Gi 면 힙만으로 다 찬다. 비힙 몫까지 준다
resources:
  requests: { cpu: 250m, memory: 1Gi }
  limits:   { memory: 2Gi }
```

## 재발 방지
JVM 컨테이너의 메모리 상한은 힙 설정과 같이 본다. 둘이 같으면 그 자체가 결함이다.

같은 작업에서 **Kafka 파드에 영속 볼륨이 아예 없다**는 것도 드러났다. 파드가 새로 뜨면 토픽과 `__consumer_offsets` 가 통째로 사라진다. 아직 안 고쳤다(`docs/exec-plans/tech-debt.md`).

## 관련
- `docs/design-docs/failures/009-clickhouse-table-swap-refill.md`
