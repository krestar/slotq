# ADR-0003: Thin SPA에 React, TypeScript, Vite 사용

- 상태: `Accepted`
- 결정일: 2026-08-25

## 맥락

SlotQ에는 Product Backend의 실제 API와 사용자 흐름을 확인할 수 있는 얇은 web client가 필요합니다. 이 client는 예약과 운영 상태를 표현하고 입력을 Product API로 전달하지만, transactional state나 비즈니스 규칙의 source of truth가 되지 않습니다.

현재 필요한 것은 client-side interaction을 제공하는 SPA입니다. 공개 콘텐츠 검색 노출, server-side rendering(SSR), static site generation(SSG), React Server Components 또는 별도 frontend API server 요구는 없습니다. 따라서 이러한 기능을 기본 포함하는 full-stack framework보다 작은 client build 경계가 적합합니다.

Vite는 개발 server와 production asset build를 제공하는 **build tool**입니다. UI component, 상태 모델과 rendering 방식을 정하는 UI framework가 아니므로 Vite 선택만으로 React 선택을 대신할 수 없습니다.

## 결정

- Thin SPA의 UI library로 **React**, 언어로 **TypeScript**, build tool로 **Vite**를 사용합니다.
- frontend source, package manifest, 설정과 테스트는 저장소의 `frontend/` 경계 안에 둡니다.
- package manager는 **npm**을 사용하고 `package-lock.json`을 commit합니다. CI는 lockfile을 기준으로 재현 가능한 설치 명령을 사용합니다.
- Node.js, React와 Vite의 정확한 GA version은 이 ADR에서 미래 버전을 추정하지 않습니다. Frontend scaffold 시점에 각 공식 지원 범위와 상호 호환성을 확인하고 manifest, lockfile과 CI에 고정합니다.
- Vite는 TypeScript를 transpile하지만 type checking은 수행하지 않으므로 `typecheck`를 `vite build`와 분리된 검증 단계로 둡니다. 구체적인 npm script는 scaffold Issue에서 고정합니다.
- Vitest와 component test의 최소 baseline은 scaffold Issue에서 함께 결정합니다. test runner만 설치하고 검증할 동작이 없는 구성을 만들지 않으며, 선택한 baseline은 로컬과 CI에서 같은 명령으로 실행할 수 있어야 합니다.
- E2E test는 실제 Backend API와 연결된 첫 vertical flow가 생길 때 도입 여부와 범위를 결정합니다. 빈 화면이나 정적 placeholder만 있는 scaffold에는 추가하지 않습니다.

## React 선택 근거

Thin SPA는 예약 입력, 비동기 결과, 상태별 화면, 권한에 따른 action과 운영 목록처럼 상호작용하는 UI를 다룹니다. React의 component와 state composition은 이런 동작을 작은 단위로 분리하고 TypeScript 계약으로 연결하기에 적합합니다. 넓은 생태계와 test tooling도 후속 기능에서 선택지를 제공합니다.

React 공식 문서는 framework가 맞지 않는 client SPA를 build tool로 시작할 수 있다고 설명하며 Vite의 `react-ts` template을 예시로 제공합니다. 이 선택은 React가 모든 web UI의 정답이라는 뜻이 아니라, 현재 상호작용 범위와 유지할 toolchain의 균형에 따른 결정입니다.

## Vite 선택 근거

Vite는 native ESM 기반 development server, HMR, React Fast Refresh integration과 production asset build를 제공합니다. React application을 빠르게 실행하고 build하는 역할에 집중하며, routing·data fetching·styling 방식은 포함하지 않습니다.

이 제한은 현재 요구와 맞습니다. Vite를 선택했다는 이유로 UI architecture나 server runtime을 추가하지 않고, 실제 흐름이 요구하는 library만 별도로 검토할 수 있습니다.

## 대안 검토

### Vanilla TypeScript

의존성이 가장 작고 browser platform을 직접 사용할 수 있다는 장점이 있습니다. 그러나 예약 form, 비동기 상태와 반복되는 interactive view가 늘면 DOM 갱신, component lifecycle과 상태 공유 규칙을 자체적으로 설계해야 합니다. 현재 예상되는 상호작용에는 작은 React component model을 사용하는 편이 더 명시적이므로 선택하지 않습니다.

### Next.js 또는 SSR 중심 React framework

SSR, SSG, route-level data loading과 server runtime이 필요한 제품에는 유효합니다. 현재 SlotQ UI에는 SEO, SSR, frontend API server 또는 React Server Components 요구가 없으므로, framework의 server convention과 별도 배포 책임을 먼저 도입하지 않습니다. 공개 콘텐츠 검색 노출이나 server rendering이 실제 요구가 되면 재검토합니다.

### Vue 또는 Svelte

두 대안 모두 component 기반 UI와 좋은 developer experience를 제공하며 기술적으로 사용할 수 있습니다. 현재 범위에서는 React와 비교해 도입해야 할 명확한 제품상 이점이 확인되지 않았고, React를 사용하면 하나의 널리 문서화된 component/test 생태계에 집중할 수 있어 선택하지 않습니다. 이 결정은 다른 UI library의 품질에 대한 부정적 판단이 아닙니다.

### Create React App

React 공식 설치 문서에서 deprecated로 명시되어 있으므로 신규 scaffold에 사용하지 않습니다.

## 지금 도입하지 않는 항목

다음 도구는 React/Vite의 필수 구성요소가 아니며, 이름만 있는 scaffold에 선제적으로 추가하지 않습니다.

- **Tailwind CSS**: styling 규모와 반복 문제가 확인되고 utility 기반 convention의 이점이 비교될 때 검토합니다.
- **Design system/component kit**: 여러 화면에서 반복되는 component, token과 accessibility 규칙이 생길 때 검토합니다.
- **Router**: 둘 이상의 독립 route, deep link, navigation history 또는 route-level 권한 요구가 생길 때 검토합니다.
- **Server-state query library**: caching, deduplication, retry, invalidation과 shared server state가 수동 fetch보다 복잡해질 때 검토합니다.

## 결과

- Frontend는 `frontend/` 안의 독립 npm package와 static asset build로 관리합니다.
- Backend와 Frontend의 build 및 CI 명령은 분리하되, 실제 vertical flow에서는 API contract를 함께 검증합니다.
- TypeScript 오류와 production bundling 실패는 서로 다른 검증 신호로 확인할 수 있습니다.
- SSR, SEO, frontend API server와 E2E infrastructure는 현재 범위에서 제외됩니다.
- 새로운 UI dependency는 해결할 문제, 기존 방식의 한계와 검증 방법을 설명한 뒤 추가합니다.

## 재검토 조건

다음 중 하나가 관측되면 이 결정을 다시 검토합니다.

- 공개 콘텐츠에 SEO, 빠른 initial render, SSR 또는 SSG가 필요한 경우
- frontend 전용 API aggregation, server action 또는 secret을 다루는 server runtime이 필요한 경우
- SPA의 routing·data fetching·rendering 요구가 결합되어 framework convention이 더 단순한 경우
- React component model이나 bundle 특성이 측정된 성능·유지보수 요구를 충족하지 못하는 경우
- Node.js, React 또는 Vite의 지원 정책과 호환성 변화로 현재 toolchain을 유지하기 어려운 경우

## 참고 자료

- [Vite Getting Started](https://vite.dev/guide/)
- [Vite Features](https://vite.dev/guide/features)
- [React: Build a React app from Scratch](https://react.dev/learn/build-a-react-app-from-scratch)
- [React Installation](https://react.dev/learn/installation)
