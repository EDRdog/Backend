package transport

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/event"
)

type recorded struct {
	path     string
	nodeKey  string
	body     map[string]any
	encoding string // Content-Encoding 헤더. 압축했는지 확인한다
	wire     int    // 실제로 네트워크를 탄 바이트
}

// stub 은 docs/agent-protocol.md 의 서버 쪽을 흉내낸다.
type stub struct {
	server    *httptest.Server
	mu        sync.Mutex
	got       []recorded
	nodeKey   string
	commands  []Command
	eventFail int
}

func newStub(t *testing.T) *stub {
	t.Helper()
	s := &stub{nodeKey: "key-1"}
	mux := http.NewServeMux()

	mux.HandleFunc("/api/agent/enroll", func(w http.ResponseWriter, r *http.Request) {
		body := s.record(t, r)
		if body["enroll_secret"] != "secret" {
			http.Error(w, `{"error":"invalid_enroll_secret"}`, http.StatusUnauthorized)
			return
		}
		s.mu.Lock()
		key := s.nodeKey
		s.mu.Unlock()
		writeJSON(w, map[string]any{"node_key": key})
	})

	mux.HandleFunc("/api/agent/heartbeat", func(w http.ResponseWriter, r *http.Request) {
		if !s.authed(t, r, w) {
			return
		}
		writeJSON(w, map[string]any{
			"config": map[string]any{
				"sensors":                map[string]any{"process": true, "network": false},
				"watch_paths":            []string{"/Library/LaunchDaemons"},
				"flush_interval_seconds": 7,
			},
			"commands": s.commands,
		})
	})

	mux.HandleFunc("/api/agent/events", func(w http.ResponseWriter, r *http.Request) {
		if !s.authed(t, r, w) {
			return
		}
		if s.eventFail > 0 {
			s.eventFail--
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		writeJSON(w, map[string]any{"accepted": 1})
	})

	mux.HandleFunc("/api/agent/command-result", func(w http.ResponseWriter, r *http.Request) {
		if !s.authed(t, r, w) {
			return
		}
		w.WriteHeader(http.StatusOK)
	})

	s.server = httptest.NewServer(mux)
	t.Cleanup(s.server.Close)
	return s
}

// rotate 는 서버가 발급 키를 갈아치운 상황을 만든다. 이전 키로 오는 요청은 이제 401 이다.
func (s *stub) rotate(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.nodeKey = key
}

func (s *stub) authed(t *testing.T, r *http.Request, w http.ResponseWriter) bool {
	t.Helper()
	s.record(t, r)
	s.mu.Lock()
	want := s.nodeKey
	s.mu.Unlock()
	if r.Header.Get("X-Node-Key") != want {
		w.WriteHeader(http.StatusUnauthorized)
		return false
	}
	return true
}

// record 는 collector 의 해제 필터와 같은 판단을 한다. 헤더가 있으면 풀고, 없으면 그대로 읽는다.
func (s *stub) record(t *testing.T, r *http.Request) map[string]any {
	t.Helper()
	raw, _ := io.ReadAll(r.Body)
	encoding := r.Header.Get("Content-Encoding")
	body := decodeBody(t, raw, encoding)
	s.mu.Lock()
	defer s.mu.Unlock()
	s.got = append(s.got, recorded{
		path:     r.URL.Path,
		nodeKey:  r.Header.Get("X-Node-Key"),
		body:     body,
		encoding: encoding,
		wire:     len(raw),
	})
	return body
}

func decodeBody(t *testing.T, raw []byte, encoding string) map[string]any {
	t.Helper()
	var body map[string]any
	if encoding != "gzip" {
		_ = json.Unmarshal(raw, &body)
		return body
	}
	gz, err := gzip.NewReader(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("gzip 해제 실패: %v", err)
	}
	defer gz.Close()
	plain, err := io.ReadAll(gz)
	if err != nil {
		t.Fatalf("gzip 읽기 실패: %v", err)
	}
	if err := json.Unmarshal(plain, &body); err != nil {
		t.Fatalf("푼 본문이 JSON 이 아니다: %v", err)
	}
	return body
}

func (s *stub) calls(path string) []recorded {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []recorded
	for _, r := range s.got {
		if r.path == path {
			out = append(out, r)
		}
	}
	return out
}

func writeJSON(w http.ResponseWriter, body map[string]any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(body)
}

func newClient(s *stub) *Client {
	return NewClient(Config{
		BaseURL:      s.server.URL,
		EnrollSecret: "secret",
		HostID:       "mac-1",
		Platform:     "darwin",
		AgentVersion: "0.1.0",
		Timeout:      2 * time.Second,
	}, s.server.Client())
}

func enrolled(t *testing.T, s *stub) *Client {
	t.Helper()
	c := newClient(s)
	if err := c.Enroll(context.Background()); err != nil {
		t.Fatalf("Enroll: %v", err)
	}
	return c
}

func TestEnrollSendsContractFields(t *testing.T) {
	s := newStub(t)
	c := enrolled(t, s)

	body := s.calls("/api/agent/enroll")[0].body
	for key, want := range map[string]any{
		"enroll_secret":   "secret",
		"host_identifier": "mac-1",
		// 서버는 이 값에 windows 가 들어있는지로 갈랐다. GOOS 를 그대로 보낸다.
		"platform":      "darwin",
		"agent_version": "0.1.0",
	} {
		if body[key] != want {
			t.Errorf("enroll 본문 %q = %v, want %v", key, body[key], want)
		}
	}
	if c.NodeKey() != "key-1" {
		t.Errorf("NodeKey = %q, want key-1", c.NodeKey())
	}
}

func TestEnrollRejectsWrongSecret(t *testing.T) {
	s := newStub(t)
	c := NewClient(Config{BaseURL: s.server.URL, EnrollSecret: "wrong", HostID: "mac-1"}, s.server.Client())

	if err := c.Enroll(context.Background()); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("err = %v, want ErrUnauthorized", err)
	}
}

