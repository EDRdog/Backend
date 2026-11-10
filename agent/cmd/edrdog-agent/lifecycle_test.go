package main

import (
	"compress/gzip"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// auditServer 는 collector 를 흉내 낸다. 등록과 하트비트에 답하고 감사기록 요청의 type 을 모은다.
type auditServer struct {
	mu    sync.Mutex
	types []string
}

func (a *auditServer) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/agent/enroll", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"node_key":"k"}`))
	})
	mux.HandleFunc("/api/agent/heartbeat", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"config":{},"commands":[]}`))
	})
	mux.HandleFunc("/api/agent/events", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"accepted":0}`))
	})
	mux.HandleFunc("/api/agent/audit", func(w http.ResponseWriter, r *http.Request) {
		// 1KB 가 넘는 본문은 에이전트가 gzip 으로 보낸다. collector 의 해제 필터와 같은 판단이다.
		var in io.Reader = r.Body
		if r.Header.Get("Content-Encoding") == "gzip" {
			gz, err := gzip.NewReader(r.Body)
			if err != nil {
				http.Error(w, "깨진 gzip", http.StatusBadRequest)
				return
			}
			in = gz
		}
		var body struct {
			Events []struct {
				Type   string `json:"type"`
				Result string `json:"result"`
			} `json:"events"`
		}
		_ = json.NewDecoder(in).Decode(&body)
		a.mu.Lock()
		for _, e := range body.Events {
			a.types = append(a.types, e.Type+":"+e.Result)
		}
		a.mu.Unlock()
	})
	return mux
}

func (a *auditServer) has(want string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	for _, t := range a.types {
		if t == want {
			return true
		}
	}
	return false
}

func waitUntil(t *testing.T, cond func() bool, what string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("시간 안에 %s 가 서버에 오지 않았다", what)
}

// 시작, 점검, 등록은 서버로 가고, 종료 사건은 마지막 전송으로 나가야 한다.
func TestRunAgentSendsLifecycleAuditRecords(t *testing.T) {
	srv := &auditServer{}
	ts := httptest.NewServer(srv.handler())
	t.Cleanup(ts.Close)

	dir := t.TempDir()
	configPath := filepath.Join(dir, "config.json")
	if err := os.WriteFile(configPath, []byte(`{"base_url":"`+ts.URL+`","enroll_secret":"s","flush_interval_seconds":1}`), 0o600); err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		// -selftest 센서를 쓴다. 진짜 센서는 root 와 OS 권한이 필요하다.
		done <- runAgent(ctx, options{configPath: configPath, selfTest: true, selfTestInterval: time.Hour}, quietLogger())
	}()

	waitUntil(t, func() bool { return srv.has("agent_start:success") }, "agent_start")
	waitUntil(t, func() bool { return srv.has("agent_enroll:success") }, "agent_enroll")
	// 기준값을 만들지 않았으므로 무결성 점검은 실패로 남는다. 점검이 돌아 기록이 만들어진 것을 본다.
	waitUntil(t, func() bool { return srv.has("integrity_check:failure") }, "integrity_check")
	waitUntil(t, func() bool { return srv.has("security_check:failure") }, "security_check")

	cancel()
	if err := <-done; err != nil {
		t.Fatalf("runAgent: %v", err)
	}
	if !srv.has("agent_stop:success") {
		t.Fatalf("종료 사건이 마지막 전송으로 나가지 않았다: %v", srv.types)
	}
}

func TestEveryCallsRepeatedlyAndStopsWithContext(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	calls := make(chan struct{}, 10)
	finished := make(chan struct{})
	go func() {
		every(ctx, 5*time.Millisecond, func() { calls <- struct{}{} })
		close(finished)
	}()

	for i := 0; i < 2; i++ {
		select {
		case <-calls:
		case <-time.After(2 * time.Second):
			t.Fatal("주기마다 불려야 한다")
		}
	}
	cancel()
	select {
	case <-finished:
	case <-time.After(2 * time.Second):
		t.Fatal("ctx 가 끝나면 멈춰야 한다")
	}
}
