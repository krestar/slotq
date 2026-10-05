# Product application admission의 초기 관찰

#132 구현 전 `62241b921fb2e48caed89d1ecc33b378dbceaa30` 기반 진단에서 Product security
authentication 직후, controller/application 진입 전에 요청을 실제 61초 보류했다.
PRODUCT credential이 만료됐어도 기존 exact GET이 application에 진입할 수 있었다.
Ordinary dev Product HOLD도 15초 client timeout 뒤 재개되어 MySQL에 COMMIT됐다.
따라서 client timeout과 edge authentication만으로 60초 application admission gate 또는
rollback을 증명할 수 없었다. 이 관찰은 guard 이전의 historical FAIL이며 현재 구현의 실패가 아니다.

#132는 기존 PRODUCT credential/current delegation/operation/target을 application 진입 직전에
재검증하는 좁은 guard로 해결했다. ADR decision 변경이나 새로운 일반 Product deadline protocol은
필요하지 않았다. 현재 재현 절차와 지원 한계는 [application guard 요약](../2026-10-05-application-guard/README.md)을 따른다.

Canonical [ProductAdmissionGateDiagnosticTests](../../../../backend/src/test/java/com/slotq/mcp/ProductAdmissionGateDiagnosticTests.java)는
같은 post-security/pre-controller `HandlerInterceptor`에서 실제 61초 지연을 주입한다.
현재 assertion은 expired PRODUCT GET/HOLD의 401와 HOLD durable 세 row 0, ordinary HOLD의
timeout 후 COMMIT을 함께 확인한다. Test-only 빈 registry와 dev credential은 이 진단의 fixture이며
production 세 tool의 통합 검증은 별도 suite가 수행한다.

실행 산출물은 `backend/build/mcp-product-admission/`과 Gradle `build/test-results/`에 생성한다.
Run별 JSON/XML/log/hash는 Git에서 추적하지 않는다. 전체 JVM/DB 정지 중 물리적인 실행 종료를
보장하는 관찰로 확대하지 않는다.
