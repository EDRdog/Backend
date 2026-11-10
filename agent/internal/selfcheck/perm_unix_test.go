//go:build !windows

package selfcheck

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/config"
)

func fileWithMode(t *testing.T, mode os.FileMode) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "f")
	if err := os.WriteFile(path, []byte("x"), mode); err != nil {
		t.Fatal(err)
	}
	// umask 가 깎았을 수 있어 다시 맞춘다.
	if err := os.Chmod(path, mode); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestConfigPermissionPassesAt0600(t *testing.T) {
	got := find(t, Run(Inputs{ConfigPath: fileWithMode(t, 0o600)}), "config_permission")
	if got.Status != StatusOK {
		t.Fatalf("0600 은 통과: %+v", got)
	}
}

func TestConfigPermissionFailsWhenOthersCanRead(t *testing.T) {
	got := find(t, Run(Inputs{ConfigPath: fileWithMode(t, 0o644)}), "config_permission")
	if got.Status != StatusFail {
		t.Fatalf("enroll_secret 이 든 파일을 다른 사용자가 읽을 수 있으면 실패: %+v", got)
	}
}

func TestConfigPermissionFailsWhenMissing(t *testing.T) {
	in := Inputs{ConfigPath: filepath.Join(t.TempDir(), "없다")}
	if got := find(t, Run(in), "config_permission"); got.Status != StatusFail {
		t.Fatalf("파일이 없으면 실패: %+v", got)
	}
}

// 고정 인증서를 다른 사용자가 바꿀 수 있으면 고정이 무의미하다. 읽기는 열려 있어도 된다(공개 인증서).
func TestCACertPermissionFailsWhenOthersCanWrite(t *testing.T) {
	cfg := config.Config{CACertPath: fileWithMode(t, 0o666)}
	if got := find(t, Run(Inputs{Config: cfg}), "ca_cert_permission"); got.Status != StatusFail {
		t.Fatalf("0666 은 실패: %+v", got)
	}
}

func TestCACertPermissionPassesAt0644(t *testing.T) {
	cfg := config.Config{CACertPath: fileWithMode(t, 0o644)}
	if got := find(t, Run(Inputs{Config: cfg}), "ca_cert_permission"); got.Status != StatusOK {
		t.Fatalf("0644 는 통과: %+v", got)
	}
}
