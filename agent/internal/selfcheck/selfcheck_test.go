package selfcheck

import (
	"crypto/tls"
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/config"
)

// writeCACert 는 테스트 서버의 실제 인증서를 PEM 파일로 쓴다. 고정이 읽을 수 있는 유효한 CA 파일이 필요하다.
func writeCACert(t *testing.T, dir string) string {
	t.Helper()
	srv := httptest.NewUnstartedServer(http.NotFoundHandler())
	srv.TLS = &tls.Config{}
	srv.StartTLS()
	t.Cleanup(srv.Close)
	path := filepath.Join(dir, "server.pem")
	pemBytes := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: srv.Certificate().Raw})
	if err := os.WriteFile(path, pemBytes, 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func find(t *testing.T, results []Result, name string) Result {
	t.Helper()
	for _, r := range results {
		if r.Name == name {
			return r
		}
	}
	t.Fatalf("%s 항목이 없다: %+v", name, results)
	return Result{}
}

func TestPinningPassesWithHTTPSAndLoadableCA(t *testing.T) {
	dir := t.TempDir()
	cfg := config.Config{BaseURL: "https://edr.example.com", CACertPath: writeCACert(t, dir)}
	got := find(t, Run(Inputs{Config: cfg}), "server_pinning")
	if got.Status != StatusOK {
		t.Fatalf("고정이 설정됐는데 통과하지 못했다: %+v", got)
	}
}

func TestPinningFailsWithoutCACert(t *testing.T) {
	cfg := config.Config{BaseURL: "https://edr.example.com"}
	got := find(t, Run(Inputs{Config: cfg}), "server_pinning")
	if got.Status != StatusFail {
		t.Fatalf("ca_cert_path 가 비면 시스템 저장소를 쓰므로 고정이 아니다: %+v", got)
	}
}

func TestPinningFailsOnPlainHTTP(t *testing.T) {
	dir := t.TempDir()
	cfg := config.Config{BaseURL: "http://edr.example.com", CACertPath: writeCACert(t, dir)}
	if got := find(t, Run(Inputs{Config: cfg}), "server_pinning"); got.Status != StatusFail {
		t.Fatalf("평문 HTTP 는 통과하면 안 된다: %+v", got)
	}
}

func TestPinningFailsWhenCACertUnreadable(t *testing.T) {
	cfg := config.Config{BaseURL: "https://edr.example.com", CACertPath: filepath.Join(t.TempDir(), "없는파일.pem")}
	if got := find(t, Run(Inputs{Config: cfg}), "server_pinning"); got.Status != StatusFail {
		t.Fatalf("CA 를 못 읽으면 실패여야 한다: %+v", got)
	}
}

func TestSensorsSkippedBeforeStart(t *testing.T) {
	got := find(t, Run(Inputs{}), "sensors")
	if got.Status != StatusSkip {
		t.Fatalf("센서를 띄우기 전에는 건너뛴다: %+v", got)
	}
}

func TestSensorsFailWhenAnyStopped(t *testing.T) {
	in := Inputs{Sensors: func() (int, []string) { return 3, []string{"l7"} }}
	got := find(t, Run(in), "sensors")
	if got.Status != StatusFail || !strings.Contains(got.Detail, "l7") {
		t.Fatalf("멈춘 센서 이름이 실패 사유에 있어야 한다: %+v", got)
	}
}

func TestSensorsPassWhenAllRunning(t *testing.T) {
	in := Inputs{Sensors: func() (int, []string) { return 2, nil }}
	if got := find(t, Run(in), "sensors"); got.Status != StatusOK {
		t.Fatalf("전부 살아 있으면 통과: %+v", got)
	}
}

func TestSummarizeFailsWhenAnyItemFails(t *testing.T) {
	ok, detail := Summarize([]Result{
		{Name: "a", Status: StatusOK},
		{Name: "b", Status: StatusFail, Detail: "이유"},
		{Name: "c", Status: StatusSkip, Detail: "건너뜀"},
	})
	if ok {
		t.Fatal("실패가 하나라도 있으면 전체 실패")
	}
	for _, want := range []string{"a=ok", "b=fail(이유)", "c=skip(건너뜀)"} {
		if !strings.Contains(detail, want) {
			t.Errorf("detail 에 %q 가 없다: %s", want, detail)
		}
	}
}

func TestSummarizeSkipDoesNotFail(t *testing.T) {
	if ok, _ := Summarize([]Result{{Name: "a", Status: StatusOK}, {Name: "c", Status: StatusSkip}}); !ok {
		t.Fatal("건너뜀은 실패가 아니다")
	}
}

// 통과한 항목의 Detail(무결성의 해시)도 감사기록에 남아야 서버에서 릴리스 해시와 대조할 수 있다.
func TestSummarizeKeepsDetailOfPassedItem(t *testing.T) {
	_, detail := Summarize([]Result{{Name: "binary_integrity", Status: StatusOK, Detail: "sha256=abc"}})
	if !strings.Contains(detail, "binary_integrity=ok(sha256=abc)") {
		t.Fatalf("detail = %s", detail)
	}
}
