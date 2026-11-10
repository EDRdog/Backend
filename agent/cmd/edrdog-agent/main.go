// edrdog-agent 는 엔드포인트에서 행위를 관찰해 서버로 보내고, 서버가 내린 조치를 실행하는 수집기다.
package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	goruntime "runtime"
	"sync"
	"syscall"
	"time"

	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/audit"
	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/command"
	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/config"
	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/event"
	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/runtime"
	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/selfcheck"
	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/sensor"
	"github.com/2026-Siheung-Bootcamp-Team-I/Backend/agent/internal/transport"
)

// version 은 서버에 알리는 에이전트 판번호다. 릴리즈할 때 -ldflags 로 덮어쓴다.
var version = "0.1.0"

type options struct {
	configPath       string
	logPath          string
	selfTest         bool
	selfTestInterval time.Duration
	asService        bool
	securityCheck    bool
	recordBaseline   bool
}

func main() {
	var opts options
	flag.StringVar(&opts.configPath, "config", "", "설정 파일 경로")
	flag.StringVar(&opts.logPath, "log", "", "로그 파일 경로. 비우면 stderr (Windows 서비스는 stderr 가 아무 데도 가지 않는다)")
	flag.BoolVar(&opts.selfTest, "selftest", false, "진짜 센서 대신 가짜 이벤트를 보내 서버까지의 경로를 확인한다")
	flag.DurationVar(&opts.selfTestInterval, "selftest-interval", time.Second, "자체 점검 이벤트 주기")
	flag.BoolVar(&opts.asService, "service", false, "Windows 서비스로 실행한다(설치 스크립트가 붙인다)")
	// -selftest 와 다르다. 이쪽은 에이전트 자신의 보안 설정과 무결성을 점검한다(internal/selfcheck).
	flag.BoolVar(&opts.securityCheck, "security-check", false, "보안 기능 자체시험과 무결성 검증을 한 번 돌려 출력하고 끝낸다. 실패가 있으면 종료 코드 1")
	flag.BoolVar(&opts.recordBaseline, "record-baseline", false, "지금 바이너리와 설정 파일의 해시를 무결성 기준값으로 기록하고 끝낸다(설치 스크립트가 부른다)")
	flag.Parse()

	if opts.securityCheck || opts.recordBaseline {
		os.Exit(runOneShot(opts))
	}

	out, closeLog, err := openLogWriter(opts.logPath)
	if err != nil {
		// 여기서만 stderr 로 적는다: 로그 파일을 못 열었다는 사실은 어디엔가는 남아야 한다.
		fmt.Fprintf(os.Stderr, "로그 파일을 열지 못했다: %v\n", err)
		os.Exit(1)
	}
	defer func() { _ = closeLog() }()

	log := slog.New(slog.NewTextHandler(out, &slog.HandlerOptions{Level: slog.LevelInfo}))

	if opts.asService {
		if err := runAsService(opts, log); err != nil {
			log.Error("서비스 실행 실패", "err", err)
			os.Exit(1)
		}
		return
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := runAgent(ctx, opts, log); err != nil {
		log.Error("에이전트가 멈췄다", "err", err)
		os.Exit(1)
	}
}

// runOneShot 은 -security-check, -record-baseline 을 처리하고 종료 코드를 돌려준다.
func runOneShot(opts options) int {
	if opts.configPath == "" {
		fmt.Fprintln(os.Stderr, "-config 가 필요하다")
		return 1
	}
	binary, err := os.Executable()
	if err != nil {
		fmt.Fprintf(os.Stderr, "실행 파일 경로를 알 수 없다: %v\n", err)
		return 1
	}
	if opts.recordBaseline {
		if err := recordBaseline(os.Stdout, opts.configPath, binary); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		return 0
	}
	cfg, err := config.Load(opts.configPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if !runSecurityCheck(os.Stdout, cfg, opts.configPath, binary) {
		return 1
	}
	return 0
}

// runAgent 는 ctx 가 끝나면 정리하고 돌아온다.
func runAgent(ctx context.Context, opts options, log *slog.Logger) error {
	if opts.configPath == "" {
		return fmt.Errorf("-config 가 필요하다")
	}
	// 감사기록(KISA BF-13). 등록 전의 사건도 여기 쌓였다가 등록 뒤에 나간다.
	auditLog := audit.NewLog(1000)
	auditLog.Record(audit.TypeAgentStart, audit.Success, fmt.Sprintf("version=%s platform=%s", version, goruntime.GOOS))

	cfg, err := config.Load(opts.configPath)
	if err != nil {
		return err
	}
	// 실행 파일 경로를 못 구하면 빈 값으로 두고 무결성 점검이 실패로 남기게 한다.
	binary, _ := os.Executable()
	// 기동 시 점검. 센서는 아직 없어 sensors 항목은 건너뛴다.
	runChecks(selfcheck.Inputs{Config: cfg, ConfigPath: opts.configPath}, binary, auditLog, log)
	httpClient, err := cfg.HTTPClient()
	if err != nil {
		return err
	}

	client := transport.NewClient(transport.Config{
		BaseURL:      cfg.BaseURL,
		EnrollSecret: cfg.EnrollSecret,
		HostID:       cfg.HostIdentifier,
		// 서버는 이 값에 windows 가 들어있는지로 수집 설정을 가른다.
		Platform:     goruntime.GOOS,
		AgentVersion: version,
		Timeout:      cfg.FlushInterval(),
	}, httpClient)

	// 서버에 닿을 때까지 다시 시도한다. 끝나 버리면 부팅 직후 오프라인인 기기는 그날 수집이 멈춘다.
	if err := retry(ctx, log, "등록", 5*time.Second, 5*time.Minute, client.Enroll); err != nil {
		return fmt.Errorf("등록 실패: %w", err)
	}
	log.Info("등록 완료", "host", cfg.HostIdentifier, "platform", goruntime.GOOS, "version", version)
	auditLog.Record(audit.TypeAgentEnroll, audit.Success, "host="+cfg.HostIdentifier)

	// 첫 하트비트로 어떤 센서를 켤지·어디를 감시할지 수집 설정을 받는다.
	var beat transport.Heartbeat
	err = retry(ctx, log, "첫 하트비트", 5*time.Second, 5*time.Minute, func(ctx context.Context) error {
		var err error
		beat, err = client.Heartbeat(ctx)
		return err
	})
	if err != nil {
		return fmt.Errorf("수집 설정을 받지 못했다: %w", err)
	}

	factory := event.Factory{Host: cfg.HostIdentifier}
	sensors, err := buildSensors(factory, beat.Config, opts, log)
	if err != nil {
		return err
	}
	names := make([]string, len(sensors))
	for i, s := range sensors {
		names[i] = s.Name()
	}

	// 전송 주기는 서버가 정한다(엔드포인트에 손대지 않고 조절 가능해야 함).
	flush := cfg.FlushInterval()
	if beat.Config.FlushIntervalSeconds > 0 {
		flush = time.Duration(beat.Config.FlushIntervalSeconds) * time.Second
	}

	engine := runtime.New(client, runtime.Options{
		BufferSize:    cfg.BufferSize,
		BatchSize:     cfg.BatchSize,
		FlushInterval: flush,
		Logger:        log,
		OnSensorStop: func(name string, err error) {
			auditLog.Record(audit.TypeSensorStop, audit.Failure, fmt.Sprintf("sensor=%s err=%v", name, err))
		},
	})
	commands := command.New(client, command.NewKiller(), command.Options{
		Logger: log,
		OnResult: func(cmd transport.Command, r transport.CommandResult) {
			result := audit.Success
			if r.Status == transport.StatusFailed {
				result = audit.Failure
			}
			auditLog.Record(audit.TypeCommandResult, result,
				fmt.Sprintf("id=%s type=%s target=%s status=%s", cmd.ID, cmd.Type, cmd.Target, r.Status))
		},
	})

	// 하트비트 응답에 실려 온 대기 명령. 서버는 한 번 내준 명령을 다시 주지 않으므로 여기서 처리해야 한다.
	commands.Execute(ctx, beat.Commands)

	log.Info("수집 시작", "sensors", names, "flush", flush, "watch", beat.Config.WatchPaths)

	// 수집과 조치는 서로를 기다리면 안 된다. 수집이 끝나면(센서 전멸 포함) 조치 채널도 닫는다.
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var wg sync.WaitGroup
	var collectErr error
	wg.Add(4)
	go func() {
		defer wg.Done()
		collectErr = engine.Run(ctx, sensors)
		cancel()
	}()
	go func() {
		defer wg.Done()
		if err := commands.Run(ctx); err != nil {
			log.Error("조치 채널이 멈췄다", "err", err)
		}
	}()
	// 감사기록은 이벤트와 따로 흘려보낸다. 서버가 못 받으면 로그에 남겨 두고 다음 주기에 다시 보낸다.
	go func() {
		defer wg.Done()
		every(ctx, flush, func() { flushAudit(ctx, auditLog, client, log) })
	}()
	// 주기 점검. 기동 시 점검은 위에서 끝냈으므로 첫 주기는 한 간격 뒤다.
	go func() {
		defer wg.Done()
		in := selfcheck.Inputs{Config: cfg, ConfigPath: opts.configPath, Sensors: engine.SensorState}
		every(ctx, cfg.SelfCheckInterval(), func() { runChecks(in, binary, auditLog, log) })
	}()
	wg.Wait()

	// 종료 사건을 남기고 마지막으로 보낸다. 끝난 ctx 를 그대로 쓰면 마지막 전송이 바로 실패한다.
	if collectErr != nil {
		auditLog.Record(audit.TypeAgentStop, audit.Failure, collectErr.Error())
	} else {
		auditLog.Record(audit.TypeAgentStop, audit.Success, "정상 종료")
	}
	finalCtx, cancelFinal := context.WithTimeout(context.WithoutCancel(ctx), flush)
	defer cancelFinal()
	flushAudit(finalCtx, auditLog, client, log)

	log.Info("종료", "dropped", engine.Buffer().Dropped(), "auditDropped", auditLog.Dropped())
	return collectErr
}

// every 는 ctx 가 끝날 때까지 interval 마다 fn 을 부른다. 첫 호출은 한 간격 뒤다.
func every(ctx context.Context, interval time.Duration, fn func()) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			fn()
		case <-ctx.Done():
			return
		}
	}
}

