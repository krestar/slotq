# ADR-0004: Java 25 LTS를 Backend 기준선으로 사용

- 상태: `Accepted`
- 결정일: 2026-08-28
- 대체 대상: [ADR-0001](0001-use-java.md)

## 맥락

ADR-0001은 Backend 언어로 Java를 선택하고 초기 기준선을 Java 21 LTS로 정했습니다.
이후 Backend scaffold와 CI가 Spring Boot 4.1.1, Gradle Wrapper 9.7.1 조합으로 준비됐으며,
아직 persistence와 Product 도메인 구현이 쌓이지 않아 JDK 기준선을 변경하는 비용이 낮습니다.

Spring Boot 4.1.1은 Java 17부터 26까지와 Gradle 8.14 이상 및 9.x를 지원합니다.
Gradle 9.7.1은 Java 25 toolchain과 Java 25에서의 Gradle 실행을 지원합니다. 따라서 현재
framework와 build tool을 변경하지 않고 Java 25를 공식 build 및 runtime 기준선으로
사용할 수 있습니다.

## 결정

- Backend 언어는 Java를 유지하고 공식 build 및 runtime 기준선을 **Java 25 LTS**로 변경합니다.
- Gradle Java toolchain의 language version을 25로 고정합니다.
- CI는 Temurin JDK 25에서 test와 build를 실행합니다.
- 로컬 개발에는 Gradle이 탐지할 수 있는 JDK 25가 필요하지만 JDK vendor는 고정하지 않습니다.
- Java 21과 25의 compatibility matrix는 운영하지 않고 Java 25 하나만 공식 기준선으로 검증합니다.
- Gradle Wrapper 9.7.1과 Spring Boot 4.1.1은 이번 결정에서 변경하지 않습니다.

## 대안 검토

### Java 21 유지

Java 21은 여전히 안정적인 LTS이며 현재 scaffold를 실행하기에 충분합니다. 다만 아직
도메인 코드와 persistence 의존성이 거의 없는 현재가 전환 비용이 가장 낮고, Java 25가
현재 Spring Boot와 Gradle의 공식 지원 범위에 포함되므로 이후 구현을 하나의 최신 LTS
기준선에서 시작하기로 했습니다.

### Java 21과 25 동시 지원

두 버전의 CI matrix는 호환성 범위를 넓히지만 SlotQ는 배포용 library가 아니라 하나의
애플리케이션입니다. 두 runtime을 지원할 제품 요구가 없으므로 build 시간과 유지보수
비용을 추가하지 않습니다.

### 로컬 JDK vendor를 Temurin으로 고정

CI는 재현 가능한 환경을 위해 Temurin 25를 사용합니다. 로컬에서는 Java 25 규격을
충족하는 toolchain이면 충분하므로 vendor를 제한하지 않습니다. vendor 차이로 재현되는
문제가 확인되면 그 증거를 바탕으로 다시 검토합니다.

## 결과

- Backend source, test와 실행 artifact는 Java 25를 기준으로 생성하고 검증합니다.
- JDK 25가 설치되지 않았거나 Gradle에서 탐지되지 않는 로컬 환경은 Backend build를
  실행할 수 없습니다.
- 시스템 기본 JDK가 21인 개발 환경도 JDK 25를 함께 설치하고 Gradle toolchain으로
  선택할 수 있으므로 전역 `JAVA_HOME`이나 `PATH`를 변경할 필요가 없습니다.
- 향후 의존성은 Java 25 지원 여부를 확인한 뒤 도입합니다.

## 재검토 조건

다음 중 하나가 관측되면 기준선을 다시 검토합니다.

- Spring Boot, Gradle, JPA provider 또는 주요 운영 의존성이 Java 25를 공식 지원하지 않는 경우
- 배포 환경이나 조직 표준이 Java 25 runtime을 제공하지 않는 경우
- 로컬과 CI의 JDK vendor 차이로 재현 가능한 동작 차이가 발생하는 경우
- 다음 LTS 전환의 지원 기간, 의존성 호환성과 운영 이점이 migration 비용보다 커진 경우
- Java 21 호환 artifact를 제공해야 하는 구체적인 배포 또는 소비자 요구가 생긴 경우

## 참고 자료

- [Spring Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Gradle Java Compatibility](https://docs.gradle.org/9.7.1/userguide/compatibility.html)
- [Eclipse Temurin Support](https://adoptium.net/support/)
