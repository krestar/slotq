# ADR-0002: 단일 배포형 Modular Monolith로 시작

- 상태: `Accepted`
- 결정일: 2026-08-25

## 맥락

SlotQ는 예약·수용량·상태 전이·대기열을 하나의 일관된 제품 흐름으로 먼저 검증해야 합니다. 초기에는 독립 배포, 독립 확장, 팀별 소유권을 요구하는 측정값이 없으며, 분산 트랜잭션과 원격 호출 실패를 먼저 도입하면 핵심 invariant를 검증하기 어려워집니다.

반대로 package 경계가 없는 단일 codebase는 시간이 지날수록 persistence model과 내부 구현을 서로 공유하게 되어 후속 분리가 어려워질 수 있습니다. 하나의 배포 단위를 유지하되 도메인 책임과 의존 방향은 처음부터 명시할 필요가 있습니다.

## 결정

SlotQ Product Backend는 **하나의 실행 파일과 하나의 배포 단위를 가진 Modular Monolith**로 시작합니다. 초기 logical module은 다음 네 가지입니다.

| Module | 초기 책임 |
| --- | --- |
| `access` | 인증 주체, tenant context, 역할과 권한 경계 |
| `venue` | Tenant, Venue, Resource와 운영 Policy |
| `booking` | Slot, Capacity, Reservation과 상태 전이 |
| `waitlist` | 대기 등록, 순서, 승급 제안과 만료 결과 |

이 이름은 초기 책임을 설명하는 논리적 경계입니다. 세부 package와 내부 aggregate는 해당 기능을 구현하기 직전에 구체화합니다.

## 경계 규칙

- module 간 동기 호출은 상대 module이 공개한 **public port**를 통해 수행합니다.
- 이미 발생한 사실을 전달하는 비동기 협력은 명시적인 **domain/application event contract**를 사용합니다.
- 다른 module의 JPA entity, repository 또는 내부 service를 직접 참조하거나 공유하지 않습니다.
- 하나의 JPA entity와 table은 하나의 module이 소유합니다. 다른 module은 식별자, public DTO, port 또는 필요한 read model을 통해 접근합니다.
- module 간 순환 의존을 허용하지 않습니다. 구체적인 의존 방향은 use case가 생길 때 가장 작은 공개 계약으로 정합니다.
- 같은 process와 database를 사용하더라도 module 경계를 통과하는 계약은 테스트 가능한 형태로 유지합니다.

초기에는 단일 database와 local transaction의 장점을 사용합니다. module 분리를 흉내 내기 위해 네트워크 호출, 별도 database 또는 중복 event infrastructure를 만들지 않습니다.

## 대안 검토

### Microservices Architecture로 시작

독립 배포와 장애 격리에는 유리할 수 있지만, 현재는 이를 요구하는 traffic, 팀 구조, 서비스 수준 목표가 없습니다. 초기부터 적용하면 service discovery, 원격 호출 실패, 분산 추적, 데이터 소유권과 event delivery 문제를 제품 핵심보다 먼저 해결해야 하므로 선택하지 않습니다.

### 경계 없는 Layered Monolith

구성은 단순하지만 module 간 entity와 repository 공유를 막기 어렵고 기능이 늘수록 변경 영향 범위가 커집니다. controller/service/repository 계층만으로 도메인 소유권을 표현하지 않고, 도메인 책임을 기준으로 module을 나눕니다.

### Spring Modulith 즉시 도입

Spring Modulith는 module 탐지, 의존 검증, event 기반 협력과 문서화에 유용할 수 있습니다. 그러나 현재는 package convention과 architecture test로도 경계를 표현할 수 있으며, framework를 도입해야 해결되는 문제가 아직 없습니다. 필요성이 입증되기 전에는 의존성을 추가하지 않습니다.

## 결과

- 핵심 예약 transaction과 invariant를 하나의 process와 database 안에서 먼저 검증할 수 있습니다.
- module 경계마다 공개 계약을 설계해야 하므로 초기 코드가 경계 없는 monolith보다 조금 더 명시적입니다.
- module 내부 구현은 외부에 감추고, 향후 분리가 필요할 때 public port와 event contract를 후보 경계로 사용할 수 있습니다.
- 단일 배포 단위이므로 process 장애와 배포 주기는 공유합니다. 현재 단계에서는 이 비용을 수용합니다.
- Spring Modulith, message broker, 분산 transaction을 이 결정의 필수 구성요소로 간주하지 않습니다.

## 재검토 조건

다음과 같은 **측정된 필요**가 생기면 특정 module의 서비스 분리를 검토합니다.

- 한 module의 부하 특성이 달라 전체 애플리케이션을 함께 확장하는 비용이 지속적으로 커지는 경우
- 독립 팀이 module별 release cadence와 운영 소유권을 가져 단일 배포가 반복적인 병목이 되는 경우
- 특정 기능의 장애가 예약 핵심 흐름에 전파되어 process 수준의 failure isolation이 서비스 수준 목표에 필요해진 경우
- 규정, 보안 또는 데이터 수명주기로 인해 독립적인 data ownership과 배포 경계가 필요한 경우
- module 간 호출량, transaction 경계와 event delivery 방식이 관측되어 분리 비용을 추정할 수 있는 경우

재검토는 전체 시스템을 한 번에 분해한다는 뜻이 아닙니다. 문제와 경계가 입증된 module만 대상으로 monolith 내부 개선, 별도 process, 독립 service를 비교합니다.

## 참고 자료

- [Spring Modulith](https://spring.io/projects/spring-modulith/)
