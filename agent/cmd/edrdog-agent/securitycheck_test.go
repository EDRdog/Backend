package main

import (
	"bytes"
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/audit"
	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/config"
	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/selfcheck"
)

// installed 는 바이너리, 설정 파일, 기준값이 있는 설치 상태를 만든다.
func installed(t *testing.T) (binary, configPath string) {
	t.Helper()
	dir := t.TempDir()
	binary = filepath.Join(dir, "edrdog-agent")
	configPath = filepath.Join(dir, "config.json")
	if err := os.WriteFile(binary, []byte("agent"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(configPath, []byte(`{}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := selfcheck.RecordBaseline(binary, configPath); err != nil {
		t.Fatal(err)
	}
	return binary, configPath
}

// drain 은 Log 에 쌓인 기록을 꺼낸다.
type captureSender struct{ events []audit.Event }

func (c *captureSender) SendAudit(_ context.Context, e []audit.Event) error {
	c.events = append(c.events, e...)
	return nil
}

func recorded(t *testing.T, l *audit.Log) []audit.Event {
	t.Helper()
	s := &captureSender{}
	if err := l.Flush(context.Background(), s); err != nil {
		t.Fatal(err)
	}
	return s.events
}

func quietLogger() *slog.Logger { return slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil)) }

func TestRunChecksRecordsOneAuditEventPerKind(t *testing.T) {
	binary, configPath := installed(t)
	l := audit.NewLog(10)

	runChecks(selfcheck.Inputs{Config: config.Config{BaseURL: "https://x"}, ConfigPath: configPath}, binary, l, quietLogger())

	got := recorded(t, l)
	if len(got) != 2 || got[0].Type != audit.TypeSecurityCheck || got[1].Type != audit.TypeIntegrityCheck {
		t.Fatalf("자체시험과 무결성 기록이 하나씩 있어야 한다: %+v", got)
	}
	if got[1].Result != audit.Success {
		t.Errorf("기준값 그대로인데 무결성이 실패다: %+v", got[1])
	}
	// ca_cert_path 가 없어 고정 항목이 실패하므로 자체시험 사건은 실패다.
	if got[0].Result != audit.Failure || !strings.Contains(got[0].Detail, "server_pinning=fail") {
		t.Errorf("자체시험 기록 = %+v", got[0])
	}
}

func TestRunChecksRecordsIntegrityFailureWhenBinaryChanged(t *testing.T) {
	binary, configPath := installed(t)
	if err := os.WriteFile(binary, []byte("tampered"), 0o755); err != nil {
		t.Fatal(err)
	}
	l := audit.NewLog(10)

	runChecks(selfcheck.Inputs{ConfigPath: configPath}, binary, l, quietLogger())

	integrity := recorded(t, l)[1]
	if integrity.Result != audit.Failure || !strings.Contains(integrity.Detail, "binary_integrity=fail") {
		t.Fatalf("바뀐 바이너리를 감사기록에 실패로 남겨야 한다: %+v", integrity)
	}
}

func TestSecurityCheckCommandReturnsFalseOnFailureAndPrintsEachItem(t *testing.T) {
	binary, configPath := installed(t)
	var out bytes.Buffer

	ok := runSecurityCheck(&out, config.Config{BaseURL: "https://x"}, configPath, binary)

	if ok {
		t.Fatal("고정 항목이 실패하므로 false 여야 한다(종료 코드 1)")
	}
	for _, name := range []string{"server_pinning", "config_permission", "sensors", "binary_integrity", "config_integrity"} {
		if !strings.Contains(out.String(), name) {
			t.Errorf("출력에 %s 가 없다:\n%s", name, out.String())
		}
	}
}

func TestRecordBaselineCommandWritesBaselineNextToConfig(t *testing.T) {
	dir := t.TempDir()
	binary := filepath.Join(dir, "edrdog-agent")
	configPath := filepath.Join(dir, "config.json")
	_ = os.WriteFile(binary, []byte("agent"), 0o755)
	_ = os.WriteFile(configPath, []byte(`{}`), 0o600)
	var out bytes.Buffer

	if err := recordBaseline(&out, configPath, binary); err != nil {
		t.Fatalf("recordBaseline: %v", err)
	}
	if _, err := os.Stat(selfcheck.BaselinePath(configPath)); err != nil {
		t.Fatalf("기준값 파일이 없다: %v", err)
	}
}
