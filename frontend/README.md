# SlotQ Frontend

SlotQ Product API의 실제 사용자 흐름을 검증하는 React·TypeScript·Vite 기반 thin SPA다.
Customer는 active Venue 선택, Availability 조회, HOLD, confirm/cancel과 최신 Reservation
재조회를 단계별 guided flow에서 수행한다. Product 상태와 deadline은 Backend 응답만 사용한다.

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

Customer mutation은 자동 retry하지 않으며 in-flight 중 같은 action의 중복 submit만 막는다.
HOLD 응답이 유실되면 Availability를, Reservation command 결과가 불명확하면 Reservation GET을
사용자가 명시적으로 재조회한다. M1 Frontend는 `Idempotency-Key` 보장을 제공하지 않는다.

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
