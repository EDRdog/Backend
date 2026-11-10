package selfcheck

import (
	"os"
	"path/filepath"
	"testing"
)

// install 은 바이너리와 설정 파일을 만들고 기준값을 기록한다. 설치 스크립트가 하는 일과 같다.
func install(t *testing.T) (binary, config string) {
	t.Helper()
	dir := t.TempDir()
	binary = filepath.Join(dir, "edrdog-agent")
	config = filepath.Join(dir, "config.json")
	if err := os.WriteFile(binary, []byte("agent v1"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(config, []byte(`{"base_url":"https://x"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := RecordBaseline(binary, config); err != nil {
		t.Fatalf("RecordBaseline: %v", err)
	}
	return binary, config
}

func TestIntegrityPassesRightAfterBaseline(t *testing.T) {
	binary, config := install(t)
	results := CheckIntegrity(binary, config)
	for _, name := range []string{"binary_integrity", "config_integrity"} {
		if got := find(t, results, name); got.Status != StatusOK {
			t.Fatalf("기준값을 막 기록했는데 통과하지 못했다: %+v", got)
		}
	}
}

func TestIntegrityOKDetailCarriesHashForServerSideComparison(t *testing.T) {
	binary, config := install(t)
	got := find(t, CheckIntegrity(binary, config), "binary_integrity")
	// 서버에 남는 이 값을 릴리스 .sha256 과 대조할 수 있어야 한다.
	if len(got.Detail) != len("sha256=")+64 {
		t.Fatalf("detail 에 sha256=<64자> 가 있어야 한다: %q", got.Detail)
	}
}

func TestIntegrityFailsWhenBinaryChanged(t *testing.T) {
	binary, config := install(t)
	if err := os.WriteFile(binary, []byte("agent v1 + backdoor"), 0o755); err != nil {
		t.Fatal(err)
	}
	results := CheckIntegrity(binary, config)
	if got := find(t, results, "binary_integrity"); got.Status != StatusFail {
		t.Fatalf("바이너리가 바뀌었는데 통과했다: %+v", got)
	}
	if got := find(t, results, "config_integrity"); got.Status != StatusOK {
		t.Fatalf("설정은 그대로인데 실패했다: %+v", got)
	}
}

func TestIntegrityFailsWhenConfigChanged(t *testing.T) {
	binary, config := install(t)
	if err := os.WriteFile(config, []byte(`{"base_url":"https://evil"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	results := CheckIntegrity(binary, config)
	if got := find(t, results, "config_integrity"); got.Status != StatusFail {
		t.Fatalf("설정이 바뀌었는데 통과했다: %+v", got)
	}
	if got := find(t, results, "binary_integrity"); got.Status != StatusOK {
		t.Fatalf("바이너리는 그대로인데 실패했다: %+v", got)
	}
}

// 기준값을 지우면 비교할 것이 없다. 통과로 두면 지우는 것이 우회가 된다.
func TestIntegrityFailsWhenBaselineMissing(t *testing.T) {
	binary, config := install(t)
	if err := os.Remove(BaselinePath(config)); err != nil {
		t.Fatal(err)
	}
	for _, r := range CheckIntegrity(binary, config) {
		if r.Status != StatusFail {
			t.Fatalf("기준값이 없으면 둘 다 실패: %+v", r)
		}
	}
}

func TestIntegrityFailsWhenBaselineCorrupt(t *testing.T) {
	binary, config := install(t)
	if err := os.WriteFile(BaselinePath(config), []byte("not json"), 0o644); err != nil {
		t.Fatal(err)
	}
	for _, r := range CheckIntegrity(binary, config) {
		if r.Status != StatusFail {
			t.Fatalf("기준값이 깨졌으면 둘 다 실패: %+v", r)
		}
	}
}

func TestIntegrityFailsWhenBinaryMissing(t *testing.T) {
	binary, config := install(t)
	if err := os.Remove(binary); err != nil {
		t.Fatal(err)
	}
	if got := find(t, CheckIntegrity(binary, config), "binary_integrity"); got.Status != StatusFail {
		t.Fatalf("바이너리를 못 읽으면 실패: %+v", got)
	}
}

func TestBaselineSitsNextToConfig(t *testing.T) {
	if got := BaselinePath(filepath.Join("etc", "edrdog", "config.json")); got != filepath.Join("etc", "edrdog", "integrity.json") {
		t.Fatalf("기준값은 설정 파일 옆에 둔다: %s", got)
	}
}
