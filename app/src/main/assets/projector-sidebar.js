(() => {
  const sidebar = document.getElementById('sidebar');
  if (!sidebar || sidebar.dataset.projectorControls) return;
  const remote = sidebar.querySelector('#sideRemote');
  const tutorials = sidebar.querySelector('#sideTutorials');
  if (!remote || !tutorials) return;

  function replaceLink(link, id, label, available, open, iconPath) {
    link.hidden = true;
    link.style.display = 'none';
    if (!available) return;

    const button = document.createElement('button');
    button.id = id;
    button.type = 'button';
    button.className = link.className;
    const icon = link.querySelector('svg').cloneNode(false);
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
    link.after(button);
  }

  const bridge = window.AndroidBridge;
  replaceLink(remote, 'sidePictureSettings', 'Picture Settings',
    bridge.hasPictureSettings(), () => bridge.openPictureSettings(),
    'M3 5h18v14H3z M5 16l4-4 3 3 3-4 4 5');
  replaceLink(tutorials, 'sideSoundControl', 'Sound Control',
    bridge.hasSoundControl(), () => bridge.openSoundControl(),
    'M4 9h4l5-4v14l-5-4H4z M16 9a4 4 0 0 1 0 6 M19 6a8 8 0 0 1 0 12');
  sidebar.dataset.projectorControls = 'true';
})();
