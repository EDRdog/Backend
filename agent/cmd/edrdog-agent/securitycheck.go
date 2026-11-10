package main

import (
	"fmt"
	"io"
	"log/slog"

	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/audit"
	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/config"
	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/selfcheck"
)

// runChecks 는 자체시험과 무결성 검증을 한 번 돌려 감사기록과 로그에 남긴다(KISA BF-10, BF-11).
// 기동 시와 주기마다 부른다. 실패해도 에이전트는 계속 돈다. 멈추면 그 기기의 수집이 같이 멈춘다.
func runChecks(in selfcheck.Inputs, binary string, al *audit.Log, log *slog.Logger) {
	recordResults(al, log, audit.TypeSecurityCheck, selfcheck.Run(in))
	recordResults(al, log, audit.TypeIntegrityCheck, selfcheck.CheckIntegrity(binary, in.ConfigPath))
}

// recordResults 는 항목 결과를 한 건의 감사기록으로 접는다.
func recordResults(al *audit.Log, log *slog.Logger, typ string, results []selfcheck.Result) {
	ok, detail := selfcheck.Summarize(results)
	if !ok {
		log.Warn("보안 점검 실패", "type", typ, "detail", detail)
		al.Record(typ, audit.Failure, detail)
		return
	}
	log.Info("보안 점검 통과", "type", typ)
	al.Record(typ, audit.Success, detail)
}

// runSecurityCheck 는 관리자가 -security-check 로 요청한 점검이다. 항목을 출력하고, 실패가 없으면 true 다.
// 서버로는 보내지 않는다. 센서는 띄우지 않았으므로 sensors 항목은 건너뛴다.
func runSecurityCheck(out io.Writer, cfg config.Config, configPath, binary string) bool {
	results := append(selfcheck.Run(selfcheck.Inputs{Config: cfg, ConfigPath: configPath}),
		selfcheck.CheckIntegrity(binary, configPath)...)
	for _, r := range results {
		fmt.Fprintf(out, "%-20s %-5s %s\n", r.Name, r.Status, r.Detail)
	}
	ok, _ := selfcheck.Summarize(results)
	return ok
}

// recordBaseline 은 설치 스크립트가 부르는 -record-baseline 이다. 지금 바이너리와 설정 파일을 기준값으로 기록한다.
func recordBaseline(out io.Writer, configPath, binary string) error {
	if err := selfcheck.RecordBaseline(binary, configPath); err != nil {
		return err
	}
	fmt.Fprintf(out, "기준값을 기록했다: %s\n", selfcheck.BaselinePath(configPath))
	return nil
}
