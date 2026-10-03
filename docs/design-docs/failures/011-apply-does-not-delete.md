# 011. apply 는 삭제를 안 해서 레포에서 뺀 리소스가 서버에 남았다

## 증상
수집 입구를 collector 로 옮기면서 레포에서 `api-service-agent` Service 를 지웠다. 그런데 새 Service 가 `port is already allocated` 로 아예 만들어지지 않았다.

## 원인
**`kubectl apply` 는 매니페스트에 없는 것을 지우지 않는다.** 레포에서 빠진 Service 가 서버에 그대로 남아 NodePort 30443 을 붙들고 있었다. NodePort 는 클러스터에서 하나뿐인 자원이라 새 Service 가 같은 포트를 못 잡는다.

같은 배포 경로에서 한 번 더 깨졌다. **Job 은 `spec.template` 이 immutable** 이라 내용이 바뀌면 apply 가 `field is immutable` 로 죽는다. `set -e` 때문에 뒤따르는 apply 와 서비스 롤링까지 통째로 안 돌았다. `--dry-run=client` 로는 안 잡힌다(서버의 기존 오브젝트와 대조하지 않는다).

## 해결
- 없앤 리소스는 CD 에 명시적 삭제 줄로 적는다(`kubectl delete service api-service-agent --ignore-not-found`).
- Job 은 apply 전에 지우고 다시 만든다. 여기 있는 Job 은 부트스트랩용이라 다시 돌려도 안전하다.

## 재발 방지
레포에서 리소스를 지우면 서버에서도 지워지는지 확인한다. apply 만으로는 안 된다. 삭제 줄을 적는 것은 손이 가는 방식이고, 묘비명이 늘어나면 GitOps 도구(prune)를 검토할 신호다. 지금은 묘비명 한 줄이라 그대로 둔다.

## 관련
- `docs/design-docs/decisions/011-collector-as-ingest-entrypoint.md`, `docs/exec-plans/tech-debt.md`
