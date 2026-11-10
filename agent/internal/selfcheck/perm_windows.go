//go:build windows

package selfcheck

import "os"

// permResult 는 Windows 에서 건너뛴다. 접근 권한이 유닉스 권한 비트가 아니라 ACL 이라 FileMode 로는 알 수 없다.
// 설치 스크립트가 설정 파일에 SYSTEM 과 Administrators 만 남기는 ACL 을 걸지만 에이전트가 그것을 다시 읽어 확인하지는 않는다.
func permResult(name, _ string, _ os.FileMode) Result {
	return Result{Name: name, Status: StatusSkip, Detail: "Windows 는 ACL 을 읽지 않는다"}
}
