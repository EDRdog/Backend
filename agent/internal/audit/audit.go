// Package audit 는 에이전트의 감사기록(KISA BF-13)을 모아 서버로 보낸다.
// 이벤트(event 패키지)와 따로 둔다. 감사기록은 양이 적고 잃으면 안 되며, 서버에서 가는 곳도 다르다.
package audit

import (
	"context"
	"sync"
	"time"
)

// 감사 사건 종류. 서버가 그대로 저장하므로 이름을 바꾸면 옛 기록과 갈린다.
const (
	TypeAgentStart     = "agent_start"
	TypeAgentStop      = "agent_stop"
	TypeAgentEnroll    = "agent_enroll"
	TypeSensorStop     = "sensor_stop"
	TypeSecurityCheck  = "security_check"
	TypeIntegrityCheck = "integrity_check"
	TypeCommandResult  = "command_result"
)

// 사건 결과.
const (
	Success = "success"
	Failure = "failure"
)

// MaxBatch 는 한 요청에 담는 기록 수다. collector 의 상한(100)과 같아야 한다.
// 넘기면 서버가 요청을 거절하고, 거절된 배치는 되돌아와 같은 이유로 또 거절된다.
const MaxBatch = 100

// Event 는 감사기록 한 건이다. 서버가 node_key 로 tenant 와 host 를 붙이므로 여기에는 없다.
type Event struct {
	TS     int64  `json:"ts"` // epoch millis
	Type   string `json:"type"`
	Result string `json:"result"`
	Detail string `json:"detail"`
}

// Sender 는 감사기록을 서버로 보낸다. transport.Client 가 이걸 만족한다.
type Sender interface {
	SendAudit(ctx context.Context, events []Event) error
}

// Log 는 보내기 전의 감사기록을 담는다. 용량을 넘으면 가장 오래된 것부터 버린다.
// 여러 고루틴에서 동시에 불려도 안전하다.
type Log struct {
	mu       sync.Mutex
	items    []Event
	capacity int
	dropped  int
}

// NewLog 는 최대 capacity 건을 담는 로그를 만든다.
func NewLog(capacity int) *Log {
	if capacity < 1 {
		capacity = 1
	}
	return &Log{capacity: capacity}
}

// Record 는 지금 시각으로 사건 한 건을 남긴다. detail 에 비밀값을 넣지 마라.
func (l *Log) Record(typ, result, detail string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.items = append(l.items, Event{TS: time.Now().UnixMilli(), Type: typ, Result: result, Detail: detail})
	l.trimLocked()
}

// Flush 는 쌓인 기록을 MaxBatch 건씩 보낸다. 실패한 배치는 맨 앞으로 되돌리고 오류를 올린다.
// 되돌리지 않으면 서버가 잠깐 죽은 사이의 기록이 사라진다.
func (l *Log) Flush(ctx context.Context, s Sender) error {
	for {
		batch := l.drain(MaxBatch)
		if len(batch) == 0 {
			return nil
		}
		if err := s.SendAudit(ctx, batch); err != nil {
			l.requeue(batch)
			return err
		}
	}
}

// Len 은 아직 못 보낸 건수다.
func (l *Log) Len() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.items)
}

// Dropped 는 용량 초과로 버린 누적 건수다.
func (l *Log) Dropped() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.dropped
}

func (l *Log) drain(max int) []Event {
	l.mu.Lock()
	defer l.mu.Unlock()
	if max > len(l.items) {
		max = len(l.items)
	}
	batch := append([]Event(nil), l.items[:max]...)
	l.items = append(l.items[:0], l.items[max:]...)
	return batch
}

func (l *Log) requeue(batch []Event) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.items = append(append(make([]Event, 0, len(batch)+len(l.items)), batch...), l.items...)
	l.trimLocked()
}

func (l *Log) trimLocked() {
	if overflow := len(l.items) - l.capacity; overflow > 0 {
		l.items = append(l.items[:0], l.items[overflow:]...)
		l.dropped += overflow
	}
}
