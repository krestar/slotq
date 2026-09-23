# SlotQ Frontend

SlotQ Product API의 실제 사용자 흐름을 검증하는 React·TypeScript·Vite 기반 thin SPA다.
Customer는 active Venue 선택, Availability 조회, HOLD, confirm/cancel과 최신 Reservation
재조회를 단계별 guided flow에서 수행한다. Customer Waitlist는 같은 시간대의 적합한 Table
수요 등록, 자기 Entry·Offer의 exact 조회와 서버 허용 작업을 제공한다. Venue Waitlist는 서버가
발견한 Venue scope의 순서·Slot 적합성·Offer 업무 상태를 조회한다. Product 상태와 deadline은
Backend 응답만 사용한다.

## Runtime baseline

- Node.js 24.20.0 LTS (`Krypton`)
- npm 11.19.0
- React 19.2.8
- Vite 8.2.2

Node.js 24는 scaffold 시점의 LTS release이며, `.nvmrc`에 검증 patch를 기록한다. Vite
8.2.2는 Node.js `^20.19.0 || >=22.12.0`을 지원한다. 현재 test DOM인 jsdom 30은 Node.js
24.15.0 이상을 요구하므로 `package.json`은 Node.js 24 LTS 범위로 제한한다.

- [Node.js releases](https://nodejs.org/en/about/previous-releases)
- [Vite Getting Started](https://vite.dev/guide/)

## Install and verify

```bash
cd frontend
npm ci
npm run typecheck
npm test
npm run build
```

- `dev`: Vite development server 실행
- `typecheck`: TypeScript가 application과 Vite config를 별도로 검증
- `test`: Vitest와 jsdom에서 smoke test 실행
- `build`: Vite production asset build 실행

Vite는 TypeScript syntax를 transpile하지만 type checking을 대신하지 않는다. 따라서
`typecheck`와 `build`는 독립적인 검증 단계로 유지한다.

## Product API base URL

로컬 기본값은 `http://localhost:8080`이다. 다른 API origin을 사용할 때 `.env.example`을
참고해 `frontend/.env.local`에 다음 값을 설정한다.

```dotenv
VITE_API_BASE_URL=http://localhost:8080
VITE_LOCAL_AUTH_FIXTURE=customer-a
```

`VITE_*` 변수는 production build 결과에 포함되어 Browser에 공개된다. Password, API Key,
access token, private endpoint와 그 밖의 Secret을 절대 넣지 않는다. Secret이 필요한 연동은
Product Backend가 소유하고 Frontend에는 공개 가능한 URL과 식별자만 전달한다.

`VITE_LOCAL_AUTH_FIXTURE`는 local Vite 실행에서만 사용하는 공개 fixture 선택자다. Browser는
시작할 때 Backend의 runtime bootstrap으로 process-bound credential을 받고 JavaScript memory에만
보관한다. token은 URL, log, localStorage, sessionStorage, IndexedDB, cookie, `.env` 또는 build
artifact에 저장하지 않는다. 보호 API가 `401`을 반환하면 기존 credential만 폐기하고 요청 자체는
재전송하지 않는다. 이후 사용자가 발생시킨 다음 보호 요청에서 runtime bootstrap을 다시 수행한다.
production build는 이 fixture 설정이나 dev bootstrap에 의존하지 않는다.

Customer mutation은 자동 retry하지 않으며 in-flight 중 중복 submit과 surface 전환을 막는다.
HOLD 최초 submit 직전에 UUID key와 immutable `venueId + slotInventoryId + partySize` attempt를
생성한다. network/30초 timeout/5xx/성공 응답 유실·파싱 불가 뒤에는 사용자가 같은 요청을
명시적으로 재시도할 때만 같은 `Idempotency-Key`와 payload를 사용한다. stable status/code의
4xx는 종료하며, 성공·definitive 오류·명시적 포기 이후 새 submit은 같은 payload라도 새 key다.

Unresolved HOLD는 App의 현재 runtime memory에만 보존해 Customer surface unmount와 내부
navigation을 지나 복구한다. 검색 조건 변경은 포기 전까지 잠그며 Availability refresh는
이전 command 결과를 확정하지 않는다. 보존 상한은 최초 submit부터 23시간으로 #17의 성공
완료부터 24시간 server retention보다 짧다. retry 직전에 wall-clock과 monotonic clock의
23시간 경계를 모두 확인한다. sleep 중 monotonic clock이 멈추어도 wall-clock으로 차단하고,
시스템 시계가 뒤로 조정되어도 monotonic 경계는 연장하지 않는다. 만료 또는 auth invalidate 시
key/context를 폐기하며 결과를 실패로 단정하지 않는다.
포기는 서버 예약 취소가 아니다.

Waitlist 등록은 HOLD와 별도의 intent다. UUID `Idempotency-Key`와
`venueId + slotInventoryId + partySize`를 App memory에만 보관하며 HOLD의 23시간
client 보존 상한을 적용하지 않는다. 응답을 받지 못하면 같은 key의 registration-request GET을
먼저 제공하고, 404도 원 요청 실패로 확정하지 않는다. 사용자가 누른 경우에만 같은 key·body를
다시 보낸다. Entry cancel과 Offer accept/reject에는 key를 붙이지 않는다. unknown 또는
409 뒤에는 원 Entry/Offer exact GET으로 서버 상태를 확인한다. PENDING이 유지되면 서버가
허용한 동일 action만 명시적으로 재시도할 수 있다. Poll과 deadline 표시는 read-only이며
mutation이나 local expiry 전이를 일으키지 않는다.

Waitlist URL은 비밀이 아닌 `view`, `venueId`, `date`, `entryId`, `offerId` 선택값만 담는다.
Reload와 auth invalidation은 미해결 key/action intent를 폐기한다. 재인증 후 known ID의 exact
GET 또는 Venue-local date 자기 목록으로 다시 발견하며 이전 intent의 성공을 추측하지 않는다.
Owner/Manager Waitlist 조회 범위와 작업 가능 여부는 서버의 scoped response와
`allowedActions`로만 판단한다. Staff의 기존 Reservation 업무는 유지한다.

실제 MySQL 8.4.11·Backend·브라우저에서 확인한 M4 흐름과 재현 절차는
[Waitlist browser evidence](../docs/architecture/waitlist-browser-evidence.md)에 기록했다.

Reload는 새 runtime auth session이다. 기존 계약에는 인증 주체 연속성 증명이 없으므로 이전
attempt를 저장·복구하거나 재전송하지 않는다. fixtureKey를 Customer identity 근거로 쓰지 않으며
token/Principal/Tenant/Role/grant와 HOLD key/context 모두 browser persistent storage에 저장하지 않는다.
Reload 전에 결과를 받지 못한 예약은 생성됐을 수 있으며 최신 Availability를 다시 조회한다.

HOLD response의 최초 Reservation identity, canonical Location과 retry 시점 server effective
representation을 사용한다. confirm/cancel에는 key를 보내지 않으며 결과 불명확 시 기존 exact
Reservation GET으로만 재조정하고 context/navigation lock을 유지한다. Browser CORS는 HOLD key
preflight와 Location 읽기를 지원한다.

## Accessibility baseline

- `header`, `main`, `footer` landmark와 페이지별 하나의 명확한 `h1`을 사용한다.
- Keyboard 사용자가 본문으로 이동할 수 있는 skip link와 눈에 보이는 focus indicator를
  유지한다.
- 오류는 색상만으로 구분하지 않고 구체적인 텍스트를 제공한다. 즉시 알려야 하는 오류에는
  `role="alert"`를 사용하고, 여러 field 오류는 focus 가능한 summary로 연결한다.
- 비동기 상태가 추가되면 loading, empty, success와 error를 보조 기술이 구분할 수 있게
  이름과 상태를 제공한다.

Router, server-state library, Design System과 E2E framework는 실제 Product flow가 요구할
때 별도 Issue에서 검토한다.
