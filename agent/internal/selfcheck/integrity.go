package selfcheck

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

// BaselineFile 은 설정 파일 옆에 두는 기준값 파일 이름이다. 설치 스크립트가 -record-baseline 으로 만든다.
const BaselineFile = "integrity.json"

// Baseline 은 설치 시점의 SHA-256 이다. 정당한 교체(재설치)는 기준값도 새로 기록한다(ADR 030).
type Baseline struct {
	BinarySHA256 string `json:"binary_sha256"`
	ConfigSHA256 string `json:"config_sha256"`
}

// BaselinePath 는 설정 파일 경로에서 기준값 파일 경로를 구한다.
func BaselinePath(configPath string) string {
	return filepath.Join(filepath.Dir(configPath), BaselineFile)
}

// RecordBaseline 은 지금 바이너리와 설정 파일의 해시를 기준값으로 쓴다.
func RecordBaseline(binaryPath, configPath string) error {
	binSum, err := fileSHA256(binaryPath)
	if err != nil {
		return err
	}
	cfgSum, err := fileSHA256(configPath)
	if err != nil {
		return err
	}
	raw, err := json.MarshalIndent(Baseline{BinarySHA256: binSum, ConfigSHA256: cfgSum}, "", "  ")
	if err != nil {
		return err
	}
	// 임시 파일에 쓰고 바꿔치기한다. 쓰다 끊기면 반쪽짜리 기준값이 남아 이후 검증이 전부 실패한다.
	path := BaselinePath(configPath)
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o644); err != nil {
		return fmt.Errorf("기준값을 쓸 수 없다(%s): %w", tmp, err)
	}
	return os.Rename(tmp, path)
}

// CheckIntegrity 는 바이너리와 설정 파일을 기준값과 비교한다.
// 기준값이 없거나 깨졌으면 둘 다 실패다. 비교할 것이 없다고 통과시키면 기준값을 지우는 것이 우회가 된다.
// 통과한 항목의 Detail 에는 현재 해시를 싣는다. 서버에 남은 값을 릴리스 .sha256 과 대조할 수 있다.
func CheckIntegrity(binaryPath, configPath string) []Result {
	base, err := readBaseline(BaselinePath(configPath))
	if err != nil {
		return []Result{
			{Name: "binary_integrity", Status: StatusFail, Detail: err.Error()},
			{Name: "config_integrity", Status: StatusFail, Detail: err.Error()},
		}
	}
	return []Result{
		compare("binary_integrity", binaryPath, base.BinarySHA256),
		compare("config_integrity", configPath, base.ConfigSHA256),
	}
}

func readBaseline(path string) (Baseline, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return Baseline{}, fmt.Errorf("기준값 파일을 읽을 수 없다. 재설치하면 다시 기록된다: %w", err)
	}
	var b Baseline
	if err := json.Unmarshal(raw, &b); err != nil || b.BinarySHA256 == "" || b.ConfigSHA256 == "" {
		return Baseline{}, fmt.Errorf("기준값 파일이 깨졌다(%s)", path)
	}
	return b, nil
}

func compare(name, path, want string) Result {
	got, err := fileSHA256(path)
	if err != nil {
		return Result{Name: name, Status: StatusFail, Detail: err.Error()}
	}
	if got != want {
		return Result{Name: name, Status: StatusFail, Detail: fmt.Sprintf("기준값과 다르다 (기준 %s, 현재 %s)", want, got)}
	}
	return Result{Name: name, Status: StatusOK, Detail: "sha256=" + got}
}

// fileSHA256 은 파일을 흘려 읽어 해시한다. 에이전트 바이너리는 수십 MB 라 통째로 올리지 않는다.
func fileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", fmt.Errorf("파일을 열 수 없다(%s): %w", path, err)
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", fmt.Errorf("파일을 읽을 수 없다(%s): %w", path, err)
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