func TestHeartbeatReturnsConfigAndCommands(t *testing.T) {
	s := newStub(t)
	s.commands = []Command{{ID: "c1", Type: CommandKillProcess, Target: "/tmp/evil.sh"}}
	c := enrolled(t, s)

	hb, err := c.Heartbeat(context.Background())
	if err != nil {
		t.Fatalf("Heartbeat: %v", err)
	}

	if hb.Config.FlushIntervalSeconds != 7 {
		t.Errorf("flush = %d, want 7", hb.Config.FlushIntervalSeconds)
	}
	if len(hb.Config.WatchPaths) != 1 || hb.Config.WatchPaths[0] != "/Library/LaunchDaemons" {
		t.Errorf("watch_paths = %v", hb.Config.WatchPaths)
	}
	if len(hb.Commands) != 1 || hb.Commands[0].Target != "/tmp/evil.sh" {
		t.Fatalf("commands = %+v", hb.Commands)
	}
	// node_key 는 본문이 아니라 헤더로 간다.
	if s.calls("/api/agent/heartbeat")[0].nodeKey != "key-1" {
		t.Error("X-Node-Key 헤더가 안 실렸다")
	}
}

func TestServerConfigEnabled(t *testing.T) {
	cfg := ServerConfig{Sensors: map[string]bool{"process": true, "network": false}}

	if !cfg.Enabled("process") {
		t.Error("process 가 켜져 있어야 한다")
	}
	if cfg.Enabled("network") {
		t.Error("network 는 꺼져 있어야 한다")
	}
	// 서버가 언급하지 않은 센서는 켠 것으로 본다. 설정 누락으로 수집이 조용히 멈추면 안 된다.
	if !cfg.Enabled("file") {
		t.Error("언급 없는 센서는 켜져 있어야 한다")
	}
	if !(ServerConfig{}).Enabled("process") {
		t.Error("설정이 아예 없으면 전부 켜져 있어야 한다")
	}
}

func TestSendEventsPostsEnvelope(t *testing.T) {
	s := newStub(t)
	c := enrolled(t, s)

	f := event.Factory{Host: "mac-1"}
	e := f.Process(time.Unix(1, 0), event.ProcessInfo{Path: "/bin/sh", Cmdline: "sh", Parent: "bash"})
	err := c.SendEvents(context.Background(), []event.Event{e})
	if err != nil {
		t.Fatalf("SendEvents: %v", err)
	}

	body := s.calls("/api/agent/events")[0].body
	data, ok := body["events"].([]any)
	if !ok || len(data) != 1 {
		t.Fatalf("events = %#v", body["events"])
	}
}