// flushAudit 는 감사기록을 보내고 실패는 기록만 한다. 실패한 기록은 로그에 남아 다음 주기에 다시 나간다.
func flushAudit(ctx context.Context, al *audit.Log, sender audit.Sender, log *slog.Logger) {
	if err := al.Flush(ctx, sender); err != nil {
		log.Warn("감사기록 전송 실패, 다음 주기에 재시도", "err", err, "pending", al.Len())
	}
}

// buildSensors 는 돌릴 센서를 고른다. 켤 센서가 없으면 조용히 0건을 보내지 않고 오류를 낸다.
func buildSensors(factory event.Factory, serverCfg transport.ServerConfig, opts options, log *slog.Logger) ([]runtime.Sensor, error) {
	if opts.selfTest {
		return []runtime.Sensor{&sensor.SelfTest{
			Factory:  factory,
			Interval: opts.selfTestInterval,
			// 해시기를 붙여 두면 자체 점검이 sha256 경로까지 확인해 준다.
			Hasher: sensor.NewFileHasher(),
		}}, nil
	}
	sensors := platformSensors(factory, serverCfg, log)
	if len(sensors) == 0 {
		return nil, fmt.Errorf("%s 에서 켤 센서가 없다. 서버 설정에서 전부 껐거나 지원하지 않는 플랫폼이다", goruntime.GOOS)
	}
	return sensors, nil
}
