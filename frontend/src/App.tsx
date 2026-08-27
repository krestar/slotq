export function App() {
  return (
    <>
      <a className="skip-link" href="#main-content">
        본문으로 바로가기
      </a>

      <header className="site-header">
        <span className="brand" aria-label="SlotQ">
          SlotQ
        </span>
      </header>

      <main id="main-content" className="app-shell" tabIndex={-1}>
        <section className="intro" aria-labelledby="page-title">
          <p className="eyebrow">Frontend foundation</p>
          <h1 id="page-title">SlotQ</h1>
          <p className="intro-copy">
            Product API의 실제 사용자 흐름을 검증하기 위한 웹 클라이언트 기반입니다.
          </p>
        </section>
      </main>

      <footer className="site-footer">
        <small>Build the product first. Add intelligence on top.</small>
      </footer>
    </>
  )
}