// 에이전트 → collector 는 고객사 네트워크를 타는 유일한 구간이라 여기만 압축이 이득이다(#183).
// 이벤트 배열은 같은 필드명이 레코드마다 반복돼 압축률이 높다.
func TestSendEventsCompressesLargeBatch(t *testing.T) {
	s := newStub(t)
	c := enrolled(t, s)

	f := event.Factory{Host: "mac-1"}
	var batch []event.Event
	for i := 0; i < 50; i++ {
		batch = append(batch, f.Process(time.Unix(1, 0), event.ProcessInfo{
			Path: "/bin/sh", Cmdline: "sh -c whoami", Parent: "bash",
		}))
	}
	if err := c.SendEvents(context.Background(), batch); err != nil {
		t.Fatalf("SendEvents: %v", err)
	}

	got := s.calls("/api/agent/events")[0]
	if got.encoding != "gzip" {
		t.Fatalf("Content-Encoding = %q, gzip 이어야 한다", got.encoding)
	}
	data, ok := got.body["events"].([]any)
	if !ok || len(data) != len(batch) {
		t.Fatalf("푼 본문의 events = %#v", got.body["events"])
	}
	raw, err := json.Marshal(map[string]any{"events": batch})
	if err != nil {
		t.Fatalf("Marshal: %v", err)
	}
	if got.wire >= len(raw) {
		t.Fatalf("압축 후 %d 바이트, 압축 전 %d 바이트. 줄지 않았다", got.wire, len(raw))
	}
}

// gzip 헤더·트레일러만 18바이트다. 수백 바이트짜리 enroll·heartbeat 는 압축하면 오히려 커진다.
func TestSmallRequestsAreNotCompressed(t *testing.T) {
	s := newStub(t)
	c := enrolled(t, s)

	if _, err := c.Heartbeat(context.Background()); err != nil {
		t.Fatalf("Heartbeat: %v", err)
	}

	for _, path := range []string{"/api/agent/enroll", "/api/agent/heartbeat"} {
		for _, got := range s.calls(path) {
			if got.encoding != "" {
				t.Fatalf("%s Content-Encoding = %q, 압축하지 않아야 한다", path, got.encoding)
			}
		}
	}
}

// 서버는 압축 안 된 요청도 그대로 받는다. 구버전 에이전트가 계속 그렇게 보내기 때문이다.
func TestSendEventsSkipsCompressionForSmallBatch(t *testing.T) {
	s := newStub(t)
	c := enrolled(t, s)

	f := event.Factory{Host: "mac-1"}
	e := f.Process(time.Unix(1, 0), event.ProcessInfo{Path: "/bin/sh", Cmdline: "sh"})
	if err := c.SendEvents(context.Background(), []event.Event{e}); err != nil {
		t.Fatalf("SendEvents: %v", err)
	}

	got := s.calls("/api/agent/events")[0]
	if got.encoding != "" {
		t.Fatalf("Content-Encoding = %q, 한 건짜리 배치는 압축하지 않아야 한다", got.encoding)
	}
}

func TestSendEventsSkipsEmptyBatch(t *testing.T) {
	s := newStub(t)
	c := enrolled(t, s)

	if err := c.SendEvents(context.Background(), nil); err != nil {
		t.Fatalf("SendEvents: %v", err)
	}
	if n := len(s.calls("/api/agent/events")); n != 0 {
		t.Errorf("빈 배치로 %d 회 호출, want 0", n)
	}
}

func TestSendEventsFailsOnServerError(t *testing.T) {
	// 5xx 를 조용히 삼키면 호출자가 배치를 되돌리지 못해 이벤트가 사라진다.
	s := newStub(t)
	c := enrolled(t, s)
	s.eventFail = 1

	if err := c.SendEvents(context.Background(), []event.Event{{Type: event.TypeProcess}}); err == nil {
		t.Fatal("5xx 인데 err 가 nil 이다")
	}
}

