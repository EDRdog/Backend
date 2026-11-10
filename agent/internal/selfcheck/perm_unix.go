//go:build !windows

package selfcheck

import (
	"fmt"
	"os"
)

// permResult 는 파일 권한에 forbidden 비트가 하나라도 켜져 있으면 실패로 본다.
// 경로가 비면(ca_cert_path 를 안 쓰는 경우) 볼 것이 없어 건너뛴다. 그 자체의 문제는 server_pinning 이 잡는다.
func permResult(name, path string, forbidden os.FileMode) Result {
	if path == "" {
		return Result{Name: name, Status: StatusSkip, Detail: "경로가 없다"}
	}
	info, err := os.Stat(path)
	if err != nil {
		return Result{Name: name, Status: StatusFail, Detail: err.Error()}
	}
	if perm := info.Mode().Perm(); perm&forbidden != 0 {
		return Result{Name: name, Status: StatusFail, Detail: fmt.Sprintf("권한 %04o 는 다른 사용자에게 열려 있다", perm)}
	}
	return Result{Name: name, Status: StatusOK}
}
