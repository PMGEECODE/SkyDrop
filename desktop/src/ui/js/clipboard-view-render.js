/**
 * clipboard-view-render.js
 * DOM-building helpers for the Clipboard view.
 * All user text is set via textContent – never innerHTML – to prevent XSS.
 */

const MAX_TEXT_BYTES = 65536;

/**
 * Returns a human-readable relative time string using Intl.RelativeTimeFormat.
 * @param {string|Date} dateInput
 * @returns {string}
 */
function formatRelativeTime(dateInput) {
  try {
    const date = dateInput instanceof Date ? dateInput : new Date(dateInput);
    if (isNaN(date.getTime())) return '';
    const diffMs = date.getTime() - Date.now();
    const diffSecs = Math.round(diffMs / 1000);
    const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });

    const abs = Math.abs(diffSecs);
    if (abs < 60) return rtf.format(diffSecs, 'second');
    const diffMins = Math.round(diffSecs / 60);
    if (Math.abs(diffMins) < 60) return rtf.format(diffMins, 'minute');
    const diffHours = Math.round(diffMins / 60);
    if (Math.abs(diffHours) < 24) return rtf.format(diffHours, 'hour');
    const diffDays = Math.round(diffHours / 24);
    return rtf.format(diffDays, 'day');
  } catch (_) {
    return '';
  }
}

/**
 * Shows a brief tooltip label on a button, then restores the original text.
 * @param {HTMLButtonElement} btn
 * @param {string} label
 * @param {number} [durationMs=1500]
 */
function flashButtonLabel(btn, label, durationMs = 1500) {
  const original = btn.textContent;
  btn.textContent = label;
  btn.disabled = true;
  setTimeout(() => {
    btn.textContent = original;
    btn.disabled = false;
  }, durationMs);
}

/**
 * Builds and returns a clipboard item card element.
 * Callers must supply callbacks; this module does no IPC.
 *
 * @param {object} item  ClipboardItem from daemon
 * @param {object} cbs
 * @param {function} cbs.onCopy    (item) => void
 * @param {function} cbs.onPin     (item) => void
 * @param {function} cbs.onDelete  (item) => void
 * @returns {HTMLElement}
 */
function buildItemCard(item, cbs) {
  const card = document.createElement('div');
  card.className = 'clipboard-item-card';
  card.dataset.id = item.id;

  // ── Header ────────────────────────────────────────────────────────────────
  const header = document.createElement('div');
  header.className = 'clipboard-item-header';

  const source = document.createElement('span');
  source.className = 'clipboard-item-source';
  source.textContent = item.source_device || 'This device';

  const time = document.createElement('span');
  time.className = 'clipboard-item-time';
  time.textContent = formatRelativeTime(item.created_at);
  time.title = item.created_at || '';

  const actions = document.createElement('div');
  actions.className = 'clipboard-item-actions';

  const btnCopy = document.createElement('button');
  btnCopy.className = 'btn-icon-sm btn-copy';
  btnCopy.textContent = '📋 Copy';
  btnCopy.setAttribute('aria-label', 'Copy to clipboard');
  btnCopy.addEventListener('click', (e) => {
    e.stopPropagation();
    cbs.onCopy(item, btnCopy);
  });

  const btnPin = document.createElement('button');
  btnPin.className = 'btn-icon-sm btn-pin';
  btnPin.textContent = item.pinned ? '📍' : '📌';
  btnPin.setAttribute('aria-label', item.pinned ? 'Unpin item' : 'Pin item');
  btnPin.addEventListener('click', (e) => {
    e.stopPropagation();
    cbs.onPin(item);
  });

  const btnDelete = document.createElement('button');
  btnDelete.className = 'btn-icon-sm btn-delete';
  btnDelete.textContent = '🗑';
  btnDelete.setAttribute('aria-label', 'Delete item');
  btnDelete.addEventListener('click', (e) => {
    e.stopPropagation();
    cbs.onDelete(item, card);
  });

  actions.appendChild(btnCopy);
  actions.appendChild(btnPin);
  actions.appendChild(btnDelete);

  header.appendChild(source);
  header.appendChild(time);
  header.appendChild(actions);

  // ── Preview ───────────────────────────────────────────────────────────────
  const preview = document.createElement('div');
  preview.className = 'clipboard-item-preview';
  preview.textContent = item.text || '';

  card.appendChild(header);
  card.appendChild(preview);

  return card;
}

/**
 * Renders items into the list container, clearing previous content first.
 * Returns the rendered count.
 *
 * @param {HTMLElement} listEl
 * @param {HTMLElement} emptyEl
 * @param {object[]} items
 * @param {object} cbs  – same callbacks as buildItemCard
 * @returns {number}
 */
function renderItemList(listEl, emptyEl, items, cbs) {
  while (listEl.firstChild) listEl.removeChild(listEl.firstChild);

  if (!Array.isArray(items) || items.length === 0) {
    listEl.style.display = 'none';
    if (emptyEl) emptyEl.style.display = 'flex';
    return 0;
  }

  listEl.style.display = 'flex';
  if (emptyEl) emptyEl.style.display = 'none';

  for (const item of items) {
    listEl.appendChild(buildItemCard(item, cbs));
  }

  return items.length;
}

/**
 * Removes a card from the DOM with a fade-out animation.
 * @param {HTMLElement} card
 * @param {function} [onDone]
 */
function removeCardAnimated(card, onDone) {
  card.style.transition = 'opacity 200ms ease, max-height 220ms ease, margin 220ms ease';
  card.style.overflow = 'hidden';
  card.style.maxHeight = card.offsetHeight + 'px';

  requestAnimationFrame(() => {
    card.style.opacity = '0';
    card.style.maxHeight = '0';
    card.style.marginBottom = '0';
    card.style.paddingTop = '0';
    card.style.paddingBottom = '0';
  });

  setTimeout(() => {
    if (card.parentNode) card.parentNode.removeChild(card);
    if (onDone) onDone();
  }, 240);
}

/**
 * Updates the sync status badge element.
 * @param {HTMLElement} badgeEl
 * @param {'synced'|'offline'|'error'} status
 */
function updateSyncBadge(badgeEl, status) {
  if (!badgeEl) return;
  badgeEl.className = 'sync-badge ' + status;
  badgeEl.textContent =
    status === 'synced' ? 'Synced' :
    status === 'offline' ? 'Offline' : 'Error';
}

/**
 * Validates text before adding; returns an error string or null if valid.
 * @param {string} text
 * @returns {string|null}
 */
function validateAddText(text) {
  if (typeof text !== 'string' || text.trim().length === 0) {
    return 'Text cannot be empty.';
  }
  if (new TextEncoder().encode(text).length > MAX_TEXT_BYTES) {
    return 'Text exceeds the 64 KB limit.';
  }
  return null;
}

window.ClipboardViewRender = {
  buildItemCard,
  renderItemList,
  removeCardAnimated,
  updateSyncBadge,
  flashButtonLabel,
  validateAddText,
  formatRelativeTime
};
