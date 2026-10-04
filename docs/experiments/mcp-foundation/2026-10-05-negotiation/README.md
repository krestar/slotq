# PR #137 initialization negotiation correction evidence

수정 전 PR HEAD는 `b0481fbd7cb21109657f9565547476b475ef5820`, remote main은
`40e764bf0c7d23a6a3fd98c274ca634f60a1ac77`이다. 기존 PR 브랜치에서 #131의 protocol
negotiation만 보정했다. Java 25.0.4 / Boot 4.1.1 / Jackson 3.1.5 / Gradle 9.7.1과
`mcp-core` / `mcp-json-jackson3:2.0.1` 조합을 유지한다. 실행일은 KST 2026-10-05이며
JUnit의 UTC timestamp와 혼동하지 않는다.

## 원인 판정과 근거

- [MCP 2025-11-25 Lifecycle — Version Negotiation](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#version-negotiation): requested revision을 지원하지 않으면 서버가 지원하는 revision을 반환하고 client가 수용 여부를 결정한다. Initialize를 바로 PROTOCOL error로 거부했던 검사는 이 계약과 충돌했다.
- [Streamable HTTP — Protocol Version Header](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#protocol-version-header): 이후 request는 negotiated revision header를 사용하며 invalid/unsupported header는 HTTP 400이다. 기존 header 검사는 변경하지 않았다.
- [Lifecycle — Capability Negotiation](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#capability-negotiation): client capability와 server capability는 서로 다른 optional feature를 나타낸다. ADR-0009/#130의 tools-only 계약은 지원하지 않는 server feature의 광고·사용 금지이며 client의 non-empty capability를 무조건 거부하는 계약은 없다.
- SDK 2.0.1의 [McpAsyncServer initialize handler](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/mcp-core/src/main/java/io/modelcontextprotocol/server/McpAsyncServer.java), [server protocol tests](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/mcp-test/src/test/java/io/modelcontextprotocol/server/McpServerProtocolVersionTests.java)는 unsupported proposal에 supported revision을 제안한다. [LifecycleInitializer](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/mcp-core/src/main/java/io/modelcontextprotocol/client/LifecycleInitializer.java)와 [client protocol tests](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/mcp-test/src/test/java/io/modelcontextprotocol/client/McpClientProtocolVersionTests.java)는 서버 응답을 client의 지원 목록과 비교한다. Upstream test suite를 실행했다는 주장은 아니다.

Issue #131, completed #130/merged #136, ADR-0009와 현재 architecture §3을 직접 대조했다.
기존 두 negative assertion은 `clientInfo`도 비어 있어 version/capability만 검증하지 못했다.
유효한 clientInfo로 분리한 두 regression이 수정 전 실제 TLS Boot/Tomcat/MySQL endpoint에서
실패한 결과를 [pre-fix-reproduction.json](pre-fix-reproduction.json)과
[Gradle output](pre-fix-reproduction.txt)에 보존한다. 이 실패 실행을 PASS로 세지 않는다.
공유용 Gradle output은 trailing whitespace만 제거했으며 원본 stdout hash와 정규화 provenance는
reproduction JSON에 기록했다. Test 결과와 기존 역사적 raw evidence는 바꾸지 않았다.

## 보정 범위와 protocol regression

서버의 유일한 supported revision은 계속 `2025-11-25`다. Initialize의 version은 string,
capabilities는 object여야 하며 envelope/clientInfo와 기존 body/depth/string bounds를 유지한다.
Client capability는 사용하지 않는 bounded metadata이고 authority가 아니다. 응답의 server
capability는 계속 `tools: {listChanged: false}`뿐이다. 다른 revision의 실제 semantics,
Tasks/roots/sampling/elicitation 서버 기능, OAuth, 후속 Product/corpus/retrieval은 추가하지 않았다.

실제 HTTPS regression:

- Requested `2025-11-25`, unsupported `1999-01-01`, unknown string 모두 initialize result `2025-11-25`; 이를 수용한 client의 initialized 202 → profile-filtered list → structured call 성공.
- 반환 revision을 수용하지 않는 harness는 initialized를 보내지 않고 session을 종료한다. Premature list는 계속 PROTOCOL, 종료 뒤에는 404다.
- 이후 wrong/unsupported/invalid header와 missing required header는 400. 기존 GET/DELETE header, missing session 400, terminated/foreign session 404와 reinitialize regression도 유지한다.
- Client roots/sampling/elicitation/tasks 광고를 받아도 server capability는 tools only, unsupported tasks/resources/prompts method는 -32601이다.
- 실제 stable SDK 2.0.1 client는 roots capability를 광고하며 initialize → initialized → list/call, structured success/isError, forbidden direct call과 unknown tool을 실행한다. SDK transport의 redirect/resumption 설정은 기존 그대로다.
- Malformed version/capability type, invalid clientInfo는 PROTOCOL이며 session을 발급하지 않는다.

Final clean build에서 export한 synthetic raw responses는 [protocol-negotiation.json](protocol-negotiation.json),
[client-capabilities.json](client-capabilities.json), [session-lifecycle.json](session-lifecycle.json),
[interoperability.json](interoperability.json)에 남긴다. Credential/session ID/raw prompt는 포함하지 않는다.
[2026-10-04 evidence](../2026-10-04/README.md)의 raw data와 hash는 당시 결과 그대로 보존한다.

## 실행 검증과 provenance

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
Set-Location backend
.\gradlew.bat test --tests 'com.slotq.mcp.*' --tests 'com.slotq.architecture.*' --tests 'com.slotq.observability.web.RequestCorrelationFilterTests' --tests com.slotq.AuthWebIntegrationTests --tests com.slotq.HoldIdempotencyScopeMigrationTests --tests com.slotq.SlotqApplicationTests --offline --no-daemon --console=plain
.\gradlew.bat test --offline --no-daemon --console=plain
.\gradlew.bat clean build --offline --no-daemon --console=plain
```

최종 결과는 [verification.json](verification.json)에 기록한다.

| 실행 | 결과 |
| --- | --- |
| Targeted MCP/Auth/migration/startup + architecture | 13 suite / 78 case, failure/error/skip 0, 1분 29초 PASS |
| Architecture (targeted와 두 전체 실행에 포함) | 5 suite / 28 case, failure/error/skip 0 PASS |
| 전체 Backend test | 92 suite / 737 case, 728 실행 성공, 기존 opt-in 9 skip, failure/error 0, 16분 16초 PASS |
| clean build (새 artifact + 전체 test 재실행) | 92 suite / 737 case, 728 실행 성공, 기존 opt-in 9 skip, failure/error 0, 16분 25초 PASS |

43개 source hash를 마지막 targeted 이후 고정하고 두 최종 실행 뒤 일치함을 확인했다. 새 Boot JAR의
SDK artifact 포함과 test fixture class 부재도 확인했다. Auth/delegation/quota/audit
production source는 변경하지 않았다. Skip 9개는 기존 capacity diagnostic 3개와 외부 Kafka
secure/fault/evidence fixture 6개다. 별도 fixture의 fresh 성공을 주장하지 않으며 #131 regression에는
skip이 없다.
Remote CI를 추적하거나 merge하지 않는다.
