package audit

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
)

// fakeSender 는 보낸 배치를 모아 두고, fail 이 켜져 있으면 실패한다.
type fakeSender struct {
	batches [][]Event
	fail    bool
}

func (f *fakeSender) SendAudit(_ context.Context, events []Event) error {
	if f.fail {
		return errors.New("서버 없음")
	}
	f.batches = append(f.batches, events)
	return nil
}

func TestEventSerializesToServerContract(t *testing.T) {
	l := NewLog(10)
	l.Record(TypeAgentStart, Success, "v0.1.0")
	s := &fakeSender{}
	if err := l.Flush(context.Background(), s); err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(s.batches[0][0])
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if got["type"] != "agent_start" || got["result"] != "success" || got["detail"] != "v0.1.0" {
		t.Fatalf("계약과 다른 모양: %s", raw)
	}
	// ts 는 epoch millis 정수여야 한다.
	if ts, ok := got["ts"].(float64); !ok || ts < 1e12 {
		t.Fatalf("ts 가 epoch millis 가 아니다: %v", got["ts"])
	}
}

func TestFlushClearsLogOnSuccess(t *testing.T) {
	l := NewLog(10)
	l.Record(TypeAgentStart, Success, "")
	s := &fakeSender{}
	if err := l.Flush(context.Background(), s); err != nil {
		t.Fatal(err)
	}
	if l.Len() != 0 {
		t.Fatalf("보낸 기록이 남아 있다: %d", l.Len())
	}
}

func TestFlushKeepsRecordsInOrderWhenSendFails(t *testing.T) {
	l := NewLog(10)
	l.Record(TypeAgentStart, Success, "a")
	l.Record(TypeIntegrityCheck, Failure, "b")
	s := &fakeSender{fail: true}
	if err := l.Flush(context.Background(), s); err == nil {
		t.Fatal("전송 실패가 올라와야 한다")
	}
	if l.Len() != 2 {
		t.Fatalf("실패한 기록을 되돌려야 한다: %d", l.Len())
	}
	// 서버가 돌아오면 같은 순서로 나간다.
	s.fail = false
	if err := l.Flush(context.Background(), s); err != nil {
		t.Fatal(err)
	}
	if got := s.batches[0]; got[0].Detail != "a" || got[1].Detail != "b" {
		t.Fatalf("순서가 바뀌었다: %+v", got)
	}
}

func TestLogDropsOldestWhenFull(t *testing.T) {
	l := NewLog(2)
	l.Record(TypeAgentStart, Success, "1")
	l.Record(TypeAgentStart, Success, "2")
	l.Record(TypeAgentStart, Success, "3")
	s := &fakeSender{}
	if err := l.Flush(context.Background(), s); err != nil {
		t.Fatal(err)
	}
	if got := s.batches[0]; len(got) != 2 || got[0].Detail != "2" || got[1].Detail != "3" {
		t.Fatalf("가장 오래된 기록을 버려야 한다: %+v", got)
	}
	if l.Dropped() != 1 {
		t.Fatalf("버린 수가 틀리다: %d", l.Dropped())
	}
}

func TestFlushSplitsIntoServerSizedBatches(t *testing.T) {
	l := NewLog(1000)
	for i := 0; i < MaxBatch*2+1; i++ {
		l.Record(TypeAgentStart, Success, "")
	}
	s := &fakeSender{}
	if err := l.Flush(context.Background(), s); err != nil {
		t.Fatal(err)
	}
	if len(s.batches) != 3 || len(s.batches[0]) != MaxBatch {
		t.Fatalf("서버 상한(%d)씩 나눠 보내야 한다: %d 배치", MaxBatch, len(s.batches))
	}
}

func TestFlushWithNothingSendsNothing(t *testing.T) {
	s := &fakeSender{}
	if err := NewLog(10).Flush(context.Background(), s); err != nil {
		t.Fatal(err)
	}
	if len(s.batches) != 0 {
		t.Fatal("빈 로그는 요청을 만들지 않는다")
	}
}
