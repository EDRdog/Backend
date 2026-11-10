# 탐지 시험 (KISA EDR)

ATT&CK 기법을 흉내 내는 양성·되돌림 가능한 시나리오로 수집→탐지→대응을 재는 자체 시험.
설계·근거·사람이 할 일은 `docs/exec-plans/completed/kisa-edr-detection-test.md` 에 있다.

- `run.sh` : 러너. 기본 `--dry-run`(출력만), 실제 실행은 `--run`, 대응까지 `--run --respond`.
- `scenarios/` : 시나리오 한 개 = 파일 한 개. 머리의 `# key: value` 를 러너가 읽는다. `run)`/`cleanup)` 두 단계.
- `results-template.md` : 결과표 틀.
- `kisa-report-template.md` : KISA 항목별 합/불 보고서 틀(스크린샷 포함, 최종 산출물).

서버는 로컬 스택이다(kind 인프라 + 호스트 bootRun). api 기본값 `http://localhost:8084`, Grafana `http://localhost:3000`.

외부로 나가는 곳: example.com(문서용), DNS 질의만 `tor2web.org`·`userstorage.mega.co.nz`(접속 없음). 그 밖은 없다.
전부 일반 사용자 권한이고, 만든 것은 cleanup 에서 지운다. 실제 악성코드·지속성·권한 상승·사용자 데이터 접근은 없다.
