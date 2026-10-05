# PR #115 두 blocker 재검증

> 이 문서는 당시 실행의 관찰과 한계를 보존하는 historical summary다. PR #139 정책에 따라
> run별 raw output은 현재 tree에서 제거했다. 과거 bytes는 Git history에 남아 있으며,
> 아래 수치를 이번 cleanup의 새 실행 결과로 해석하지 않는다. 새 raw는 gitignored `build/`에 생성한다.

기존 구현을 기준으로 두 blocker를 재현하고 필요한 관측 계약/log 경계만 보완했다.
최신 remote main은 `c694273c`, 수정 전 PR head는 `798943b`다.

## Logical consumer 계약

#108의 `waitlist.promotion`, `operations.event-observation`을 공통 allowlist에 허용하고
임의 consumer는 계속 거부한다. DB direct sampler의 SQL과 emission은 그대로
`waitlist.promotion`만 관측한다. observer projection/intake/Kafka runtime은 추가하지 않았다.

수정한 intake/lag legend 및 invalid-delay 집계는 consumer를 보존한다.
실제 [dashboard](../../../../infra/observability/grafana/dashboards/slotq-product.json)의 query/legend를 대조했다.
[Promtool fixture](../../../../infra/observability/kafka-query-contract.test.json)는 실제 dashboard에서 추출한 식에 synthetic series만 넣었다.
두 consumer의 invalid-delay 5/10, replica 중복 제거 후 lag 4/7을 독립적으로 확인했다.
기존 Kafka panel 12개 식은 입력 series가 없을 때 모두 빈 결과를 유지한다.
실제 Kafka emission이나 integrated panel evidence를 주장하지 않는다.

```powershell
docker run --rm --network none --entrypoint /bin/promtool --mount type=bind,source=<absolute-infra-observability-directory>,target=/fixture,readonly prom/prometheus:v3.5.0 test rules /fixture/kafka-query-contract.test.json
```

당시 Promtool 결과: SUCCESS.

## Queue saturation

사용 중인 [OTel SDK 1.62.0 소스](https://github.com/open-telemetry/opentelemetry-java/blob/v1.62.0/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/export/BatchSpanProcessor.java)의
`queue.offer` 실패는 exporter 이전 drop이며 다음 batch export 때 numeric warning을 출력한다.
기존 `CountingExporter`는 이 drop을 세지 않고, SDK MeterProvider도 연결되지 않았다.
기존 SDK logger OFF 설정은 해당 warning까지 숨겼다.

정확한 SDK logger의 queue-full warning만 기존 nonblocking async appender에 허용했다.
문구와 양의 정수 형식을 검사하고 throwable/credential/endpoint/raw message는 차단한다.
sampling, queue/batch/timeout, span/correlation 및 transaction 코드는 변경하지 않았다.
신호는 exporter failure counter와 별개의 local queue-loss warning이며 durable counter가 아니다.
warning 역시 log queue 유실·process 종료 전 미출력 가능성을 가진다.

실제 OTLP collector를 stall한 채 4,096 sampled spans와 Product request를 5초 안에 종료시키고,
실제 SDK warning이 Boot logging 경로로 남으며 credential/trace payload/endpoint가 출력되지 않는지 확인했다.
새 회귀의 수정 전 실행은 두 blocker에서 각각 실패했다. 수정 후 결과와 warning,
관찰은 이 summary에 남긴다. 재현 test는 `TracingExporterIsolationTests`를 따른다.

## Evidence 범위

[기존 DB direct summary](../2026-09-27-db-direct/README.md)는 당시 revision의 관찰과 limitation을 보존한다.
이번 변경 후 그 자료를 현재 revision 재실행 결과로 표시하지 않는다. 수정한 query는 Promtool로,
queue loss와 privacy는 실제 SDK/OTLP focused test로 재검증했다. 기존 Product commit/rollback/effect 및
DB inventory focused 7개도 통과했다. 기존 DB backlog/DEAD/lock drill은 해당 구현/식이 바뀌지 않아
반복하지 않았다.

구현 checkpoint `98c6f4a`에서 Backend 전체 test는 9분 47초,
clean build는 9분 59초에 각각 성공했다. XML report 기준
두 실행 모두 61 suites / 579 tests 중 576 passed, 0 failures/errors, 3 skipped다.
skip은 기존 `SLOTQ_CAPACITY_LOCK_EVIDENCE=true` opt-in 진단이다.
clean build는 전체 8 tasks를 실행했고 artifact hash와 실제 SDK warning도 기록했다.
이후 commit은 evidence 기록만 보완한다.
