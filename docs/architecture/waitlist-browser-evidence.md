# M4 Waitlist 실제 browser 증거 (#97)

2026-09-23에 `main` `4afe0ba1043c484853643fa2d3dbbcba7ea4d9e7`에서 시작한
`feat/waitlist-thin-ui`의 UI로 확인했다. Backend business 코드는 변경하지 않았다.
이 문서는 실제 관측 기록이다. ID는 격리 DB의 서버 발급 값이며 key와 token 원문은 남기지 않는다.

## 실행 환경과 준비

- Windows, Node 24, MySQL Community Server **8.4.11** Windows ZIP, 독립 `slotq97` DB,
  `127.0.0.1:3307`. `SELECT @@transaction_isolation`은 `REPEATABLE-READ`였다.
- Docker CLI가 동작하지 않아 MySQL ZIP을 `%TEMP%/slotq97-mysql`에 풀고
  `mysqld --initialize-insecure`로 격리 datadir을 만들었다. `--no-defaults`,
  `--bind-address=127.0.0.1`, `--port=3307`로 실행했다. 이 설정은 로컬 실증 전용이다.
- JDK 25의 `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`, `local` profile,
  `SLOTQ_MYSQL_PORT=3307`, `SLOTQ_MYSQL_DATABASE=slotq97`, Backend `127.0.0.1:8081`.
  `slotq.waitlist.promotion.enabled`, `slotq.waitlist.promotion.maintenance-enabled`,
  `slotq.events.delivery.scheduler-enabled`를 모두 `true`로 실행했다. 시작 로그는
  MySQL 8.4.11, Flyway V14, `REPEATABLE_READ`와 정상 bootstrap을 확인했다.
- `VITE_API_BASE_URL=http://localhost:8081`로 Vite 5173 `customer-a`, 5174
  `customer-b`, 5175 `tenant-a-owner`를 각각 실행했다. 응답 유실 사례만
  5176 `customer-a` → 로컬 8082 proxy → 8081을 사용했다.
- dev auth fixture의 Venue A
  `30000000-0000-0000-0000-000000000001` (UTC)에는 영업시간이 없어, 이 격리 DB에만
  `venue_operating_hours`의 요일 1~7을 각각 `09:00:00`–`22:00:00`으로 시드했다.
  첫 Backend bootstrap/Flyway 뒤 아래 형태로 넣고 UTC JVM으로 재시작했다.
  테스트용 시드 외 Resource, Policy v2 (HOLD 1분), 미래 Slot, 일반 Reservation은
  기존 management/customer API로 만들었다. Offer·receipt·event row를 직접 넣지 않았다.

로컬 재현 시 격리 datadir을 만들고 서버를 실행한다. `%TEMP%/slotq97-mysql` 경로와
MySQL ZIP 위치는 자신의 환경에 맞춘다. Backend 첫 시작에서 Flyway/fixture를 만든 뒤
아래 SQL을 요일별로 적용한다.

```powershell
$mysqlHome = "$env:TEMP\slotq97-mysql\mysql-8.4.11-winx64"
& "$mysqlHome\bin\mysqld.exe" --no-defaults --initialize-insecure --datadir="$env:TEMP\slotq97-mysql\data"
& "$mysqlHome\bin\mysqld.exe" --no-defaults --datadir="$env:TEMP\slotq97-mysql\data" --port=3307 --bind-address=127.0.0.1 --console
# 다른 터미널: mysql -h 127.0.0.1 -P 3307 -u root -e "CREATE DATABASE slotq97"
```

Backend는 별도 터미널에서 실행한다. 영업시간 시드 후 재시작해 UTC 설정을 유지한다.

