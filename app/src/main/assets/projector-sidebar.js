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
  if (bridge.hasPictureSettings()) {
    const openPanel = () => bridge.openPictureAndSoundSettings();
    insertControl(tools, 'sidePictureSettings', 'Picture Settings', openPanel,
      'M3 5h18v14H3z M5 16l4-4 3 3 3-4 4 5');
    insertControl(tools, 'sideSoundControl', 'Sound Settings', openPanel,
      'M4 9h4l5-4v14l-5-4H4z M16 9a4 4 0 0 1 0 6 M19 6a8 8 0 0 1 0 12');

    const view = document.getElementById('viewBody');
    function addShortcutSetup() {
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
