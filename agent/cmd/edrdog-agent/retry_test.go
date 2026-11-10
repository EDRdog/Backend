package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

func TestRetryKeepsTryingUntilSuccess(t *testing.T) {
	calls := 0
	err := retry(context.Background(), quiet, "등록", time.Millisecond, 4*time.Millisecond, func(context.Context) error {
		calls++
		if calls < 3 {
			return errors.New("서버에 닿지 않음")
		}
		return nil
	})
	if err != nil || calls != 3 {
		t.Fatalf("err=%v calls=%d, 세 번째에 성공해야 한다", err, calls)
	}
}

func TestRetryStopsWhenContextEnds(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	err := retry(ctx, quiet, "등록", time.Millisecond, 2*time.Millisecond, func(context.Context) error {
		return errors.New("서버에 닿지 않음")
	})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err=%v, ctx 가 끝나면 그 오류로 돌아와야 한다", err)
	}
}