```powershell
cd C:\dev\slotq\backend
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
$env:JAVA_TOOL_OPTIONS = '-Duser.timezone=UTC'
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SLOTQ_MYSQL_PORT = '3307'
$env:SLOTQ_MYSQL_DATABASE = 'slotq97'
$env:SPRING_APPLICATION_JSON = '{"server":{"port":8081,"address":"127.0.0.1"},"slotq":{"cors":{"allowed-origins":"http://localhost:5173,http://localhost:5174,http://localhost:5175"},"waitlist":{"promotion":{"enabled":true,"maintenance-enabled":true}},"events":{"delivery":{"scheduler-enabled":true}}}}'
.\gradlew.bat bootRun
```

Frontend는 각자 다른 터미널에서 같은 명령을 port/fixture만 바꿔 실행한다.

```powershell
cd C:\dev\slotq\frontend
$env:VITE_API_BASE_URL = 'http://localhost:8081'
$env:VITE_LOCAL_AUTH_FIXTURE = 'customer-a'
npm run dev -- --port 5173 --strictPort
# 5174/customer-b, 5175/tenant-a-owner도 별도 프로세스로 실행
```

```sql
-- 독립 slotq97 DB에만 실행. day_of_week를 1부터 7까지 각각 한 번 삽입.
INSERT INTO venue_operating_hours
  (tenant_id, venue_id, day_of_week, opens_at, closes_at)
VALUES
  (UNHEX(REPLACE('20000000-0000-0000-0000-000000000001', '-', '')),
   UNHEX(REPLACE('30000000-0000-0000-0000-000000000001', '-', '')),
   1, '09:00:00', '22:00:00');
```

## Browser ↔ Backend ↔ MySQL 관측

