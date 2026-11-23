(() => {
  const sidebar = document.getElementById('sidebar');
  if (!sidebar || sidebar.dataset.projectorControls) return;
  const remote = sidebar.querySelector('#sideRemote');
  const tutorials = sidebar.querySelector('#sideTutorials');
  const streaming = sidebar.querySelector('[data-view="streaming"]');
  const tools = sidebar.querySelector('[data-view="tools"]');
  if (!remote || !tutorials || !streaming || !tools) return;

  for (const link of [remote, tutorials]) {
    link.hidden = true;
    link.style.display = 'none';
  }
  streaming.querySelector('.side-label').textContent = 'App Updates';

  const focusStyles = document.createElement('style');
  focusStyles.textContent = [
    '#sidebar [data-view="all"], #stage [data-chipview="all"] { display: none; }',
    '#sidebar .side-item, #stage .chip, #stage .card, #stage .card-art { transition: none; }',
    '#stage .row, #stage .hero, #stage .ent-panel-lock { animation: none; }',
    '#stage .grid { align-items: flex-start; }',
    '#stage .card:focus-visible, #stage .card.nav-focus { transform: none; }',
    '#stage .rail { scroll-behavior: auto; }'
  ].join('\n');
  document.head.append(focusStyles);

  sidebar.addEventListener('keydown', event => {
    const up = event.key === 'ArrowUp' || event.keyCode === 38;
    const down = event.key === 'ArrowDown' || event.keyCode === 40;
    if ((!up && !down) || event.defaultPrevented) return;
    if (document.querySelector('.modal:not(.hidden), #entOverlay:not(.hidden)')) return;

    const active = document.activeElement;
    if (!active.matches('.side-item.nav-focusable')) return;

    event.preventDefault();
    event.stopPropagation();
    const sibling = up ? 'previousElementSibling' : 'nextElementSibling';
    let next = active[sibling];
    while (next && (!next.matches('.side-item.nav-focusable') || next.hidden || next.disabled
      || !next.getClientRects().length)) {
      next = next[sibling];
    }
    if (!next) return;

    next.focus({preventScroll: true});
    const itemBounds = next.getBoundingClientRect();
    const sidebarBounds = sidebar.getBoundingClientRect();
    if (itemBounds.top < sidebarBounds.top) {
      sidebar.scrollTop += itemBounds.top - sidebarBounds.top;
    } else if (itemBounds.bottom > sidebarBounds.bottom) {
      sidebar.scrollTop += itemBounds.bottom - sidebarBounds.bottom;
    }
  });

  function insertControl(before, id, label, open, iconPath) {
    const button = document.createElement('button');
    button.id = id;
    button.type = 'button';
    button.className = streaming.className;
    const icon = streaming.querySelector('svg').cloneNode(false);
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', iconPath);
    icon.setAttribute('aria-hidden', 'true');
    icon.append(path);
    const title = document.createElement('span');
    title.className = 'side-label';
    title.textContent = label;
    button.append(icon, title);
    button.addEventListener('click', () => {
      if (!open()) window.alert(label + ' could not be opened.');
    });
    before.before(button);
  }

  const bridge = window.AndroidBridge;
  if (typeof bridge.openProjectorCare === 'function' && typeof renderAppView === 'function') {
    const renderBeforeCare = renderAppView;
    window.renderAppView = function(view) {
      let html=renderBeforeCare(view);
      if (view === 'tools' && typeof loadModel === 'function' && loadModel() === 'a5pro') {
        const tile='<button type="button" class="card nav-focusable" onclick="AndroidBridge.openProjectorCare()"><div class="card-art" style="background:radial-gradient(circle at 25% 20%,#57d8c1,transparent 60%),linear-gradient(145deg,#167d91,#152d59)"><div style="position:relative;z-index:1;display:flex;flex-direction:column;align-items:center;gap:16px;color:#efffff"><svg width="70" height="70" viewBox="0 0 64 64" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="m29 35 22-24a4 4 0 0 1 6 6L33 39M17 29l20 17c-9 13-22 12-30 6 8-4 7-14 10-23ZM16 45l-4 8M24 48l-3 8M10 11v10M5 16h10M40 4v6M37 7h6"/></svg><span style="font-size:29px;font-weight:800">CLEAN</span></div></div><div class="card-title">Clean</div><div class="card-blurb">RAM &amp; storage</div><div class="card-sub">Check space. Tidy up.</div></button>';
        html=html.replace('<div class="grid">','<div class="grid">'+tile);
      }
      return html;
    };
    if (typeof currentView !== 'undefined' && currentView === 'tools') refreshView();
  }

  if (typeof bridge.openNetworkSpeedChecker === 'function') {
    insertControl(tools.nextElementSibling, 'sideNetworkSpeed', 'Network speed',
      () => bridge.openNetworkSpeedChecker(),
      'M3 17a9 9 0 0 1 18 0 M12 17l5-7 M6 17h1 M17 17h1');
  }
  if (bridge.hasPictureSettings()) {
    const openPanel = () => bridge.openPictureAndSoundSettings();
    insertControl(tools, 'sidePictureSettings', 'Picture & Sound Settings', openPanel,
      'M3 5h18v14H3z M5 16l4-4 3 3 3-4 4 5');

    if (typeof bridge.hasNavigationSoundControl === 'function' && bridge.hasNavigationSoundControl()) {
      insertControl(tools, 'sideOptimiseControls', 'Optimise A5 Pro', () => {
        bridge.showProjectorOptimisation('a5pro');
        return true;
      }, 'M13 2 4 14h7l-1 8 10-13h-7z');
      const optimise = document.getElementById('sideOptimiseControls');
      function updateOptimiseVisibility() {
        const visible = typeof loadModel === 'function' && loadModel() === 'a5pro';
        if (optimise.hidden === visible) optimise.hidden = !visible;
      }
      new MutationObserver(updateOptimiseVisibility).observe(sidebar,
        {attributes: true, subtree: true});
      updateOptimiseVisibility();
    }

    const view = document.getElementById('viewBody');
    function addShortcutSetup() {
      if (typeof bridge.showUpdateNotifications === 'function' && view.querySelector('.panel-row [data-action="change-model"]') && !view.querySelector('#updateNotificationSettings')) {
        const notice=document.createElement('div');notice.id='updateNotificationSettings';notice.className='panel-row';notice.innerHTML='<div class="panel-label"><b>Update notifications</b><span>Get alerts while Projector Guy is closed</span></div><button class="panel-btn nav-focusable" type="button">Open</button>';notice.querySelector('button').onclick=()=>bridge.showUpdateNotifications();view.appendChild(notice);
      }
      const changeModel = view.querySelector('.panel-row [data-action="change-model"]');
      if (!changeModel || view.querySelector('#pictureShortcutSetup')) return;
      const row = document.createElement('div');
      row.id = 'pictureShortcutSetup';
      row.className = 'panel-row';
      row.innerHTML = '<div class="panel-label"><b>Picture and Sound shortcut</b>'
        + '<span>Set up the Menu button on your remote</span></div>'
        + '<button class="panel-btn nav-focusable" type="button">Open</button>';
      row.querySelector('button').addEventListener('click', () => {
        if (!bridge.openPictureShortcutSetup()) window.alert('Shortcut setup could not be opened.');
      });
      changeModel.closest('.panel-row').after(row);
    }
    if (view) {
      new MutationObserver(addShortcutSetup).observe(view, {childList: true});
      addShortcutSetup();
    }
  }
  sidebar.dataset.projectorControls = 'true';
})();
