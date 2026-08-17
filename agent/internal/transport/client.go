// Package transport 는 서버의 에이전트 API 에 붙는다. 계약은 docs/agent-protocol.md 다.
package transport

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/event"
)

// ErrUnauthorized 는 서버가 node_key 나 enroll_secret 을 거부했다는 뜻이다.
var ErrUnauthorized = errors.New("서버가 인증을 거부했다")

// 에이전트가 명령 실행 결과로 보고하는 상태. TIMEOUT/COOLDOWN/DISABLED 는 서버가 붙인다.
const (
	StatusKilled  = "KILLED"
	StatusNoMatch = "NO_MATCH"
	StatusFailed  = "FAILED"
)

// CommandKillProcess 는 프로세스 종료 명령이다.
const CommandKillProcess = "kill_process"

// Command 는 서버가 하트비트 응답에 실어 보내는 명령 한 건이다.
type Command struct {
	ID     string `json:"id"`
	Type   string `json:"type"`
	Target string `json:"target"`
}

// ServerConfig 는 서버가 내려주는 수집 설정이다.
type ServerConfig struct {
	Sensors              map[string]bool `json:"sensors"`
	WatchPaths           []string        `json:"watch_paths"`
	FlushIntervalSeconds int             `json:"flush_interval_seconds"`
}

// Enabled 는 센서를 켜야 하는지 알려준다.
// 언급 없는 센서를 꺼진 것으로 보면 설정 하나가 빠졌을 때 수집이 조용히 멈춘다. 끄는 것은 명시적이어야 한다.
func (c ServerConfig) Enabled(name string) bool {
	if c.Sensors == nil {
		return true
	}
	on, set := c.Sensors[name]
	return !set || on
}

// Heartbeat 는 하트비트 응답이다.
type Heartbeat struct {
	Config   ServerConfig `json:"config"`
	Commands []Command    `json:"commands"`
}

// CommandResult 는 명령 실행 결과 보고다.
type CommandResult struct {
	CommandID string `json:"command_id"`
	Status    string `json:"status"`
	Message   string `json:"message"`
}

// Config 는 서버 접속에 필요한 값이다.
type Config struct {
	BaseURL      string
	EnrollSecret string
	HostID       string
	Platform     string // Go 의 runtime.GOOS 값. darwin 또는 windows
	AgentVersion string
	Timeout      time.Duration
}

// Client 는 서버와의 왕복을 담당한다. 여러 고루틴에서 동시에 써도 안전하다.
type Client struct {
	cfg  Config
	http *http.Client

	mu      sync.RWMutex
	nodeKey string

	// 재등록 직렬화용. mu 와 따로 둔다. 등록은 왕복이 걸리는데 그동안 nodeKey 읽기를 막으면
	// 다른 고루틴이 통째로 멈춘다.
	enrollMu sync.Mutex

	// 직전 events 전송에 걸린 왕복 시간. 다음 전송에 실어 서버가 업링크를 집계하게 한다(#181).
	prevSendMu sync.Mutex
	prevSend   time.Duration
}

// NewClient 는 클라이언트를 만든다. httpClient 가 nil 이면 Timeout 을 적용한 기본값을 쓴다.
func NewClient(cfg Config, httpClient *http.Client) *Client {
	if cfg.Timeout <= 0 {
		cfg.Timeout = 10 * time.Second
	}
	if httpClient == nil {
		httpClient = &http.Client{Timeout: cfg.Timeout}
	}
	return &Client{cfg: cfg, http: httpClient}
}

// NodeKey 는 현재 보유한 node_key 다. 등록 전이면 빈 문자열이다.
func (c *Client) NodeKey() string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.nodeKey
}

// Enroll 은 enroll_secret 으로 node_key 를 받아 저장한다.
func (c *Client) Enroll(ctx context.Context) error {
	body := map[string]string{
		"enroll_secret":   c.cfg.EnrollSecret,
		"host_identifier": c.cfg.HostID,
		"platform":        c.cfg.Platform,
		"agent_version":   c.cfg.AgentVersion,
	}
	var res struct {
		NodeKey string `json:"node_key"`
	}
	if err := c.post(ctx, "/api/agent/enroll", "", body, &res); err != nil {
		return err
	}
	if res.NodeKey == "" {
		return fmt.Errorf("등록 응답에 node_key 가 없다")
	}
	c.mu.Lock()
	c.nodeKey = res.NodeKey
	c.mu.Unlock()
	return nil
}

// Heartbeat 는 수집 설정과 대기 중인 명령을 받아온다. 서버는 이 호출로 마지막 접속 시각을 갱신한다.
func (c *Client) Heartbeat(ctx context.Context) (Heartbeat, error) {
	var out Heartbeat
	err := c.authed(ctx, func(nodeKey string) error {
		return c.post(ctx, "/api/agent/heartbeat", nodeKey, struct{}{}, &out)
	})
	return out, err
}

