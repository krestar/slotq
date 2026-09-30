# Product 관측 로컬 stack

#106의 로컬 재현용 Prometheus/Grafana/Tempo 구성이다. Tempo 2.8 단일 binary를 고정해 Kafka 의존성을 추가하지 않는다. 운영 배포의 TLS, secret 배포, 백업/retention 정책을 대신하지 않는다.

## 실행

Docker Desktop과 PowerShell 7에서 repository root 기준:

```powershell
./infra/observability/setup.ps1
docker compose --env-file infra/observability/.local/compose.env -f infra/compose.observability.yml up -d
```

setup은 기존 `.local`을 덮어쓰지 않는다. `.local` 파일에는 machine credential이 있으므로 현재 operator만 읽을 수 있도록 OS ACL을 제한한다. 파일/내용을 git, terminal capture, Issue evidence에 넣지 않는다. Grafana는 `http://127.0.0.1:3000`, 사용자 `operator`, password는 `.local/compose.env`의 `GRAFANA_PASSWORD`다. Prometheus API/UI는 `http://127.0.0.1:9090`, `telemetry` + 별도 `METRICS_PASSWORD` Basic 인증을 요구한다.

Product 프로세스에 `.local/product.env`의 환경변수를 설정한다. 기본 scrape target은 `host.docker.internal:8080`이며 실제 Product port가 다르면 `prometheus.yml`만 바꾼다. 해당 env에는 scrape 전용 bearer와 OTLP 전용 Basic credential만 포함된다. Product 로그인 bearer를 재사용하지 않는다. Product OTLP HTTP endpoint는 `/v1/traces` 전체 경로다.

DB 관측은 기본 비활성이고 별도 read-only 계정을 명시해야 한다:

```text
SLOTQ_OBSERVABILITY_DATABASE_ENABLED=true
SLOTQ_OBSERVABILITY_DATABASE_JDBC_URL=jdbc:mysql://127.0.0.1:3306/slotq
SLOTQ_OBSERVABILITY_DATABASE_USERNAME=slotq_observer
SLOTQ_OBSERVABILITY_DATABASE_PASSWORD=<secret>
SLOTQ_OBSERVABILITY_DATABASE_INTERVAL=PT15S
SLOTQ_OBSERVABILITY_DATABASE_STALE_AFTER=PT45S
```

계정 생성은 DB 관리자의 기존 secret 관리 절차를 따른다. 요구 권한은 아래 관측 테이블 SELECT와 `information_schema.innodb_metrics` 조회에 필요한 PROCESS다. Product 테이블 변경/DDL/kill 권한은 부여하지 않는다. PROCESS는 global DB 운영정보 읽기 권한이므로 접근통제된 관측 계정만 사용한다.

```sql
GRANT SELECT ON slotq.event_records TO 'slotq_observer'@'%';
GRANT SELECT ON slotq.event_discovery TO 'slotq_observer'@'%';
GRANT SELECT ON slotq.event_deliveries TO 'slotq_observer'@'%';
GRANT SELECT ON slotq.event_registrations TO 'slotq_observer'@'%';
GRANT SELECT ON slotq.event_kafka_publications TO 'slotq_observer'@'%';
GRANT SELECT ON slotq.waitlist_promotion_receipts TO 'slotq_observer'@'%';
GRANT SELECT ON slotq.waitlist_promotion_requests TO 'slotq_observer'@'%';
GRANT SELECT ON performance_schema.data_lock_waits TO 'slotq_observer'@'%';
GRANT SELECT ON performance_schema.global_status TO 'slotq_observer'@'%';
GRANT PROCESS ON *.* TO 'slotq_observer'@'%';
```

`%`는 로컬 예제다. 실제 계정 host는 관측 프로세스의 허용 출발지로 제한한다. deadlock instrument가 disabled이면 해당 series는 제공하지 않는다. locks 권한 실패는 별도 `sample="locks"` unavailable로 표시하며 event sample을 지우지 않는다.

## Dashboard와 경계