| 흐름 | 실제 관측 |
| --- | --- |
| 등록 → release → accept | 12:00 Slot `4866676a-37e8-4a9c-9d79-f71c5a8b644a`에 Customer A의 Reservation `05777373-b534-4449-9d23-64d2b4146bc5`를 CONFIRMED로 점유했다. Customer B가 만석 화면에서 Entry `387c4bfb-dd00-4bc6-9125-393e6b9a5522`를 201 등록했다. Venue 목록은 WAITING을 표시했다. A가 예약을 CANCELLED로 바꾸자 활성 scheduler가 Offer `5017d6fd-fcd2-412d-9781-e795808ffb0d`, Reservation `9b89278b-7065-4238-a7fa-372efbf5292d`를 만들었다. B가 수락한 뒤 Entry FULFILLED, Offer ACCEPTED, 같은 Reservation CONFIRMED를 기존 예약 상세에서 확인했다. 새 Customer HOLD를 호출하지 않았다. |
| reject → next | 13:00 Slot `1e11e716-d98d-4481-9ae1-4e80bd48cfb0`에 B Entry `e3cedfe3-a53c-4565-a84f-737b0d78f51a`, 이어 A Entry `a2367ced-0ff6-45ce-851a-a50e0f43e2d7`를 등록했다. 점유 예약을 취소하자 B Offer `5c9a1f56-45a4-4e81-961e-deb83e64dae9`가 PENDING이 됐다. B가 거절하자 Offer/Entry DECLINED, backing Reservation CANCELLED가 됐고 A Offer `95377672-afaa-4c45-bd23-78c7de955dba`가 PENDING으로 승급했다. |
| deadline expiry → next | A의 위 Offer를 수락하지 않고 서버 deadline까지 기다렸다. exact read가 Entry/Offer EXPIRED 및 backing Reservation EXPIRED를 반환했다. 그 전에 B의 새 Entry `4920ccb9-6145-47e2-a974-0c8109261243`를 등록해 두었고, 그 뒤 새 Offer `1b2b3678-b367-4c22-b62e-aa0a714a6382`가 PENDING으로 생성됐다. Venue 목록에서 DECLINED → EXPIRED → OFFERED 순서와 각 Reservation 참조를 확인했다. Browser countdown이 terminal 상태를 만들지 않았다. |
| 부적합 선행 수요 skip | 14:00 큰 Table(4석)과 작은 Table(2석)을 모두 점유했다. A의 4명 Entry `59a7d91a-ddbf-49fb-8b39-2bb688737a35`를 먼저, B의 2명 Entry `78d5d82e-be29-442b-b159-38a294db066b`를 나중에 등록했다. 작은 Slot `99a298b5-dc12-4862-b93e-39631543323a`만 release하자 Venue의 해당 Slot 필터는 앞 Entry를 `선택 Slot에 부적합`/WAITING, 뒤 Entry를 `선택 Slot에 적합`/OFFERED로 반환했다. 뒤 Offer의 Reservation은 `023c1175-905f-452a-b5be-cae833b3a59c`였다. 브라우저가 FIFO를 재정렬하지 않았다. |
| reload와 scope | `view=waitlist&venueId&date&entryId&offerId` URL로 reload한 뒤 key/intent UI는 사라지고 같은 Entry/Offer exact GET 결과가 복구됐다. ID 없는 `venueId&date` 재진입은 자기 목록에서 Entry를 발견했다. Staff fixture의 기존 management Reservation GET은 200, 같은 Venue Waitlist GET은 `403 ACCESS_DENIED`였다. |
| 등록 응답 유실 | 5176 앞 로컬 proxy가 Backend의 201 응답을 완전히 받은 뒤 browser 쪽 연결을 끊었다. 브라우저 네트워크 계층에서 동일 POST가 두 번 관측되어 201 응답 두 개를 끊었고, UI는 `등록 요청: unknown`과 key lookup만 표시했다. key lookup은 원 Entry `a571406d-951e-4828-8b4c-afabebf3bec3`를 반환했고 exact GET으로 OFFERED 상태를 확인했다. DB에는 이 수요의 Entry가 하나였다. 별도 자동 UI 재전송은 없었다. |
| Offer 응답 유실 | 같은 proxy가 Offer `d1592f41-64be-4288-aa28-e964f944fc0b`의 accept 200 응답 두 개를 끊었다. UI는 `처리 결과 확인 필요`를 표시하고 반대 작업을 제공하지 않았다. 원 Offer exact read는 ACCEPTED, Entry FULFILLED, 원 Reservation `d59051f7-a0ed-46f9-91e1-070d6722cc8b` CONFIRMED를 반환했다. |
| Backend / DB 장애와 복구 | Backend JVM을 중단한 채 exact refresh를 누르면 `현재값 확인 실패: INTERNAL_ERROR`가 표시되고 이전 allowedActions 버튼은 사라졌다. 같은 DB로 Backend를 재시작하자 이전 process token은 무효화됐고, 재인증 후 URL의 원 Entry/Offer를 다시 읽어 위 ACCEPTED/CONFIRMED를 확인했다. 이어 `mysqladmin shutdown`으로 MySQL만 중단했을 때 실제 Hikari connection timeout과 같은 UI 미확인 상태를 관측했다. MySQL을 같은 datadir로 재시작하고 원 Entry exact read를 누르면 서버 상태가 다시 표시됐다. 장애 중 mutation 전송은 없었다. |

응답 유실 proxy는 테스트용 Node `http` proxy로 요청 body/header를 Backend에 그대로 전달하고,
해당 POST의 upstream 응답 `end` 이후 downstream socket만 닫았다. 등록/Offer 각각 두 upstream
POST가 관측됐지만 DB에는 한 등록 Entry와 한 Offer terminal 결과가 남았다. proxy가
Idempotency-Key 또는 token을 출력하지 않도록 했으며 Product 코드에는 포함하지 않았다.

## 검증 결과와 한계

`frontend/`에서 `npm ci`, `npm run typecheck`, `npm test`, `npm run build`를 실행했다.
최종 전체 실행은 Vitest 12 files / 205 tests 통과, typecheck 및 Vite build 통과였다.

이 기록은 위 격리 fixture와 수동 browser 경로의 관측이다. #94~#96의 별도 Backend
통합·process recovery 검증을 대체하지 않는다. M4를 이 문서만으로 Complete로 표시하지 않는다.