func TestReEnrollsOnUnauthorized(t *testing.T) {
	// 서버가 재시작해 키를 갈아치워도 사람이 손대지 않고 복구되어야 한다.
	s := newStub(t)
	c := enrolled(t, s)
	s.nodeKey = "key-2"

	if err := c.SendEvents(context.Background(), []event.Event{{Type: event.TypeProcess}}); err != nil {
		t.Fatalf("SendEvents: %v", err)
	}

	if c.NodeKey() != "key-2" {
		t.Errorf("NodeKey = %q, want key-2", c.NodeKey())
	}
	calls := s.calls("/api/agent/events")
	if len(calls) != 2 {
		t.Fatalf("events 호출 %d 회, want 2 (거부 1 + 재등록 후 1)", len(calls))
	}
	if calls[1].nodeKey != "key-2" {
		t.Errorf("재시도 node_key = %q, want key-2", calls[1].nodeKey)
	}
}

func TestReportCommandSendsResult(t *testing.T) {
	s := newStub(t)
	c := enrolled(t, s)

	err := c.ReportCommand(context.Background(), CommandResult{
		CommandID: "c1", Status: StatusKilled, Message: "pid 42 종료",
	})
	if err != nil {
		t.Fatalf("ReportCommand: %v", err)
	}

	body := s.calls("/api/agent/command-result")[0].body
	if body["command_id"] != "c1" || body["status"] != StatusKilled {
		t.Errorf("결과 본문 = %v", body)
	}
}

// 고루틴 둘이 같은 401 을 보고 각자 재등록하면 서로의 node_key 를 죽여 핑퐁이 된다(#281).
// 서버가 키를 갈아치운 뒤 두 요청을 동시에 태워, 재등록이 한 번만 나가는지 본다.
func TestConcurrentUnauthorizedEnrollsOnce(t *testing.T) {
	s := newStub(t)
	c := enrolled(t, s)
	s.rotate("key-2")

	start := make(chan struct{})
	var wg sync.WaitGroup
	errs := make([]error, 2)
	wg.Add(2)
	go func() {
		defer wg.Done()
		<-start
		_, errs[0] = c.Heartbeat(context.Background())
	}()
	go func() {
		defer wg.Done()
		<-start
		errs[1] = c.ReportCommand(context.Background(), CommandResult{CommandID: "c1", Status: StatusKilled})
	}()
	close(start)
	wg.Wait()

	for i, err := range errs {
		if err != nil {
			t.Errorf("요청 %d: %v", i, err)
		}
	}
	// 최초 1회 + 재등록 1회. 3회면 둘이 각자 등록해 서로의 키를 죽인 것이다.
	if n := len(s.calls("/api/agent/enroll")); n != 2 {
		t.Errorf("enroll 호출 = %d, want 2", n)
	}
	if c.NodeKey() != "key-2" {
		t.Errorf("NodeKey = %q, want key-2", c.NodeKey())
	}
}

// 업링크에 얼마나 쓰는지는 서버가 알 수 없다. 에이전트가 자기 시계로 잰 값을 다음 전송에 실어 보낸다(#181).
func TestSendEventsReportsPreviousRTT(t *testing.T) {
	s := newStub(t)
	c := enrolled(t, s)
	batch := []event.Event{{Type: event.TypeProcess}}

	for i := 0; i < 2; i++ {
		if err := c.SendEvents(context.Background(), batch); err != nil {
			t.Fatalf("SendEvents %d: %v", i, err)
		}
	}

	calls := s.calls("/api/agent/events")
	// 첫 전송은 실을 값이 없다. 0 을 보내면 왕복이 0ms 였다는 뜻이 돼 지표가 왜곡된다.
	if v, ok := calls[0].body["prev_send_us"]; ok {
		t.Errorf("첫 전송에 prev_send_us = %v, want 없음", v)
	}
	v, ok := calls[1].body["prev_send_us"].(float64)
	if !ok {
		t.Fatalf("두 번째 전송 prev_send_us = %#v", calls[1].body["prev_send_us"])
	}
	if v < 0 {
		t.Errorf("prev_send_us = %v, 음수다", v)
	}
}
