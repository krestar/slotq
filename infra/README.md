# Infrastructure

이 디렉터리는 SlotQ의 로컬·운영 Infrastructure 설정 경계다.

## 로컬 MySQL 8.4 LTS

Docker와 Docker Compose가 준비된 환경에서 저장소 root 기준 다음 단일 명령으로 로컬 MySQL을 시작한다.

```bash
docker compose -f infra/compose.mysql.yml up -d --wait
```

MySQL은 `127.0.0.1:3306`에만 노출되며 database 이름은 `slotq`다. 로컬 전용 환경은 고정 Secret을 저장소에 두지 않기 위해 root 비밀번호를 비워 둔다. 외부에 노출하거나 production 설정으로 재사용하지 않는다.

포트 충돌이 있으면 `SLOTQ_MYSQL_PORT`만 변경할 수 있다.

```bash
SLOTQ_MYSQL_PORT=3307 docker compose -f infra/compose.mysql.yml up -d --wait
```

Backend는 `local` profile에서 같은 database를 사용한다.

```bash
cd backend
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

Windows PowerShell에서는 다음과 같이 실행한다.

```powershell
Set-Location backend
$env:SPRING_PROFILES_ACTIVE = "local"
.\gradlew.bat bootRun
```

포함된 Compose 설정은 `SLOTQ_MYSQL_PORT`만 받아 database `slotq`, 사용자 `root`, 빈 비밀번호로 실행한다. 별도로 구성한 MySQL에 Backend를 연결할 때는 `SLOTQ_MYSQL_DATABASE`, `SLOTQ_MYSQL_USER`, `SLOTQ_MYSQL_PASSWORD` 환경 변수를 사용한다. 실제 credential과 Secret은 파일에 commit하지 않는다. 저장소 root의 `.env`, `.env.local`, `.env.*.local` 파일은 Git에서 제외된다.

Flyway migration은 Infrastructure가 아니라 이를 사용하는 Backend가 `backend/src/main/resources/db/migration/`에서 소유한다. 빈 database에서 Backend가 시작되면 migration이 자동 적용된다.

`local` profile은 `slotq.auth.dev-bootstrap-enabled=true`와 Browser CORS 기본 Origin
`http://localhost:5173`을 적용한다. Backend는 시작할 때 공개 fixtureKey(`customer-a`,
`tenant-a-owner`, `tenant-a-manager`, `tenant-a-staff`)용 Principal·membership·VenueGrant fixture를
DB에 준비하지만 Bearer credential은 process memory에서만 무작위로 만든다. Backend를 다시
시작하면 기존 credential은 무효가 된다. production profile에서 dev bootstrap을 활성화하거나
wildcard CORS Origin을 설정하면 startup이 실패한다.

환경과 volume을 제거하려면 다음 명령을 사용한다.

```bash
docker compose -f infra/compose.mysql.yml down -v
```

제거 후 같은 `up -d --wait` 명령으로 다시 구성할 수 있다. Testcontainers 기반 Backend test는 별도의 로컬 database 상태를 사용하지 않고 격리된 MySQL 8.4 container에서 migration을 검증한다. 따라서 Backend test를 실행할 때도 Docker daemon이 실행 중이어야 한다.
