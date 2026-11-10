package main

import (
	"context"
	"log/slog"
	"time"
)

// retry 는 fn 이 성공하거나 ctx 가 끝날 때까지 간격을 두 배씩(최대 max) 늘려 가며 다시 부른다.
// 부팅 직후처럼 서버에 아직 닿지 않을 때 에이전트가 끝나 버리면 SCM 재시작 횟수를 다 써서 그날 수집이 멈춘다.
func retry(ctx context.Context, log *slog.Logger, what string, first, max time.Duration, fn func(context.Context) error) error {
	wait := first
	for {
		err := fn(ctx)
		if err == nil {
			return nil
		}
		// 종료 중이면 실패 경고를 남기지 않고 바로 끝낸다.
		if ctx.Err() != nil {
			return ctx.Err()
		}
		log.Warn(what+" 실패, 다시 시도한다", "err", err, "wait", wait)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(wait):
		}
		wait = min(wait*2, max)
	}
}