Grafana 로그인 후 **SlotQ Product — requests, DB delivery and effects**가 자동 provision된다. Prometheus 자체도 Basic auth가 필요하며 Grafana는 저장된 datasource credential로 proxy 조회한다. Tempo의 3200/4318은 host에 publish하지 않는다. host OTLP는 Basic 인증된 nginx `/v1/traces` POST 경로만 열며 query/admin 경로는 없다. Trace 조회는 로그인한 Grafana proxy를 통해서만 한다. Docker daemon/network access 자체는 trusted operator boundary다. nginx access log는 credential/body가 기록되지 않도록 비활성화한다.

로컬 stack HTTP는 loopback host와 trusted Docker network에서만 사용한다. 원격 배포에는 TLS와 network policy가 필요하며 localhost/별도 port가 authentication을 대체하지 않는다. 이 machine scrape/ingest credential은 #110 human recovery 권한 모델이 아니다.

[metric/query 의미](../../docs/architecture/product-observability-metrics.md), [correlation/security](../../docs/architecture/product-observability.md)를 함께 확인한다.

## 실제 확인 절차

1. 정상 Product request → event delivery → receipt를 실행하고 request ID로 Tempo server span과 link된 delivery attempt를 조회한다.
2. worker를 중단한 상태에서 기존 Product workload를 commit해 PENDING backlog를 만든다. `slotq_db_delivery_targets`, oldest age가 보이고 60초 age + 30초 `for` 뒤 `SlotqBacklog`가 firing인지 조회한다. worker 재개/drain 뒤 resolved를 확인한다.
3. 기존 event failure/replay 테스트 경로로 DEAD target을 만들고 `SlotqDeadDelivery` firing 및 recovery 후 resolved를 확인한다. 정상 no-op receipt는 DEAD에 포함하지 않는다.
4. 두 DB connection으로 같은 synthetic row를 갱신해 lock wait를 유지한다. `slotq_db_lock_waits > 0` 및 10초 `for` 뒤 `SlotqDatabaseLockWait` firing, lock 해제 후 resolved를 확인한다. Product row를 운영 중 강제로 잠그지 않는다.
5. 관측 DB credential/collector를 중단하고 sample healthy=0/NaN 또는 export failure와 Product commit/rollback 독립성을 확인한다. scrape request는 DB sample을 실행하지 않는다.
6. Kafka relay/intake가 활성화되면 publication pending/retry/failure, runtime state, intake delay와 group/partition lag를 조회한다. 비활성·미수집 panel은 **NO SIGNAL / UNKNOWN**이다. `or vector(0)`/null-to-zero transformation은 사용하지 않는다. Group/topic/partition label은 배포 allowlist와 128개 조합 상한 안에서만 허용한다.

Relay/consumer의 독립 JVM은 기본 web none이다. Scrape가 필요한 배치는 명시적으로
`spring.main.web-application-type=servlet`, `slotq.events.management-only=true`와 TLS/별도 machine
scrape bearer를 설정한다. 이 role은 `GET /actuator/prometheus`만 통과시키며 Product·operator·diagnostic
경로와 다른 method는 404다. 정확한 consumer/transport/epoch와 기존 scheduler guard도 유지한다.
Read-only telemetry 계정은 각 JVM에 별도로 설정한다. Human recovery는 Product의 #110 private TLS
boundary만 사용한다. Management-only flag는 Product/cutover role에서 거부된다.

`SlotqExecutionRuntimeUnavailable`, consumer DEAD/backlog/sample, Kafka lag unavailable과 publication
expired claim/DEAD alert를 분리한다. Publisher가 죽은 뒤 ACK unknown은 Product read-only publication
inventory로 탐지한다. 미수집 metric을 0으로 만들지 않는다. 실제 #111 drill 명령·결과는
[M5 비교 및 통합 drill](../../docs/experiments/m5-transport/README.md)을 따른다.

알림 임계값은 위 재현을 위한 local diagnostic budget이며 #111 transport 선택/production SLO 임계값을 확정하지 않는다. Alertmanager 외부 notification 채널은 추가하지 않는다. Prometheus `/api/v1/alerts`와 dashboard가 firing/resolved evidence다.
