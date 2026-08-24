# ADR-0001: Backend 언어로 Java 사용

- 상태: `Accepted`
- 결정일: 2026-08-25

## 맥락

Backend scaffold를 만들기 전에 하나의 기본 언어와 재현 가능한 build 진입점을 정해야 합니다. 후보는 Java와 Kotlin이며, 둘 다 Spring 생태계에서 사용할 수 있습니다.

SlotQ의 초기 복잡성은 언어 자체보다 예약 상태 전이, 수용량 invariant, 트랜잭션, 동시성, persistence 경계에 있습니다. 따라서 초기 선택은 이 문제들을 명시적으로 드러내고 Spring/JPA의 기본 관례를 적은 추가 설정으로 사용할 수 있어야 합니다.

## 결정

- Backend 기본 언어로 **Java 21 LTS**를 사용합니다.
- Spring Boot의 정확한 버전은 이 ADR에서 미래 버전을 추정해 고정하지 않습니다. Backend scaffold를 만드는 시점에 공식 지원 중인 **GA patch**를 확인하고 build에 고정합니다.
- 저장소에는 **Maven Wrapper**를 포함하고, 로컬과 CI는 wrapper를 동일한 진입점으로 사용합니다.
- scaffold PR은 선택한 JDK, Spring Boot, Maven Wrapper 버전과 지원 근거를 함께 기록합니다.

## 대안 검토

### Kotlin

Kotlin은 null safety, 간결한 문법, data/sealed class, Java 상호운용성 등 분명한 장점이 있습니다. 도메인 모델을 짧고 표현력 있게 작성할 가능성도 있습니다.

다만 현재 단계에서는 다음 비용이 핵심 문제 학습과 검증을 분산시킬 수 있습니다.

- Kotlin compiler와 Spring/JPA용 compiler plugin 조합을 함께 관리해야 합니다.
- JPA proxy와 entity 생성을 위해 `all-open`, `no-arg` 또는 `kotlin-jpa` 같은 설정과 관례를 정확히 유지해야 합니다.
- Kotlin `data class`의 자동 `equals`, `hashCode`, `toString`은 식별자가 늦게 할당되고 lazy association을 가질 수 있는 JPA entity에 그대로 적용하기 어렵습니다.
- nullable type, `lateinit`, collection 초기화, entity 생성자와 proxy 동작에 대한 팀 관례를 별도로 정해야 합니다.
- Java 중심 Spring/JPA 자료와 Kotlin 관례를 동시에 비교하는 학습 비용이 생깁니다.

이는 Kotlin이 부적합하다는 판단이 아니라, 현재 SlotQ에서 얻을 이점보다 추가 toolchain과 persistence 관례의 비용이 더 크다는 판단입니다.

### Java 최신 LTS 또는 비 LTS를 즉시 선택

새 언어 기능만을 이유로 초기 baseline을 높이지 않습니다. Java 21은 필요한 언어 기능과 Spring 생태계 호환성을 제공하는 안정적인 LTS 기준입니다. 향후 JDK 변경은 지원 기간, 의존성 호환성, 운영 환경과 측정된 이점을 근거로 별도 ADR에서 다룹니다.

### Gradle

Gradle도 유효한 선택이지만, 초기에는 Maven의 명시적인 lifecycle과 작은 설정 면적이 더 적합합니다. Maven Wrapper로 build tool 버전과 실행 경로를 통일하고, build 최적화 요구가 실제로 생기기 전에는 추가 DSL과 plugin 선택을 만들지 않습니다.

## 결과

- 하나의 언어 관례와 Spring/JPA 기본 경로에 집중할 수 있습니다.
- Java의 장황함은 작은 class, 명확한 책임 분리와 필요한 경우 record·sealed type 같은 Java 21 기능으로 관리합니다.
- Kotlin source나 Kotlin 전용 build plugin을 부분적으로 추가하지 않습니다. 도입이 필요하면 경계와 migration 비용을 포함한 새 ADR을 작성합니다.
- Spring Boot 버전은 scaffold 시점의 공식 지원 상태를 확인해야 하므로, scaffold 전까지는 아직 확정되지 않습니다.

## 재검토 조건

다음 중 하나가 관측되면 언어 선택을 다시 검토합니다.

- Java의 반복 코드가 도메인 규칙의 가독성이나 변경 속도를 지속적으로 저해하고 측정 가능한 유지보수 비용을 만든 경우
- Kotlin이 제공하는 기능이 구체적인 제품 요구를 단순화하고 compiler/plugin/JPA 비용보다 큰 이점을 주는 경우
- 여러 개발자가 참여해 Kotlin 경험과 일관된 coding convention을 유지할 수 있게 된 경우
- Spring, JPA provider, build plugin 조합에서 Kotlin 운용 기준과 migration 경로를 검증한 경우
- 지원 JDK 또는 배포 환경의 변경으로 Java 21 baseline 유지가 부적절해진 경우

## 참고 자료

- [Oracle Java SE Support Roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)
- [Spring Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot Kotlin Support](https://docs.spring.io/spring-boot/reference/features/kotlin.html)
- [Kotlin No-arg compiler plugin](https://kotlinlang.org/docs/no-arg-plugin.html)
- [Apache Maven Wrapper](https://maven.apache.org/wrapper/)