// SendEvents 는 이벤트 배치를 보낸다.
// 실패는 그대로 올린다. 호출자가 배치를 버퍼에 되돌려야 이벤트가 사라지지 않는다.
func (c *Client) SendEvents(ctx context.Context, events []event.Event) error {
	if len(events) == 0 {
		return nil
	}
	body := struct {
		Events []event.Event `json:"events"`
		// 첫 전송은 실을 값이 없어 생략한다. 0 을 보내면 왕복이 0 이었다는 뜻이 된다.
		// ms 로 실으면 가까운 서버 상대로는 늘 0 이라 밀리초가 아니라 마이크로초다.
		PrevSendUs int64 `json:"prev_send_us,omitempty"`
	}{Events: events, PrevSendUs: c.takePrevSend()}

	start := time.Now()
	err := c.authed(ctx, func(nodeKey string) error {
		return c.post(ctx, "/api/agent/events", nodeKey, body, nil)
	})
	// 실패한 전송은 재시도·타임아웃이 섞여 업링크 지표로 못 쓴다.
	if err == nil {
		c.setPrevSend(time.Since(start))
	}
	return err
}

// takePrevSend 는 실어 보낼 직전 왕복 시간을 마이크로초로 돌려주고 비운다.
// 비우지 않으면 전송이 멎은 동안 같은 값이 계속 실려 지표가 그 값에 눌린다.
func (c *Client) takePrevSend() int64 {
	c.prevSendMu.Lock()
	defer c.prevSendMu.Unlock()
	us := c.prevSend.Microseconds()
	c.prevSend = 0
	return us
}

func (c *Client) setPrevSend(d time.Duration) {
	c.prevSendMu.Lock()
	defer c.prevSendMu.Unlock()
	c.prevSend = d
}

// ReportCommand 는 명령 실행 결과를 보고한다.
func (c *Client) ReportCommand(ctx context.Context, result CommandResult) error {
	return c.authed(ctx, func(nodeKey string) error {
		return c.post(ctx, "/api/agent/command-result", nodeKey, result, nil)
	})
}

// authed 는 요청을 실행하고, 인증이 거부되면 한 번 재등록한 뒤 다시 시도한다.
// 이 재등록이 없으면 서버가 재시작해 node_key 를 잃은 순간부터 사람이 손댈 때까지 모든 요청이 막힌다.
// 재시도는 한 번뿐이다. 반복하면 거부하는 서버에 계속 등록을 밀어 넣는다.
// do 에 쓴 키를 넘겨받아야 재등록이 필요한지 판단할 수 있다.
func (c *Client) authed(ctx context.Context, do func(nodeKey string) error) error {
	used := c.NodeKey()
	err := do(used)
	if !errors.Is(err, ErrUnauthorized) {
		return err
	}
	if err := c.reenroll(ctx, used); err != nil {
		return err
	}
	return do(c.NodeKey())
}

// reenroll 은 used 가 아직 최신 키일 때만 새로 등록한다.
// 고루틴 둘이 같은 401 을 보고 각자 등록하면, 재등록이 이전 토큰을 무효로 만들어 서로의 키를 죽인다.
// 잠그기만 하고 이 검사를 빼면 순서대로 두 번 등록해 같은 핑퐁이 난다.
func (c *Client) reenroll(ctx context.Context, used string) error {
	c.enrollMu.Lock()
	defer c.enrollMu.Unlock()
	if c.NodeKey() != used {
		return nil // 다른 고루틴이 이미 받아 왔다
	}
	return c.Enroll(ctx)
}

func (c *Client) post(ctx context.Context, path, nodeKey string, body, out any) error {
	payload, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("%s 요청 직렬화 실패: %w", path, err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.cfg.BaseURL+path, bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("%s 요청 생성 실패: %w", path, err)
	}
	req.Header.Set("Content-Type", "application/json")
	if nodeKey != "" {
		req.Header.Set("X-Node-Key", nodeKey)
	}

	res, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("%s 전송 실패: %w", path, err)
	}
	defer res.Body.Close()

	// 401 을 다른 오류와 뭉뚱그리면 authed 가 재등록을 걸지 못한다.
	if res.StatusCode == http.StatusUnauthorized {
		return ErrUnauthorized
	}
	if res.StatusCode < 200 || res.StatusCode > 299 {
		return fmt.Errorf("%s 응답 상태 %d", path, res.StatusCode)
	}
	if out == nil {
		_, _ = io.Copy(io.Discard, res.Body)
		return nil
	}
	if err := json.NewDecoder(res.Body).Decode(out); err != nil {
		return fmt.Errorf("%s 응답 해석 실패: %w", path, err)
	}
	return nil
}
