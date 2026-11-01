(() => {
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (reduceMotion) return;

  const overlay = document.createElement('div');
  overlay.className = 'page-transition-overlay';
  overlay.setAttribute('aria-hidden', 'true');
  overlay.innerHTML = `
    <div class="page-transition-content">
      <span class="page-transition-brand">P</span>
      <span class="page-transition-label">Đang chuyển trang</span>
      <span class="page-transition-progress"></span>
    </div>
  `;
  document.documentElement.append(overlay);

  let navigating = false;
  document.addEventListener('click', (event) => {
    if (navigating || event.defaultPrevented || event.button !== 0
        || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
      return;
    }

    const target = event.target;
    const link = target instanceof Element ? target.closest('a[href]') : null;
    if (!link || link.target || link.hasAttribute('download')
        || link.relList.contains('external')) {
      return;
    }

    const destination = new URL(link.href, window.location.href);
    if (destination.origin !== window.location.origin
        || (destination.pathname === window.location.pathname
          && destination.search === window.location.search)) {
      return;
    }

    event.preventDefault();
    navigating = true;
    document.body.classList.add('page-transition-leaving');
    overlay.classList.add('is-active');
    window.setTimeout(() => {
      window.location.assign(destination.href);
    }, 460);
  });
})();
