// Package selfcheck 는 에이전트 보안 기능의 자체시험과 무결성 검증을 한다(KISA BF-10, BF-11).
//
// 이름 주의: main 의 -selftest 플래그(센서 대신 가짜 이벤트를 보내 서버까지의 경로를 확인한다)와
// 다른 것이다. 여기는 에이전트 자신의 보안 설정이 멀쩡한지 본다. 플래그는 -security-check 다.
package selfcheck

import (
	"fmt"
	"strings"

	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/config"
)

// 항목 결과. Skip 은 이 환경에서 확인할 수 없다는 뜻이고 실패가 아니다.
const (
	StatusOK   = "ok"
	StatusFail = "fail"
	StatusSkip = "skip"
)

// Result 는 점검 항목 하나의 결과다. Detail 에 비밀값을 넣지 마라. 서버로 간다.
type Result struct {
	Name   string
	Status string
	Detail string
}

// Inputs 는 자체시험이 보는 것이다.
type Inputs struct {
	Config     config.Config
	ConfigPath string
	// Sensors 는 (띄운 센서 수, 멈춘 센서 이름)을 돌려준다. nil 이면 아직 센서를 띄우기 전이다.
	Sensors func() (total int, stopped []string)
}

// Run 은 보안 기능 자체시험을 돌린다. 항목마다 결과를 돌려주고, 하나가 실패해도 나머지를 계속 본다.
func Run(in Inputs) []Result {
	return []Result{
		serverPinning(in.Config),
		permResult("config_permission", in.ConfigPath, 0o077),
		permResult("ca_cert_permission", in.Config.CACertPath, 0o022),
		sensors(in.Sensors),
	}
}

// Summarize 는 결과를 감사기록용으로 접는다. 실패가 하나라도 있으면 ok 는 false 다.
func Summarize(results []Result) (ok bool, detail string) {
	ok = true
	parts := make([]string, len(results))
	for i, r := range results {
		parts[i] = r.Name + "=" + r.Status
		if r.Detail != "" {
			parts[i] += "(" + r.Detail + ")"
		}
		if r.Status == StatusFail {
			ok = false
		}
	}
	return ok, strings.Join(parts, "; ")
}

// serverPinning 은 서버 인증서 고정이 설정돼 있는지 본다. 비워 두면 시스템 신뢰 저장소를 써서 고정이 아니다.
func serverPinning(cfg config.Config) Result {
	r := Result{Name: "server_pinning", Status: StatusFail}
	switch {
	case !strings.HasPrefix(cfg.BaseURL, "https://"):
		r.Detail = "base_url 이 https 가 아니다"
	case cfg.CACertPath == "":
		r.Detail = "ca_cert_path 가 비어 서버를 고정하지 않는다"
	default:
		// HTTPClient 가 CA 파일을 읽고 인증서를 하나라도 찾아야 성공한다.
		if _, err := cfg.HTTPClient(); err != nil {
			r.Detail = err.Error()
		} else {
			r.Status = StatusOK
		}
	}
	return r
}

// sensors 는 띄운 센서가 전부 살아 있는지 본다. 하나만 멈춰도 그 관측이 조용히 사라진다.
func sensors(state func() (int, []string)) Result {
	r := Result{Name: "sensors"}
	if state == nil {
		r.Status, r.Detail = StatusSkip, "센서를 띄우기 전이다"
		return r
	}
	total, stopped := state()
	if len(stopped) > 0 {
		r.Status, r.Detail = StatusFail, "멈춘 센서: "+strings.Join(stopped, ",")
		return r
	}
	r.Status, r.Detail = StatusOK, fmt.Sprintf("%d개 동작", total)
	return r
}
