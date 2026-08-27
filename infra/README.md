# Infrastructure

이 디렉터리는 SlotQ의 로컬·운영 Infrastructure 설정 경계다.

초기 MySQL 8.4 LTS container와 local configuration은 Issue #6에서 추가한다. Database
volume, generated data, 실제 credential과 Secret은 commit하지 않는다. Flyway schema
migration은 Infrastructure가 아니라 이를 사용하는 Backend가
`backend/src/main/resources/db/migration/`에서 소유한다.
