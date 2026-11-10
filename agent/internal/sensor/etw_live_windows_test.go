//go:build windows && etwlive

package sensor

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/event"
	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/packet"
)

// TestETWLive 는 실제 Windows 에서 ETW 센서와 pktmon 을 열어 다섯 종이 오는지, 곧 끝나는 프로세스의
// 명령행이 실리는지 본다. 관리자 권한이 필요하다. 서버는 쓰지 않는다. go test -tags etwlive 로만 돈다.
func TestETWLive(t *testing.T) {
	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	startup := `C:\ProgramData\Microsoft\Windows\Start Menu\Programs\StartUp`
	factory := event.Factory{Host: "ci"}
	etwSensor := &ETWSensor{Factory: factory, WatchPaths: []string{startup}, Logger: log, Hasher: NewFileHasher()}
	capture, err := OpenPktMonCapture(log)
	if err != nil {
		t.Fatalf("pktmon: %v", err)
	}
	flows := NewFlowOwners()
	etwSensor.PktMon, etwSensor.Flows = capture, flows
	l7 := &L7Sensor{Factory: factory, Source: capture, Owner: flows, LinkType: packet.LinkEthernet, Logger: log}

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	out := make(chan event.Event, 100000)
	go func() { t.Logf("etw 종료: %v", etwSensor.Run(ctx, out)) }()
	go func() { t.Logf("l7 종료: %v", l7.Run(ctx, out)) }()
	time.Sleep(10 * time.Second)

	// 곧 끝나는 프로세스 50개, 오래 사는 프로세스 하나, 연결과 DNS 와 TLS, 감시 경로의 파일 생성.
	for i := 0; i < 50; i++ {
		_ = exec.Command("cmd.exe", "/c", "exit", fmt.Sprintf("cimark%d", i)).Run()
	}
	_ = exec.Command("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 5; #cilong").Run()
	if resp, err := http.Get("https://example.com"); err == nil {
		resp.Body.Close()
	}
	file := filepath.Join(startup, "edrdog-ci.txt")
	_ = os.WriteFile(file, []byte("x"), 0o644)
	defer os.Remove(file)
	time.Sleep(15 * time.Second)
	cancel()
	time.Sleep(3 * time.Second)

	types := map[string]int{}
	short := map[string]bool{}
	long := false
	for len(out) > 0 {
		e := <-out
		types[e.Type]++
		if strings.Contains(e.Cmdline, "exit cimark") {
			short[e.Cmdline] = true
		}
		if strings.Contains(e.Cmdline, "#cilong") {
			long = true
		}
	}
	t.Logf("종류별=%v 짧은프로세스명령행=%d/50 오래사는프로세스명령행=%v", types, len(short), long)
	for _, typ := range []string{"script", "network", "dns", "file", "l7"} {
		if types[typ] == 0 {
			t.Errorf("%s 이벤트가 0건이다", typ)
		}
	}
	if len(short) < 45 {
		t.Errorf("짧은 프로세스 명령행이 %d/50 이다", len(short))
	}
	if !long {
		t.Error("오래 사는 프로세스 명령행이 없다")
	}
}
