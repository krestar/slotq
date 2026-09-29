# #108 Kafka intake 및 M4 검증 (2026-09-29)

## 기준

- 구현 HEAD: `a3a7718575193eba47b89e612d45e3f187978227`
- MySQL `8.4.11`, `REPEATABLE-READ`; Kafka `apache/kafka:4.1.1`
- 세 raw 파일은 이번 #108 구현에서 생성했다. #107 publication evidence를 재사용하지 않았다.
- `intake-crash-raw.json`과 `m4-kafka-vertical-raw.json`은 위 HEAD에서 실제 Testcontainers MySQL/Kafka를 사용한 테스트가 작성했다. `browser-raw.json`은 같은 production code(`088866797327279a3a76468fbddd7e905150740f`; 이후 `a3a7718`은 test만 추가)를 실행한 격리된 JVM·Docker·기존 UI 관측값이다.

## 재현 명령

`backend/`에서 Java 25와 Docker Desktop이 실행 중일 때:

```powershell
.\gradlew.bat test --tests com.slotq.events.persistence.KafkaIntakeCrashIntegrationTests --tests com.slotq.KafkaWaitlistVerticalSliceIntegrationTests --tests com.slotq.events.EventTransportCutoverIntegrationTests --tests com.slotq.integration.operations.OperationsObservationIntegrationTests -PkafkaEvidenceDir=C:/dev/slotq/docs/experiments/kafka-consumer/2026-09-29 -PkafkaEvidenceRevision=a3a7718575193eba47b89e612d45e3f187978227 --no-daemon --offline --console=plain
.\gradlew.bat clean build --no-daemon --offline --console=plain
```

첫 명령은 4 suites, 6 tests, 실패 0으로 완료했다. 전체 clean build는 `BUILD SUCCESSFUL in 13m 21s`로 완료했다. JUnit XML 집계는 71 suites / 605 tests / 601 passed / 4 skipped / failures 0 / errors 0이다.

## Raw SHA-256

| 파일 | SHA-256 |
| --- | --- |
| `intake-crash-raw.json` | `7b60ee91776407b8db6a37aecf9863cec91203b2da675d2edb6b0799de0bdafc` |
| `m4-kafka-vertical-raw.json` | `695f19e830e3db95302f1eca2c65d617248a4559a76c964552a8d4c74cc02006` |
| `browser-raw.json` | `729833fe65347a785a5edde6937947b8bc97729bc283ca0e68306a194fda0192` |

## 직접 관측

- 독립 child JVM 종료 코드 81/82/83에서 intake 전, intake commit 후 offset 전, offset commit 후 최초 effect 전 상태를 기록했다. 마지막 시점에는 broker group offset이 다음 위치에 있고 target은 `PENDING`, attempts=0이었다. broker redelivery 0건에서 consumer-scoped DB executor가 최초 attempt 1회로 receipt `NO_CANDIDATE`와 `DONE`을 commit했다.
- 같은 실제 broker에 malformed, unknown original, canonical corruption을 전송해 payload 없는 quarantine failure code 세 가지를 MySQL에 기록했다. business target은 1개로 유지했다.
- 실제 Booking/Waitlist 명령으로 release→첫 FIFO Offer, reject→다음, expiry→다음, accept, no-candidate no-op 뒤 새 promotion request를 실행했다. 5 original event는 Kafka에서 두 logical group으로 fan-out되어 각각 target을 1개씩 만들고 10 target 모두 `DONE`이었다. observer projection은 5개이며 intake timestamp와 projection timestamp를 분리했다.
- 양방향 quiesced cutover 테스트는 shared discovery cursor를 지난 원본도 scan으로 복원하고, 이전 epoch direct worker의 claim을 거부했다. DB direct의 scope·retry·DEAD·effect/DONE atomicity와 M4 FIFO/capacity/#88/early RR/current-state regression은 전체 Backend suite에서 검증한다.
- 실제 브라우저에서는 기존 Customer Waitlist UI로 `PENDING` Offer를 발견했다. 예약 화면으로 이동한 뒤 다시 Offer를 읽었고, Product JVM 재시작 후 401→local fixture 재인증→exact Offer GET을 관측했다. Offer 수락 후 브라우저의 Entry/Offer/Reservation은 `FULFILLED`/`ACCEPTED`/`CONFIRMED`; MySQL 원본도 동일했다. relay, Waitlist, observer는 서로 다른 JVM이었다. production Frontend는 변경하지 않았다.

`browser-raw.json`의 local fixture session POST 수에는 초기 인증과 재인증이 함께 포함된다. 401 1건 뒤 인증 POST 및 exact Offer GET 성공을 로그에서 직접 확인했다. 실험용 credential, raw broker payload 및 고객 PII는 evidence에 포함하지 않았다.

독립 JVM 전체 fault matrix는 #109, 사람 권한 recovery/auth/audit은 #110, DB direct 대비 최종 비교와 채택 판단은 #111 소유다.
