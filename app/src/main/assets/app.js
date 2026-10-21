(() => {
  'use strict';

  const el = id => document.getElementById(id);
  const native = !!(window.Android && typeof window.Android.getLibrary === 'function');
  const has = name => native && typeof window.Android[name] === 'function';
  const browserAudio = el('browserAudio');
  const browserVideo = el('browserVideo');
  const media = [];
  const speedSteps = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
  const collections = {lists: [], lyrics: {}, selectedId: '', queueId: ''};
  const TYPE_LABEL = {audio: 'AUDIO', video: 'VIDEO', youtube: 'YOUTUBE'};
  const PAGE = 150;
  let view = 'library';
  let currentId = '';
  let activeFilter = 'all';
  let search = '';
  let shuffle = false;
  let repeat = 0;
  let speed = 1;
  let playing = false;
  let preparing = false;
  let position = 0;
  let duration = 0;
  let dragging = false;
  let lyricsOpen = false;
  let lyricsRenderedKey = null;
  let lyricTimeline = [];
  let lyricActive = -2;
  let browserElement = null;
  let toastTimer;
  let lastError = '';
  let modalReturnFocus = null;
  let renderLimit = PAGE;
  let reorderMode = false;
  let scanState = 'idle';
  let permissionGranted = false;
  let lastScanAt = 0;
  let hiddenCount = 0;
  let appVersion = '1.3.8';

  const storage = {
    get(key) { try { return localStorage.getItem(key); } catch (_) { return null; } },
    set(key, val) { try { localStorage.setItem(key, val); } catch (_) { /* optional */ } }
  };
  let sortMode = ['recent', 'old', 'ko', 'en'].includes(storage.get('pulse-sort')) ? storage.get('pulse-sort') : 'recent';
  let listSortMode = ['manual', 'recent', 'old', 'ko', 'en'].includes(storage.get('pulse-sort-list')) ? storage.get('pulse-sort-list') : 'manual';
  const parseJson = (raw, fallback) => { try { return JSON.parse(raw); } catch (_) { return fallback; } };
  const track = id => media.find(item => item.id === id);
  const current = () => track(currentId);
  const isYT = item => !!item && item.type === 'youtube';
  const selectedList = () => collections.lists.find(item => item.id === collections.selectedId);
  const queueList = () => collections.lists.find(item => item.id === collections.queueId);
  const makeId = () => `list-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 9)}`;
  const time = value => {
    const sec = Math.max(0, Math.floor((Number(value) || 0) / 1000));
    const hour = Math.floor(sec / 3600);
    const minute = Math.floor(sec % 3600 / 60);
    const second = String(sec % 60).padStart(2, '0');
    return hour ? `${hour}:${String(minute).padStart(2, '0')}:${second}` : `${minute}:${second}`;
  };
  const icon = (name, cls = 'ico') => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', cls);
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `#i-${name}`);
    svg.appendChild(use);
    return svg;
  };
  const button = (text, className, handler, iconName) => {
    const node = document.createElement('button');
    node.type = 'button';
    node.className = className || '';
    if (iconName) node.appendChild(icon(iconName));
    if (text) node.appendChild(document.createTextNode(text));
    node.addEventListener('click', handler);
    return node;
  };
  const uniqueStrings = (values, limit = 20000) => {
    if (!Array.isArray(values)) return [];
    return [...new Set(values.filter(value => typeof value === 'string' && value.length <= 2048))].slice(0, limit);
  };
  const ytIdFrom = text => {
    const raw = String(text || '').trim();
    if (/^[A-Za-z0-9_-]{11}$/.test(raw)) return raw;
    const m = raw.match(/(?:youtube(?:-nocookie)?\.com\/(?:watch\?(?:.*&)?v=|shorts\/|embed\/|live\/|v\/)|youtu\.be\/)([A-Za-z0-9_-]{11})/i);
    return m ? m[1] : '';
  };
  const decodeEntities = s => { const t = document.createElement('textarea'); t.innerHTML = s; return t.value; };

  // ---------------------------------------------------------------- sorting
  const collKo = new Intl.Collator('ko', {numeric: true, sensitivity: 'base'});
  const collEn = new Intl.Collator('en', {numeric: true, sensitivity: 'base'});
  const CHO = 'ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ';
  const CHO_BASE = {'ㄲ': 'ㄱ', 'ㄸ': 'ㄷ', 'ㅃ': 'ㅂ', 'ㅆ': 'ㅅ', 'ㅉ': 'ㅈ'};
  const sortName = item => String(item.name || '').replace(/^[\s\[\](){}【】「」『』<>〈〉"'“”‘’.,\-_#~!*+=♪♬@&]+/, '');
  function scriptGroup(name) {
    const c = name.charAt(0);
    if (/[가-힣ㄱ-ㆎ]/.test(c)) return 'ko';
    if (/[A-Za-zÀ-ɏ]/.test(c)) return 'en';
    if (/[0-9]/.test(c)) return 'num';
    return 'etc';
  }
  function initialOf(name) {
    const c = name.charAt(0);
    const code = c.charCodeAt(0);
    if (code >= 0xAC00 && code <= 0xD7A3) {
      const cho = CHO[Math.floor((code - 0xAC00) / 588)];
      return CHO_BASE[cho] || cho;
    }
    if (/[ㄱ-ㅎ]/.test(c)) return CHO_BASE[c] || c;
    if (/[A-Za-z]/.test(c)) return c.toUpperCase();
    if (/[0-9]/.test(c)) return '0-9';
    return '#';
  }
  function sortItems(items, mode) {
    const arr = items.slice();
    if (mode === 'manual') return arr;
    if (mode === 'recent') return arr.sort((a, b) => (b.addedAt || 0) - (a.addedAt || 0));
    if (mode === 'old') return arr.sort((a, b) => (a.addedAt || 0) - (b.addedAt || 0));
    const order = mode === 'ko' ? {ko: 0, en: 1, num: 2, etc: 3} : {en: 0, ko: 1, num: 2, etc: 3};
    const coll = mode === 'ko' ? collKo : collEn;
    const keyed = arr.map(item => ({item, name: sortName(item)}));
    keyed.sort((a, b) => (order[scriptGroup(a.name)] - order[scriptGroup(b.name)]) || coll.compare(a.name, b.name));
    return keyed.map(row => row.item);
  }
  function groupLabel(item, mode) {
    if (mode === 'ko' || mode === 'en') return initialOf(sortName(item));
    if (mode === 'recent' || mode === 'old') {
      const at = Number(item.addedAt) || 0;
      if (!at) return '날짜 정보 없음';
      const d = new Date(at);
      const now = new Date();
      const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
      if (at >= startToday) return '오늘';
      if (at >= startToday - 86400000) return '어제';
      if (at >= startToday - 6 * 86400000) return '이번 주';
      return `${d.getFullYear()}년 ${d.getMonth() + 1}월`;
    }
    return '';
  }
  const SORT_TEXT = {manual: '목록 순서', recent: '최신순', old: '오래된순', ko: '가나다순', en: 'ABC순'};

  function toast(message) {
    const node = el('toast');
    node.textContent = message;
    node.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => node.classList.remove('show'), 2900);
  }

  // ---------------------------------------------------------------- collections
  function loadCollections() {
    const raw = has('getCollections') ? window.Android.getCollections() : storage.get('pulse-collections-v2');
    const saved = parseJson(raw || '{}', {});
    if (!saved || typeof saved !== 'object') return;
    collections.lists.length = 0;
    collections.lyrics = {};
    const found = new Set();
    for (const item of (Array.isArray(saved.lists) ? saved.lists : []).slice(0, 300)) {
      if (!item || typeof item.id !== 'string' || !item.id.startsWith('list-') || found.has(item.id)) continue;
      found.add(item.id);
      collections.lists.push({
        id: item.id,
        name: String(item.name || '이름 없는 목록').trim().slice(0, 64),
        tracks: uniqueStrings(item.tracks),
        createdAt: Number(item.createdAt) || 0,
        ...(typeof item.ytPlaylistId === 'string' && item.ytPlaylistId.length < 200 ? {ytPlaylistId: item.ytPlaylistId} : {})
      });
    }
    if (saved.lyrics && typeof saved.lyrics === 'object' && !Array.isArray(saved.lyrics)) {
      for (const [id, words] of Object.entries(saved.lyrics).slice(0, 5000)) {
        if (id.length <= 2048 && typeof words === 'string' && words.length <= 30000) collections.lyrics[id] = words;
      }
    }
    collections.selectedId = collections.lists.some(list => list.id === saved.selectedId) ? saved.selectedId : '';
    collections.queueId = collections.lists.some(list => list.id === saved.queueId) ? saved.queueId : '';
  }

  function saveCollections() {
    const json = JSON.stringify(collections);
    if (has('saveCollections')) window.Android.saveCollections(json);
    else storage.set('pulse-collections-v2', json);
  }

  function refreshCollectionReferences(removedId) {
    let changed = false;
    for (const list of collections.lists) {
      const before = list.tracks.length;
      list.tracks = list.tracks.filter(id => id !== removedId);
      changed = changed || list.tracks.length !== before;
    }
    if (Object.prototype.hasOwnProperty.call(collections.lyrics, removedId)) {
      delete collections.lyrics[removedId];
      changed = true;
    }
    if (changed) saveCollections();
  }

  function importFiles() {
    if (native) window.Android.importFiles();
    else el('fileInput').click();
  }

  // ---------------------------------------------------------------- navigation
  function navigate(to, scrollTop = true) {
    view = ['library', 'playlists', 'player'].includes(to) ? to : 'library';
    if (view !== 'playlists') reorderMode = false;
    document.body.dataset.view = view;
    renderNav();
    renderLibrary();
    renderPlaylists();
    renderNow();
    if (scrollTop) window.scrollTo({top: 0, behavior: 'instant'});
  }

  function renderNav() {
    const key = view === 'library' && activeFilter === 'youtube' ? 'youtube' : view;
    document.querySelectorAll('.nav-tab').forEach(node => {
      const selected = node.dataset.nav === key;
      node.classList.toggle('active', selected);
      if (selected) node.setAttribute('aria-current', 'page');
      else node.removeAttribute('aria-current');
    });
  }

  function setFilter(filter) {
    activeFilter = ['all', 'audio', 'video', 'youtube', 'favorite'].includes(filter) ? filter : 'all';
    renderLimit = PAGE;
    document.querySelectorAll('.filter').forEach(tab => {
      const active = tab.dataset.filter === activeFilter;
      tab.classList.toggle('active', active);
      tab.setAttribute('aria-selected', String(active));
      if (active) tab.scrollIntoView({block: 'nearest', inline: 'nearest'});
    });
    renderNav();
    renderLibrary();
    renderNow();
  }

  function renderCounts() {
    let audio = 0, video = 0, yt = 0, fav = 0;
    for (const item of media) {
      if (item.type === 'audio') audio++;
      else if (item.type === 'video') video++;
      else if (item.type === 'youtube') yt++;
      if (item.favorite) fav++;
    }
    el('musicCount').textContent = audio;
    el('videoCount').textContent = video;
    el('youtubeCount').textContent = yt;
    el('favoriteCount').textContent = fav;
    return {audio, video, yt, fav};
  }

  function listTracks(list) {
    return list ? list.tracks.map(id => track(id)).filter(Boolean) : [];
  }

  function listComposition(list) {
    const items = listTracks(list);
    const parts = [];
    const a = items.filter(i => i.type === 'audio').length;
    const v = items.filter(i => i.type === 'video').length;
    const y = items.filter(i => i.type === 'youtube').length;
    if (a) parts.push(`음악 ${a}`);
    if (v) parts.push(`영상 ${v}`);
    if (y) parts.push(`YouTube ${y}`);
    return {count: items.length, text: parts.join(' · ')};
  }

  // ---------------------------------------------------------------- playlists
  function renderPlaylists() {
    const panel = el('playlistCards');
    const selected = selectedList();
    panel.replaceChildren();
    if (!collections.lists.length) {
      const hint = document.createElement('p');
      hint.className = 'playlist-empty';
      hint.textContent = '아직 만든 플레이리스트가 없어요. 새 목록을 만들고 음악·영상·YouTube를 자유롭게 담아보세요.';
      panel.appendChild(hint);
    } else {
      for (const list of collections.lists) {
        const active = list.id === collections.selectedId;
        const card = button('', 'playlist-card' + (active ? ' active' : ''), () => {
          collections.selectedId = list.id;
          reorderMode = false;
          renderLimit = PAGE;
          saveCollections();
          renderPlaylists();
          renderLibrary();
          if (window.matchMedia('(max-width:760px)').matches) el('libraryTitle').scrollIntoView({behavior: 'smooth', block: 'start'});
        });
        const symbol = document.createElement('span');
        symbol.className = 'playlist-icon';
        const cover = listTracks(list).find(isYT);
        if (cover && cover.thumb) {
          const img = document.createElement('img');
          img.src = cover.thumb; img.alt = ''; img.loading = 'lazy';
          symbol.appendChild(img);
        } else symbol.appendChild(icon('list'));
        const description = document.createElement('span');
        description.className = 'playlist-card-text';
        const name = document.createElement('strong');
        name.textContent = list.name;
        const count = document.createElement('small');
        const comp = listComposition(list);
        count.textContent = `${comp.count}개${comp.text ? ' · ' + comp.text : ''}${list.id === collections.queueId ? ' · 재생 중인 목록' : ''}`;
        description.append(name, count);
        card.append(symbol, description);
        if (active) card.appendChild(icon('chevron'));
        panel.appendChild(card);
      }
    }
    el('playlistTools').hidden = !selected;
    if (selected) {
      const n = listTracks(selected).length;
      el('selectedPlaylistName').textContent = selected.name;
      el('selectedPlaylistCount').textContent = `${n}개 항목`;
      el('playlistPlay').disabled = n === 0;
      el('playlistShuffle').disabled = n < 2;
      el('reorderPlaylist').disabled = n < 2;
      el('reorderPlaylist').textContent = reorderMode ? '✓ 순서 편집 완료' : '↕ 순서 편집';
      el('reorderPlaylist').classList.toggle('on', reorderMode);
      el('addPlaylistTracks').disabled = media.length === 0;
    }
  }

  function modal(title, hint, confirmText, onConfirm) {
    if (el('dialogBackdrop').hidden) modalReturnFocus = document.activeElement;
    el('dialogBackdrop').hidden = false;
    document.body.classList.add('modal-open');
    el('dialogTitle').textContent = title;
    el('dialogHint').textContent = hint || '';
    for (const id of ['dialogName', 'dialogLyrics', 'dialogSearch', 'dialogChoices', 'dialogTools', 'dialogFilters']) el(id).hidden = true;
    el('dialogHint').classList.remove('pre');
    el('dialogSearch').after(el('dialogFilters'));
    el('dialogFilters').classList.remove('type-pick');
    el('dialogChoices').replaceChildren();
    el('dialogTools').replaceChildren();
    el('dialogFilters').replaceChildren();
    el('dialogName').type = 'text';
    el('dialogName').type = 'text';
    el('dialogName').placeholder = '플레이리스트 이름';
    el('dialogName').maxLength = 64;
    el('dialogLyrics').placeholder = '가사를 직접 입력하거나 붙여넣으세요.\n[00:12.50] 시간 표시가 있는 LRC도 지원합니다.';
    el('dialogConfirm').hidden = false;
    el('dialogCancel').hidden = false;
    el('dialogConfirm').textContent = confirmText || '저장';
    el('dialogConfirm').onclick = onConfirm;
    el('dialogBackdrop').scrollTop = 0;
    el('dialogBackdrop').dataset.kind = '';
  }

  function closeModal() {
    el('dialogBackdrop').hidden = true;
    document.body.classList.remove('modal-open');
    el('dialogConfirm').onclick = null;
    el('dialogSearch').oninput = null;
    el('dialogBackdrop').dataset.kind = '';
    if (modalReturnFocus?.isConnected) modalReturnFocus.focus();
    modalReturnFocus = null;
  }

  function nameDialog(id = '', addTrackId = '') {
    const existing = collections.lists.find(item => item.id === id);
    modal(existing ? '플레이리스트 이름 변경' : '새 플레이리스트',
      existing ? '목록 안의 항목과 순서는 그대로 유지됩니다.' : '어떤 음악을 모을지 이름을 정해보세요.',
      existing ? '변경' : '만들기', () => {
        const name = el('dialogName').value.trim();
        if (!name) { toast('플레이리스트 이름을 입력하세요.'); el('dialogName').focus(); return; }
        if (existing) existing.name = name;
        else {
          const list = {id: makeId(), name, tracks: addTrackId ? [addTrackId] : [], createdAt: Date.now()};
          collections.lists.push(list);
          collections.selectedId = list.id;
        }
        saveCollections();
        closeModal();
        renderPlaylists();
        renderLibrary();
        toast(existing ? '이름을 변경했습니다.' : (addTrackId ? '플레이리스트를 만들고 담았습니다.' : '플레이리스트를 만들었습니다.'));
        if (!existing && !addTrackId) { navigate('playlists'); addTracksToSelected(); }
      });
    el('dialogName').hidden = false;
    el('dialogName').value = existing ? existing.name : '';
    setTimeout(() => el('dialogName').focus(), 50);
  }

  function deletePlaylist() {
    const list = selectedList();
    if (!list) return;
    modal('플레이리스트 삭제', `'${list.name}' 목록을 삭제할까요? 음악 원본과 입력한 가사는 지워지지 않습니다.`,
      '삭제', () => {
        collections.lists = collections.lists.filter(item => item.id !== list.id);
        if (collections.queueId === list.id) collections.queueId = '';
        collections.selectedId = collections.lists[0]?.id || '';
        saveCollections();
        closeModal(); renderPlaylists(); renderLibrary(); renderNow();
        toast('플레이리스트를 삭제했습니다.');
      });
  }

  function addTracksToSelected() {
    const list = selectedList();
    if (!list) { toast('플레이리스트를 선택해 주세요.'); return; }
    if (!media.length) { importFiles(); return; }
    modal(`'${list.name}'에 담기`, '추가하거나 뺄 항목을 체크하고 저장하세요. 음악·영상·YouTube를 섞어 담을 수 있어요.', '선택 저장', () => {
      const checked = new Set(Array.from(el('dialogChoices').querySelectorAll('input[type="checkbox"]:checked')).map(x => x.value));
      const before = list.tracks.length;
      list.tracks = [...list.tracks.filter(id => checked.has(id)), ...orderAll.map(row => row.id).filter(id => checked.has(id) && !list.tracks.includes(id))];
      saveCollections(); closeModal(); renderPlaylists(); renderLibrary();
      const diff = list.tracks.length - before;
      toast(`'${list.name}' · ${list.tracks.length}개 항목${diff > 0 ? ` (+${diff})` : ''}`);
    });
    el('dialogSearch').hidden = false;
    el('dialogSearch').value = '';
    el('dialogChoices').hidden = false;
    el('dialogFilters').hidden = false;
    el('dialogBackdrop').dataset.kind = 'picker';
    const controls = el('dialogChoices');
    const selected = new Set(list.tracks);
    const orderAll = sortItems(media, sortMode);
    const nodes = [];
    let pickFilter = 'all';
    for (const item of orderAll) {
      const label = document.createElement('label');
      label.className = 'choice-track';
      const check = document.createElement('input');
      check.type = 'checkbox';
      check.value = item.id;
      check.checked = selected.has(item.id);
      const name = document.createElement('span');
      name.textContent = item.name;
      const kind = document.createElement('small');
      kind.textContent = (item.favorite ? '♥ ' : '') + (TYPE_LABEL[item.type] || 'AUDIO');
      label.append(check, name, kind);
      controls.append(label);
      nodes.push({label, item, check});
    }
    const apply = () => {
      const q = el('dialogSearch').value.trim().toLocaleLowerCase('ko');
      for (const {label, item} of nodes) {
        const typeOk = pickFilter === 'all' || (pickFilter === 'favorite' ? item.favorite : item.type === pickFilter);
        label.hidden = !typeOk || (!!q && !`${item.name} ${item.artist || ''} ${item.folder || ''}`.toLocaleLowerCase('ko').includes(q));
      }
    };
    const chips = [['all', '전체'], ['audio', '음악'], ['video', '영상'], ['youtube', 'YouTube'], ['favorite', '♥']];
    for (const [key, text] of chips) {
      const chip = button(text, 'chip' + (key === 'all' ? ' active' : ''), () => {
        pickFilter = key;
        el('dialogFilters').querySelectorAll('.chip').forEach(n => n.classList.toggle('active', n === chip));
        apply();
      });
      el('dialogFilters').appendChild(chip);
    }
    el('dialogFilters').appendChild(button('보이는 항목 모두 선택', 'chip ghost', () => {
      const visible = nodes.filter(n => !n.label.hidden);
      const allOn = visible.every(n => n.check.checked);
      for (const n of visible) n.check.checked = !allOn;
    }));
    el('dialogSearch').oninput = apply;
  }

  function addToPlaylist(id) {
    const item = track(id);
    if (!item) return;
    if (!collections.lists.length) { nameDialog('', id); return; }
    modal('플레이리스트에 담기', `'${item.name}'을(를) 담을 목록을 선택하세요.`, '닫기', closeModal);
    el('dialogCancel').hidden = true;
    el('dialogChoices').hidden = false;
    for (const list of collections.lists) {
      const included = list.tracks.includes(id);
      el('dialogChoices').appendChild(button(
        `${included ? '✓ ' : '+ '}${list.name}  ·  ${list.tracks.length}개`, 'dialog-option' + (included ? ' included' : ''), () => {
          if (included) list.tracks = list.tracks.filter(trackId => trackId !== id);
          else list.tracks.push(id);
          saveCollections(); closeModal(); renderPlaylists(); renderLibrary();
          toast(included ? '해당 목록에서 뺐습니다.' : `'${list.name}'에 담았습니다.`);
        }, 'list'
      ));
    }
    el('dialogChoices').appendChild(button('새 목록 만들고 담기', 'dialog-option new', () => {
      closeModal(); nameDialog('', id);
    }, 'plus'));
  }

  function moveTrack(id, offset) {
    const list = selectedList();
    if (!list) return;
    const idx = list.tracks.indexOf(id);
    let dest = idx + offset;
    if (offset === -Infinity) dest = 0;
    if (offset === Infinity) dest = list.tracks.length - 1;
    if (idx < 0 || dest < 0 || dest >= list.tracks.length || dest === idx) return;
    const [moved] = list.tracks.splice(idx, 1);
    list.tracks.splice(dest, 0, moved);
    saveCollections(); renderLibrary();
  }

  function removeFromSelected(id) {
    const list = selectedList();
    if (!list) return;
    list.tracks = list.tracks.filter(itemId => itemId !== id);
    saveCollections(); renderPlaylists(); renderLibrary();
    toast('플레이리스트에서 뺐습니다. 원본은 그대로입니다.');
  }

  function playList(list, shuffled) {
    const pool = listTracks(list);
    if (!pool.length) return;
    collections.selectedId = list.id;
    if (shuffled !== undefined && shuffled !== shuffle) { shuffle = shuffled; setOptions(); }
    const first = shuffled ? pool[Math.floor(Math.random() * pool.length)] : pool[0];
    collections.queueId = list.id;
    saveCollections();
    renderPlaylists();
    playItem(first.id, true);
    toast(`'${list.name}' ${shuffled ? '셔플 ' : ''}재생`);
  }

  // ---------------------------------------------------------------- track actions
  function trackActions(id) {
    const item = track(id);
    if (!item) return;
    const selected = view === 'playlists' ? selectedList() : null;
    const sub = [TYPE_LABEL[item.type], item.artist, item.folder && item.folder !== item.artist ? `📁 ${item.folder}` : '', item.duration ? time(item.duration) : '']
      .filter(Boolean).join(' · ');
    modal(item.name, sub, '닫기', closeModal);
    el('dialogCancel').hidden = true;
    const panel = el('dialogChoices');
    panel.hidden = false;
    panel.appendChild(button(item.favorite ? '즐겨찾기 해제' : '즐겨찾기에 추가', 'dialog-option', () => {
      closeModal(); toggleFavorite(id);
    }, 'heart'));
    panel.appendChild(button('목록에서 보이는 이름 변경', 'dialog-option', () => {
      closeModal(); renameTitleDialog(id);
    }, 'edit'));
    if (native && has('renameOriginalFile') && (item.source === 'device' || item.source === 'import')) {
      panel.appendChild(button('휴대폰 원본 파일명 변경', 'dialog-option', () => {
        closeModal(); window.Android.renameOriginalFile(id);
      }, 'edit'));
    }
    panel.appendChild(button('플레이리스트에 담기 / 빼기', 'dialog-option', () => {
      closeModal(); addToPlaylist(id);
    }, 'list-add'));
    panel.appendChild(button(collections.lyrics[id] ? '가사 보기 / 편집' : '가사 입력 · 인터넷 검색', 'dialog-option', () => {
      closeModal(); lyricsDialog(id);
    }, 'lyrics'));
    if (isYT(item)) {
      panel.appendChild(button('YouTube 화면에서 보기', 'dialog-option', () => {
        closeModal(); openYouTubePage(item.videoId || item.id.slice(3));
      }, 'globe'));
      panel.appendChild(button('YouTube 앱에서 보기·오프라인 저장 (Premium)', 'dialog-option', () => {
        closeModal(); openYouTubeOffline(item.videoId || item.id.slice(3));
      }, 'download'));
    }
    if (selected && selected.tracks.includes(id)) {
      const idx = selected.tracks.indexOf(id);
      if (idx > 0) panel.appendChild(button('맨 위로 이동', 'dialog-option', () => { closeModal(); moveTrack(id, -Infinity); }, 'up'));
      if (idx < selected.tracks.length - 1) panel.appendChild(button('맨 아래로 이동', 'dialog-option', () => { closeModal(); moveTrack(id, Infinity); }, 'down'));
      panel.appendChild(button('현재 플레이리스트에서 빼기', 'dialog-option', () => {
        closeModal(); removeFromSelected(id);
      }, 'trash'));
    }
    const removeText = item.source === 'device' ? '라이브러리에서 숨기기 (파일은 그대로)' : isYT(item) ? 'YouTube 목록에서 삭제' : '라이브러리에서 제거';
    panel.appendChild(button(removeText, 'dialog-option danger', () => {
      closeModal(); removeFile(id);
    }, 'trash'));
    if (native && has('deleteOriginal') && (item.source === 'device' || item.source === 'import')) {
      panel.appendChild(button('휴대폰 원본 파일 삭제', 'dialog-option danger', () => {
        closeModal(); window.Android.deleteOriginal(id);
      }, 'trash'));
    }
  }

  function renameTitleDialog(id) {
    const item = track(id);
    if (!item) return;
    modal('목록 표시 이름 변경', '원본 파일명은 그대로 두고 플레이리스트와 재생 화면에 표시되는 이름만 수정합니다.', '이름 저장', () => {
      const name = el('dialogName').value.trim();
      if (!name || name.length > 120) { toast('1~120자 이내로 입력해 주세요.'); return; }
      if (native && has('renameDisplayName')) {
        if (!window.Android.renameDisplayName(id, name)) { toast('이름을 저장하지 못했습니다.'); return; }
      } else {
        item.name = name;
        if (isYT(item)) persistBrowserYouTube();
      }
      item.name = name;
      closeModal(); renderAll(); toast('표시 이름을 바꿨습니다.');
    });
    el('dialogName').hidden = false;
    el('dialogName').value = item.name || '';
    el('dialogName').maxLength = 120;
  }

  window.onNativeMediaRenamed = (oldId, newId) => {
    if (oldId !== newId) {
      for (const list of collections.lists) list.tracks = list.tracks.map(id => id === oldId ? newId : id);
      if (Object.prototype.hasOwnProperty.call(collections.lyrics, oldId)) {
        collections.lyrics[newId] = collections.lyrics[oldId];
        delete collections.lyrics[oldId];
      }
      if (currentId === oldId) currentId = newId;
      saveCollections();
    }
    renderAll(); toast('원본 파일명을 변경했습니다.');
  };
  window.onNativeRenameFailed = message => toast(message || '원본 파일명 변경에 실패했습니다.');

  function toggleFavorite(id) {
    const item = track(id);
    if (!item) return;
    const willFavorite = !item.favorite;
    item.favorite = willFavorite;
    if (native) window.Android.toggleFavorite(id);
    else persistBrowserYouTube();
    renderLibrary(); renderNow();
    if (inlineYouTubeId) updateInlineFavorite();
    if (ytView && activeFilter === 'youtube') renderYtResults();
    toast(willFavorite ? '즐겨찾기에 추가했어요. 앱을 다시 열어도 유지돼요.' : '즐겨찾기에서 뺐어요.');
  }

  function removeFile(id) {
    const item = track(id);
    if (!item) return;
    const device = item.source === 'device';
    modal(device ? '라이브러리에서 숨기기' : '라이브러리에서 제거',
      device ? `'${item.name}'을(를) 목록에서 숨길까요? 휴대폰의 원본 파일은 삭제되지 않으며, 설정에서 다시 보이게 할 수 있어요.`
        : `'${item.name}'을(를) 앱 목록에서 제거할까요? 원본 파일은 삭제하지 않습니다.`,
      device ? '숨기기' : '목록에서 제거', () => {
        refreshCollectionReferences(id);
        if (id === currentId && isYT(item)) ytStop(true);
        if (native) window.Android.remove(id);
        else {
          if (id === currentId) { stopBrowser(); currentId = ''; position = duration = 0; playing = false; }
          const index = media.findIndex(row => row.id === id);
          if (index >= 0) {
            const [removed] = media.splice(index, 1);
            if (removed.url) URL.revokeObjectURL(removed.url);
          }
          persistBrowserYouTube();
          renderAll();
        }
        closeModal();
        toast(device ? '목록에서 숨겼습니다. 원본 파일은 보존됩니다.' : '목록에서 제거했습니다.');
      });
  }

  window.onNativeFileDeleted = id => {
    if (!id || typeof id !== 'string') return;
    refreshCollectionReferences(id);
    if (id === currentId) {
      stopBrowser();
      currentId = '';
      playing = preparing = false;
      position = duration = 0;
    }
    toast('휴대폰 원본 파일을 삭제했습니다.');
    if (native && has('getLibrary')) nativeLibrary(parseJson(window.Android.getLibrary(), []));
  };

  window.onNativeFileDeleteFailed = message => {
    toast(String(message || '원본 파일을 삭제하지 못했습니다.'));
  };

  // ---------------------------------------------------------------- library rendering
  function createTrackRow(item, opts = {}) {
    const row = document.createElement('article');
    row.className = 'track-row' + (currentId === item.id ? ' current' : '');
    const visual = document.createElement('div');
    visual.className = `media-tile ${item.type}`;
    if (isYT(item) && item.thumb) {
      const img = document.createElement('img');
      img.src = item.thumb; img.alt = ''; img.loading = 'lazy'; img.decoding = 'async';
      visual.appendChild(img);
      const badge = document.createElement('span');
      badge.className = 'tile-badge';
      badge.appendChild(icon('play'));
      visual.appendChild(badge);
    } else visual.appendChild(icon(item.type === 'video' ? 'video' : item.type === 'youtube' ? 'youtube' : 'music'));
    row.appendChild(visual);

    const name = button('', 'row-body', () => playItem(item.id));
    name.title = item.fileName || item.name;
    name.setAttribute('aria-label', `${item.name} 재생`);
    const title = document.createElement('span');
    title.className = 'row-title'; title.textContent = item.name || '이름 없는 파일';
    const meta = document.createElement('span');
    meta.className = 'row-meta';
    const kind = document.createElement('span');
    kind.className = 'kind-tag ' + item.type;
    kind.textContent = TYPE_LABEL[item.type] || 'AUDIO';
    const artist = document.createElement('span');
    artist.className = 'row-artist';
    artist.textContent = `${item.artist || '내 파일'} · ${item.duration ? time(item.duration) : '—:—'}`;
    meta.append(kind, artist);
    if (collections.lyrics[item.id]) {
      const lyricBadge = document.createElement('span');
      lyricBadge.className = 'lyrics-badge';
      lyricBadge.textContent = '가사';
      meta.appendChild(lyricBadge);
    }
    name.append(title, meta);
    row.appendChild(name);

    if (currentId === item.id && playing) {
      const bars = document.createElement('span');
      bars.className = 'current-bars animate';
      bars.innerHTML = '<i></i><i></i><i></i>';
      bars.setAttribute('aria-label', '재생 중');
      row.appendChild(bars);
    }
    if (opts.reorder) {
      row.classList.add('reorder');
      row.appendChild(button('', 'row-move', () => moveTrack(item.id, -1), 'up'));
      row.appendChild(button('', 'row-move', () => moveTrack(item.id, 1), 'down'));
      row.appendChild(button('', 'row-move danger', () => removeFromSelected(item.id), 'close'));
      return row;
    }
    const fav = button('', 'row-heart' + (item.favorite ? ' active' : ''), () => toggleFavorite(item.id), 'heart');
    fav.title = item.favorite ? '즐겨찾기 해제' : '즐겨찾기 추가';
    fav.setAttribute('aria-label', fav.title);
    row.appendChild(fav);
    const menu = button('', 'row-menu', () => trackActions(item.id), 'more');
    menu.title = '플레이리스트 · 가사 · 관리';
    menu.setAttribute('aria-label', `${item.name} 더보기`);
    row.appendChild(menu);
    return row;
  }

  function emptyState(why) {
    const wrapper = document.createElement('div');
    wrapper.className = 'empty-state';
    const illustration = document.createElement('div');
    illustration.className = 'empty-illustration';
    const iconName = {'missing-list': 'list', 'empty-list': 'list-add', search: 'search', youtube: 'youtube', favorite: 'heart', video: 'video'}[why] || 'folder';
    illustration.appendChild(icon(iconName));
    const heading = document.createElement('h3');
    heading.textContent = ({
      'missing-list': '플레이리스트를 만들어 보세요', 'empty-list': '목록이 아직 비어 있어요', search: '검색 결과가 없어요',
      library: '아직 음악·영상이 없어요', youtube: '저장한 YouTube 영상이 없어요', favorite: '즐겨찾기가 비어 있어요',
      video: '영상이 없어요', audio: '음악이 없어요'
    })[why];
    const p = document.createElement('p');
    p.textContent = ({
      'missing-list': '새 목록을 만든 다음 원하는 음악을 담아 보세요.', 'empty-list': '위의 “곡 담기” 버튼을 눌러 추가해 보세요.',
      search: '다른 검색어나 카테고리를 선택해 보세요.',
      library: native ? '기기 미디어를 불러오거나 파일을 직접 추가해 보세요.' : '휴대폰의 음악과 영상을 먼저 추가해 보세요.',
      youtube: '위에서 검색하거나 링크를 붙여넣어 추가해 보세요.', favorite: '♡ 버튼을 누르면 여기에 모여요. 앱을 다시 열어도 유지됩니다.',
      video: '기기에 저장된 영상이 여기에 표시돼요.', audio: '기기에 저장된 음악이 여기에 표시돼요.'
    })[why];
    wrapper.append(illustration, heading, p);
    if (why === 'library' || why === 'audio' || why === 'video') {
      if (native && !permissionGranted) wrapper.appendChild(button('기기 음악·영상 불러오기', '', () => requestDeviceScan(true)));
      wrapper.appendChild(button('+ 파일 직접 추가', 'ghost', importFiles));
    }
    if (why === 'missing-list') wrapper.appendChild(button('+ 새 플레이리스트', '', () => nameDialog()));
    if (why === 'empty-list') wrapper.appendChild(button('+ 곡 담기', '', addTracksToSelected));
    return wrapper;
  }

  function matchesSearch(item, phrase) {
    return !phrase || `${item.name} ${item.artist || ''} ${item.fileName || ''} ${item.album || ''} ${item.folder || ''}`.toLocaleLowerCase('ko').includes(phrase);
  }

  function appendRows(container, items, mode, opts = {}) {
    const fragment = document.createDocumentFragment();
    let lastGroup = null;
    const limit = opts.limit ?? items.length;
    for (let i = 0; i < Math.min(limit, items.length); i++) {
      const item = items[i];
      if (opts.groups) {
        const g = groupLabel(item, mode);
        if (g && g !== lastGroup) {
          const head = document.createElement('div');
          head.className = 'group-head';
          head.textContent = g;
          fragment.appendChild(head);
          lastGroup = g;
        }
      }
      fragment.appendChild(createTrackRow(item, opts));
    }
    container.appendChild(fragment);
    if (items.length > limit) {
      container.appendChild(button(`더 보기 · 남은 ${items.length - limit}개`, 'more-button', () => {
        renderLimit += PAGE * 2;
        renderLibrary(true);
      }));
    }
  }

  function renderHomeSections(container, phrase) {
    const sections = [
      ['audio', '음악', 'music', 'MUSIC ON THIS PHONE'],
      ['video', '영상', 'video', 'VIDEOS ON THIS PHONE'],
      ['youtube', 'YouTube', 'youtube', 'SAVED FROM YOUTUBE']
    ];
    let shown = 0;
    const favs = sortItems(media.filter(i => i.favorite), sortMode);
    if (favs.length) {
      container.appendChild(sectionHead('favorite', '즐겨찾기', 'heart', 'FAVORITES', favs.length));
      const strip = document.createElement('div');
      strip.className = 'fav-strip';
      for (const item of favs.slice(0, 12)) {
        const chip = button('', 'fav-chip' + (item.id === currentId ? ' current' : ''), () => playItem(item.id));
        const art = document.createElement('span');
        art.className = `fav-art ${item.type}`;
        if (isYT(item) && item.thumb) { const img = document.createElement('img'); img.src = item.thumb; img.alt = ''; img.loading = 'lazy'; art.appendChild(img); }
        else art.appendChild(icon(item.type === 'video' ? 'video' : 'music'));
        const label = document.createElement('span');
        label.textContent = item.name;
        chip.append(art, label);
        strip.appendChild(chip);
      }
      container.appendChild(strip);
      shown++;
    }
    for (const [type, label, iconName, eyebrow] of sections) {
      const items = sortItems(media.filter(i => i.type === type && matchesSearch(i, phrase)), sortMode);
      if (!items.length && type === 'youtube') continue;
      container.appendChild(sectionHead(type, label, iconName, eyebrow, items.length));
      if (!items.length) {
        const p = document.createElement('p');
        p.className = 'section-empty';
        p.textContent = type === 'audio' ? '기기에서 찾은 음악이 없어요.' : '기기에서 찾은 영상이 없어요.';
        container.appendChild(p);
        continue;
      }
      appendRows(container, items, sortMode, {limit: 5});
      container.lastChild?.classList?.contains('more-button') && container.lastChild.remove();
      shown++;
    }
    return shown;
  }

  function sectionHead(type, label, iconName, eyebrow, count) {
    const head = document.createElement('div');
    head.className = 'home-section-head';
    const left = document.createElement('div');
    const small = document.createElement('small');
    small.textContent = eyebrow;
    const h = document.createElement('h3');
    h.append(icon(iconName), document.createTextNode(` ${label} `));
    const c = document.createElement('span');
    c.textContent = count;
    h.appendChild(c);
    left.append(small, h);
    head.appendChild(left);
    if (count) head.appendChild(button('전체 보기', 'see-all', () => setFilter(type), 'chevron'));
    return head;
  }

  function renderLibrary(keepLimit = false) {
    if (!keepLimit) renderLimit = Math.max(renderLimit, PAGE);
    const counts = renderCounts();
    const isList = view === 'playlists';
    const selected = isList ? selectedList() : null;
    const mode = isList ? (reorderMode ? 'manual' : listSortMode) : sortMode;
    el('libraryHeading').textContent = isList ? (selected ? selected.name : '목록의 항목')
      : ({all: '내 라이브러리', audio: '음악', video: '영상', youtube: 'YouTube', favorite: '즐겨찾기'})[activeFilter];
    el('librarySectionLabel').textContent = isList ? 'PLAYLIST' : (activeFilter === 'all' ? 'MY COLLECTION' : 'LIBRARY');
    el('ytPanel').hidden = isList || activeFilter !== 'youtube';
    if (!el('ytPanel').hidden) renderYtAccount();
    renderDeviceBanner(counts);
    // sort select reflects the current context
    const sel = el('sortSelect');
    const manualOpt = sel.querySelector('option[value="manual"]');
    if (isList && !manualOpt) sel.insertBefore(new Option('목록 순서', 'manual'), sel.firstChild);
    if (!isList && manualOpt) manualOpt.remove();
    sel.value = mode;
    sel.disabled = reorderMode;

    const list = el('trackList');
    list.replaceChildren();
    const pool = isList ? listTracks(selected) : media;
    const phrase = search.trim().toLocaleLowerCase('ko');
    const result = sortItems(pool.filter(item => {
      if (!isList) {
        if (activeFilter === 'favorite' && !item.favorite) return false;
        if (['audio', 'video', 'youtube'].includes(activeFilter) && item.type !== activeFilter) return false;
      }
      return matchesSearch(item, phrase);
    }), mode);
    el('mediaCount').textContent = isList ? pool.length : (activeFilter === 'all' ? media.length : result.length);
    el('searchCount').hidden = !phrase;
    el('searchCount').textContent = `${result.length}개`;
    el('sortSummary').textContent = isList ? (reorderMode ? '▲▼로 순서를 바꾸세요' : `${SORT_TEXT[mode]} · ${result.length}개`)
      : activeFilter === 'all' && !phrase ? '음악 · 영상을 나눠서 보여줘요' : `${SORT_TEXT[mode]} · ${result.length}개`;

    if (!isList && activeFilter === 'all' && !phrase) {
      if (!media.length) list.appendChild(emptyState('library'));
      else renderHomeSections(list, '');
      return;
    }
    if (!result.length) {
      const why = isList && !selected ? 'missing-list' : isList && !pool.length ? 'empty-list'
        : phrase ? 'search' : !isList && ['youtube', 'favorite', 'audio', 'video'].includes(activeFilter) ? activeFilter : 'library';
      list.appendChild(emptyState(why));
      return;
    }
    appendRows(list, result, mode, {groups: mode !== 'manual' && !reorderMode, limit: reorderMode ? result.length : renderLimit, reorder: reorderMode});
  }

  function renderDeviceBanner(counts) {
    const banner = el('deviceBanner');
    el('deviceRescan').hidden = !native || view === 'playlists';
    if (!native || view === 'playlists' || activeFilter === 'youtube') { banner.hidden = true; return; }
    if (scanState === 'scanning') {
      banner.hidden = false;
      banner.classList.add('busy');
      el('deviceBannerTitle').textContent = '기기 미디어를 찾는 중…';
      el('deviceBannerText').textContent = '휴대폰의 음악과 영상을 확인하고 있어요.';
      el('deviceBannerButton').hidden = true;
      return;
    }
    banner.classList.remove('busy');
    el('deviceBannerButton').hidden = false;
    if (!permissionGranted) {
      banner.hidden = false;
      el('deviceBannerTitle').textContent = '기기의 음악·영상 불러오기';
      el('deviceBannerText').textContent = '휴대폰에 저장된 음악과 영상을 자동으로 찾아 음악·영상으로 나눠 보여드려요.';
      el('deviceBannerButton').textContent = '허용하고 불러오기';
      return;
    }
    banner.hidden = true;
  }

  // ---------------------------------------------------------------- device scan
  function requestDeviceScan(ask) {
    if (!has('scanDevice')) { importFiles(); return; }
    window.Android.scanDevice(!!ask);
  }

  window.onDevicePermission = (granted, canAskAgain) => {
    permissionGranted = !!granted;
    if (!granted) {
      if (!canAskAgain) {
        modal('미디어 접근 권한이 꺼져 있어요', '설정 > 권한에서 “음악 및 오디오”, “사진 및 동영상”을 허용하면 기기의 음악·영상을 자동으로 불러올 수 있어요.', '설정 열기', () => {
          closeModal(); if (has('openAppSettings')) window.Android.openAppSettings();
        });
      } else toast('권한을 허용하지 않아 기기 미디어를 불러오지 못했어요.');
    }
    renderLibrary();
  };

  window.onDeviceScan = info => {
    if (!info) return;
    if (info.state === 'start') { scanState = 'scanning'; renderLibrary(); el('deviceRescan').classList.add('spin'); return; }
    scanState = 'idle';
    permissionGranted = true;
    lastScanAt = Date.now();
    el('deviceRescan').classList.remove('spin');
    renderLibrary();
    if (info.announce) toast(`기기에서 음악 ${info.audio}곡 · 영상 ${info.video}개를 찾았어요${info.added ? ` (새 항목 ${info.added})` : ''}.`);
  };

  // ---------------------------------------------------------------- lyrics
  function setQueueForPlay(preserveQueue) {
    if (preserveQueue) return;
    const next = view === 'playlists' && selectedList() ? collections.selectedId : '';
    if (next !== collections.queueId) {
      collections.queueId = next;
      saveCollections();
      renderPlaylists();
    }
  }

  function setLyricsOpen(value) {
    lyricsOpen = !!value;
    renderNow();
  }

  function parseLyrics(raw) {
    const timed = [];
    const plain = [];
    for (const line of raw.replace(/\r\n?/g, '\n').split('\n')) {
      const hits = [...line.matchAll(/\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]/g)];
      if (!hits.length) {
        if (!/^\[(?:ar|ti|al|by|offset|length):/i.test(line.trim())) plain.push(line);
        continue;
      }
      const text = line.replace(/\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]/g, '').trim();
      for (const hit of hits) {
        const frac = hit[3] ? Number(hit[3].padEnd(3, '0')) : 0;
        timed.push({time: (Number(hit[1]) * 60 + Number(hit[2])) * 1000 + frac, text: text || '♪'});
      }
    }
    if (timed.length) return {timed: timed.sort((a, b) => a.time - b.time), plain: []};
    return {timed: [], plain};
  }

  function renderLyrics() {
    const item = current();
    const source = item ? collections.lyrics[item.id] || '' : '';
    const key = `${item?.id || ''}\0${source}`;
    if (key === lyricsRenderedKey) return;
    lyricsRenderedKey = key;
    lyricTimeline = [];
    lyricActive = -2;
    const lines = el('lyricsLines');
    lines.replaceChildren();
    el('lyricsType').textContent = '';
    if (!item) {
      lines.appendChild(button('곡을 선택하면 가사가 보여요', 'lyrics-placeholder', () => navigate('library')));
      return;
    }
    if (!source.trim()) {
      const box = document.createElement('div');
      box.className = 'lyrics-empty';
      box.appendChild(button('가사가 없습니다\n눌러서 직접 입력하거나 붙여넣으세요.', 'lyrics-placeholder', () => lyricsDialog(item.id), 'edit'));
      const row = document.createElement('div');
      row.className = 'lyrics-empty-actions';
      row.appendChild(button('인터넷에서 가사 검색', '', () => { lyricsDialog(item.id); searchLyrics(item, 'google'); }, 'globe'));
      row.appendChild(button('복사한 가사 붙여넣기', '', () => quickPasteLyrics(item.id), 'paste'));
      box.appendChild(row);
      lines.appendChild(box);
      return;
    }
    const parsed = parseLyrics(source);
    if (parsed.timed.length) {
      el('lyricsType').textContent = 'SYNC';
      for (const word of parsed.timed) {
        const node = button(word.text, 'lyric-line timed', () => seekTo(word.time));
        node.setAttribute('aria-label', `${time(word.time)} ${word.text} 이동`);
        lines.appendChild(node);
        lyricTimeline.push({time: word.time, node});
      }
    } else {
      el('lyricsType').textContent = 'TEXT';
      const block = document.createElement('div');
      block.className = 'lyrics-plain';
      block.textContent = parsed.plain.join('\n').trim();
      lines.appendChild(block);
    }
    updateLyricHighlight();
  }

  function updateLyricHighlight() {
    if (!lyricsOpen || !lyricTimeline.length) return;
    let next = -1;
    for (let i = 0; i < lyricTimeline.length; i++) {
      if (position + 100 >= lyricTimeline[i].time) next = i;
      else break;
    }
    if (next === lyricActive) return;
    if (lyricActive >= 0) lyricTimeline[lyricActive].node.classList.remove('active');
    lyricActive = next;
    if (next >= 0) {
      const node = lyricTimeline[next].node;
      node.classList.add('active');
      const box = el('lyricsLines');
      box.scrollTo({top: node.offsetTop - box.offsetTop - box.clientHeight / 2 + node.offsetHeight / 2, behavior: 'smooth'});
    }
  }

  function lyricsQuery(item) {
    const clean = String(item.name || '')
      .replace(/\((?:official|lyric|lyrics|audio|mv|m\/v|music video|visualizer|live|4k|hd)[^)]*\)/ig, '')
      .replace(/\[(?:official|lyric|lyrics|audio|mv|m\/v|music video|4k|hd|가사)[^\]]*\]/ig, '')
      .replace(/\b(?:official\s+)?(?:music\s+video|m\/v|mv|lyric video|audio)\b/ig, '')
      .replace(/[|｜].*$/, '')
      .replace(/\s{2,}/g, ' ').trim();
    const generic = ['내 음악', '내 영상', 'YouTube', '내 파일', 'Music', 'Download', 'Recordings'];
    const artist = item.artist && !generic.includes(item.artist) && item.artist !== item.folder && !clean.includes(item.artist) ? item.artist : '';
    return `${clean} ${artist} 가사`.replace(/\s{2,}/g, ' ').trim();
  }

  function searchLyrics(item, engine) {
    const q = encodeURIComponent(lyricsQuery(item));
    const url = engine === 'naver' ? `https://m.search.naver.com/search.naver?query=${q}`
      : engine === 'bugs' ? `https://m.bugs.co.kr/search/track?q=${encodeURIComponent(lyricsQuery(item).replace(/\s*가사$/, ''))}`
      : `https://www.google.com/search?q=${q}`;
    if (has('openBrowser')) {
      window.Android.openBrowser(url, 'lyrics');
      toast('가사를 길게 눌러 선택한 뒤 상단 “선택한 가사 가져오기”를 누르세요.');
    } else window.open(url, '_blank', 'noopener');
  }

  function readClipboard() {
    if (has('getClipboard')) return Promise.resolve(window.Android.getClipboard() || '');
    if (navigator.clipboard?.readText) return navigator.clipboard.readText().catch(() => '');
    return Promise.resolve('');
  }

  function insertLyrics(text, mode = 'auto') {
    const area = el('dialogLyrics');
    const clean = String(text || '').replace(/\r\n?/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
    if (!clean) { toast('붙여넣을 내용이 없어요. 먼저 가사를 복사하세요.'); return false; }
    if (mode === 'replace' || !area.value.trim()) area.value = clean;
    else area.value = `${area.value.replace(/\s+$/, '')}\n\n${clean}`;
    area.scrollTop = 0;
    toast('가사를 넣었어요. 확인 후 “가사 저장”을 누르세요.');
    return true;
  }

  function quickPasteLyrics(id) {
    lyricsDialog(id);
    readClipboard().then(text => insertLyrics(text));
  }

  window.onLyricsPicked = text => {
    if (el('dialogBackdrop').hidden || el('dialogBackdrop').dataset.kind !== 'lyrics') {
      if (current()) lyricsDialog(currentId); else return;
    }
    insertLyrics(text);
  };

  function lyricsDialog(id) {
    const item = track(id);
    if (!item) { toast('가사를 등록할 곡을 먼저 선택하세요.'); return; }
    modal('가사 입력 / 편집', `${item.name} · 인터넷에서 찾아 붙여넣거나 직접 입력하세요. [00:12.34] 형식이면 싱크 가사로 표시돼요.`, '가사 저장', () => {
      const contents = el('dialogLyrics').value.replace(/\r\n?/g, '\n').trim();
      if (contents) collections.lyrics[id] = contents;
      else delete collections.lyrics[id];
      saveCollections();
      lyricsRenderedKey = null;
      closeModal();
      if (currentId === id) { setLyricsOpen(true); renderLyrics(); }
      renderLibrary();
      toast(contents ? '가사를 저장했습니다. 다음 실행에도 유지됩니다.' : '가사를 지웠습니다.');
    });
    el('dialogBackdrop').dataset.kind = 'lyrics';
    const tools = el('dialogTools');
    tools.hidden = false;
    tools.appendChild(button('Google 검색', 'tool web', () => searchLyrics(item, 'google'), 'globe'));
    tools.appendChild(button('네이버', 'tool web', () => searchLyrics(item, 'naver'), 'globe'));
    tools.appendChild(button('벅스', 'tool web', () => searchLyrics(item, 'bugs'), 'globe'));
    tools.appendChild(button('붙여넣기', 'tool paste', () => readClipboard().then(text => insertLyrics(text)), 'paste'));
    tools.appendChild(button('지우기', 'tool clear', () => { el('dialogLyrics').value = ''; el('dialogLyrics').focus(); }, 'trash'));
    const q = document.createElement('small');
    q.className = 'tools-query';
    q.textContent = `검색어: ${lyricsQuery(item)}`;
    tools.appendChild(q);
    el('dialogLyrics').hidden = false;
    el('dialogLyrics').value = collections.lyrics[id] || '';
  }

  // ---------------------------------------------------------------- now playing
  function renderNow() {
    const item = current();
    const yt = isYT(item);
    el('currentTitle').textContent = item ? item.name : '음악을 추가해 보세요';
    el('currentArtist').textContent = item ? item.artist || '내 기기 · 오프라인' : '라이브러리에서 곡을 선택하세요';
    el('trackEyebrow').textContent = item ? (yt ? 'YOUTUBE · APP PLAYER' : item.type === 'video' ? 'NOW WATCHING · VIDEO' : 'NOW PLAYING · AUDIO') : 'READY TO PLAY';
    el('sourceLabel').textContent = yt ? '● YOUTUBE · ONLINE' : '● 100% OFFLINE';
    el('sourceLabel').classList.toggle('online', yt);
    const fav = el('favoriteCurrent');
    fav.classList.toggle('active', !!item?.favorite);
    fav.setAttribute('aria-label', item?.favorite ? '즐겨찾기 해제' : '즐겨찾기 추가');
    fav.disabled = !item;
    el('vinylWrap').classList.toggle('spinning', !!(item && item.type === 'audio' && playing && !lyricsOpen));
    el('miniEqualizer').classList.toggle('active', playing);
    document.querySelector('.vinyl-sticker use').setAttribute('href', yt ? '#i-youtube' : item?.type === 'video' ? '#i-video' : '#i-music');
    el('artworkTabLabel').textContent = yt ? '영상' : item?.type === 'video' ? '영상' : '음악';
    el('visualCaption').textContent = yt ? 'YOUTUBE' : item?.type === 'video' ? 'VIDEO COLLECTION' : 'YOUR MUSIC, YOUR MOMENT';
    el('footerHint').textContent = yt ? 'YouTube · 앱 내 재생 · 삽입 제한 영상은 Chrome으로 열기'
      : item?.type === 'video' ? (native ? '전체 화면으로 영상 재생' : '화면에서 영상 재생')
      : (collections.queueId && queueList() ? `${queueList().name} · 화면 꺼짐 재생` : native ? '화면을 꺼도 음악은 계속' : '내 기기에서 직접 재생');
    el('togglePlayback').setAttribute('aria-label', playing ? '일시정지' : '재생');
    document.querySelector('#playIcon use').setAttribute('href', playing ? '#i-pause' : '#i-play');
    el('shuffle').classList.toggle('on', shuffle);
    el('repeat').classList.toggle('on', repeat !== 0);
    el('repeatOne').hidden = repeat !== 2;
    el('speed').value = String(speedSteps.find(v => Math.abs(v - speed) < 0.02) || 1);
    el('artworkTab').classList.toggle('active', !lyricsOpen);
    el('lyricsTab').classList.toggle('active', lyricsOpen);
    el('artworkTab').setAttribute('aria-selected', String(!lyricsOpen));
    el('lyricsTab').setAttribute('aria-selected', String(lyricsOpen));
    el('editLyrics').disabled = !item;
    el('vinylWrap').hidden = lyricsOpen;
    el('lyricsPanel').hidden = !lyricsOpen;
    el('visual').classList.toggle('lyrics-open', lyricsOpen);
    el('visual').classList.toggle('youtube', yt && !lyricsOpen);
    el('visual').classList.toggle('has-browser-video', !native && item?.type === 'video' && !lyricsOpen);
    browserVideo.hidden = !!(native || item?.type !== 'video' || lyricsOpen);
    if (lyricsOpen) { renderLyrics(); updateLyricHighlight(); }
    const mini = el('miniPlayer');
    mini.hidden = !item || yt || (view === 'library' && activeFilter === 'youtube');
    if (item) {
      el('miniTitle').textContent = item.name;
      el('miniArtist').textContent = item.artist || '내 파일';
      el('miniToggle').setAttribute('aria-label', playing ? '일시정지' : '재생');
      el('miniToggle').replaceChildren(icon(playing ? 'pause' : 'play'));
      const art = el('miniArt');
      art.replaceChildren();
      if (yt && item.thumb) { const img = document.createElement('img'); img.src = item.thumb; img.alt = ''; art.appendChild(img); }
      else art.appendChild(icon(item.type === 'video' ? 'video' : 'music'));
    }
    renderProgress();
  }

  function renderProgress() {
    const item = current();
    const whole = duration || item?.duration || 0;
    const pct = whole ? Math.max(0, Math.min(100, position / whole * 100)) : 0;
    if (!dragging) {
      el('progress').value = Math.round(pct * 10);
      el('progress').style.setProperty('--percent', `${pct}%`);
      el('elapsed').textContent = time(position);
    }
    el('duration').textContent = time(whole);
    el('progress').disabled = !item || !whole;
  }

  function renderAll() { renderLibrary(); renderPlaylists(); renderNow(); }

  function setOptions() {
    if (native) window.Android.setOptions(shuffle, repeat, speed);
    else storage.set('pulse-options', JSON.stringify({shuffle, repeat, speed}));
    if (browserElement) browserElement.playbackRate = speed;
    renderNow();
  }

  function stopBrowser() {
    browserAudio.pause(); browserVideo.pause();
    browserAudio.removeAttribute('src'); browserVideo.removeAttribute('src');
    browserVideo.hidden = true;
    browserAudio.load(); browserVideo.load();
    browserElement = null;
  }

  // ---------------------------------------------------------------- YouTube: official on-demand in-app IFrame
  // No Data API key, OAuth client, stream extraction or ad manipulation is used.
  // Chrome login cookies stay private to Chrome and are not copied to WebView.
  let inlineYouTubeId = '';
  function ytStop() {
    const overlay = el('ytInlinePlayer');
    if (overlay && !overlay.hidden) {
      overlay.hidden = true;
      el('ytInlineFrame').removeAttribute('src'); // unload stops playback, not just hides it
      document.body.classList.remove('yt-inline-open');
    }
    inlineYouTubeId = '';
    if (has('setYouTubeOverlayActive')) window.Android.setYouTubeOverlayActive(false);
    document.body.classList.remove('yt-pip');
    playing = false;
    preparing = false;
  }

  function updateInlineFavorite() {
    const item = track(`yt:${inlineYouTubeId}`);
    const control = el('ytInlineFavorite');
    if (!control) return;
    control.textContent = item?.favorite ? '♥' : '♡';
    control.classList.toggle('on', !!item?.favorite);
    control.setAttribute('aria-label', item?.favorite ? 'YouTube 즐겨찾기 해제' : 'YouTube 즐겨찾기 추가');
  }

  function openInlineYouTube(item) {
    const videoId = ytIdFrom(item?.videoId || item?.id.slice(3));
    if (!videoId) { toast('영상 주소가 올바르지 않습니다.'); return; }
    ytStop();
    inlineYouTubeId = videoId;
    const panel = el('ytInlinePlayer');
    const frame = el('ytInlineFrame');
    el('ytInlineTitle').textContent = item.name || 'YouTube 영상';
    updateInlineFavorite();
    panel.hidden = false;
    document.body.classList.add('yt-inline-open');
    if (has('setYouTubeOverlayActive')) window.Android.setYouTubeOverlayActive(true);
    el('miniPlayer').hidden = true;
    const saveButton = el('ytInlineSave');
    if (saveButton) saveButton.textContent = track(`yt:${videoId}`) ? '✓ 내 영상에 저장됨' : '＋ 내 영상 링크 저장';
    // A document loaded from appassets.androidplatform.net sends the HTTP
    // Referer that YouTube now requires (error 153 otherwise). The origin
    // parameter names the embedding origin, NOT a Google Cloud OAuth ID.
    const origin = 'https://appassets.androidplatform.net';
    frame.src = `https://www.youtube.com/embed/${videoId}?playsinline=1&autoplay=1&enablejsapi=1&origin=${encodeURIComponent(origin)}&rel=0`;
    el('ytInlineClose').focus();
  }

  window.onPipChanged = pip => {
    // The official YouTube iframe remains visible in Android's Picture-in-Picture.
    document.body.classList.toggle('yt-pip', !!pip);
  };

  function saveInlineYouTube() {
    const videoId = inlineYouTubeId;
    if (!videoId) return;
    const existing = track(`yt:${videoId}`);
    if (existing) { toast('이미 내 YouTube 보관함에 저장된 영상입니다.'); return; }
    addYouTube(videoId, false, {title: el('ytInlineTitle').textContent});
    el('ytInlineSave').textContent = '✓ 내 영상에 저장됨';
  }

  function playYouTubeInsideApp() {
    const videoId = inlineYouTubeId;
    if (!videoId) return;
    ytStop();
    renderNow();
    const url = `https://m.youtube.com/watch?v=${videoId}`;
    if (native && has('openBrowser')) window.Android.openBrowser(url, 'youtube-watch');
    else window.open(url, '_blank', 'noopener,noreferrer');
  }

  function youtubePictureInPicture() {
    if (!inlineYouTubeId) return;
    if (has('enterMiniPlayer')) window.Android.enterMiniPlayer();
    else toast('작은 화면은 안드로이드 앱에서 지원됩니다.');
  }

  function openChromeYouTube(url) {
    // Close the embedded audio state before delegating playback to Chrome.
    if (native && has('pauseAll')) window.Android.pauseAll();
    else stopBrowser();
    playing = false;
    preparing = false;
    const mini = el('miniPlayer');
    if (mini) mini.hidden = true;
    ytStop();
    if (native && has('openChrome')) window.Android.openChrome(url);
    else if (has('openExternal')) window.Android.openExternal(url);
    else window.open(url, '_blank', 'noopener,noreferrer');
  }

  function ytPlay(item) {
    if (native && has('pauseAll')) window.Android.pauseAll();
    stopBrowser();
    ytStop();
    position = 0;
    duration = item.duration || 0;
    renderNow();
    openInlineYouTube(item);
  }

  function openYouTubePage(videoId) {
    const id = ytIdFrom(videoId);
    if (id) {
      const saved = track(`yt:${id}`);
      if (saved) { playItem(saved.id, true); return; }
      addYouTube(id, true);
      return;
    }
    ytStop();
    renderNow();
    if (native && has('openBrowser')) window.Android.openBrowser('https://m.youtube.com/', 'youtube');
    else window.open('https://m.youtube.com/', '_blank', 'noopener,noreferrer');
  }

  function openExternal(url) {
    if (has('openExternal')) window.Android.openExternal(url);
    else window.open(url, '_blank', 'noopener,noreferrer');
  }

  function openYouTubeOffline(videoId = '') {
    const id = ytIdFrom(videoId);
    ytStop();
    renderNow();
    if (!id) { toast('오프라인 저장할 YouTube 영상을 먼저 선택하세요.'); return; }
    if (has('openInYouTubeApp')) window.Android.openInYouTubeApp(id);
    else toast('오프라인 저장은 YouTube 공식 앱에서 지원되는 영상에 한해 이용할 수 있습니다.');
  }

  const DIRECT_MEDIA_EXT = /\.(?:mp4|m4v|mov|webm|mp3|m4a|aac|wav|flac|ogg|opus)$/i;
  const youtubeHosts = ['youtube.com', 'youtube-nocookie.com', 'youtu.be', 'googlevideo.com', 'ytimg.com'];
  function directMediaUrl(text) {
    try {
      const parsed = new URL(String(text || '').trim());
      const host = parsed.hostname.toLowerCase();
      if (parsed.protocol !== 'https:' || !DIRECT_MEDIA_EXT.test(decodeURIComponent(parsed.pathname))) return '';
      if (youtubeHosts.some(d => host === d || host.endsWith('.' + d))) return '';
      if (parsed.username || parsed.password) return '';
      return parsed.href;
    } catch (_) { return ''; }
  }

  function directMediaDownloadDialog() {
    modal('내 영상·음악 파일 다운로드',
      '직접 다운로드 권한이 있는 HTTPS 파일 주소(.mp4, .mp3 등)를 입력하세요. YouTube 시청 주소는 파일 주소가 아니므로 공식 YouTube 앱의 다운로드를 사용해야 합니다.',
      '파일 저장', () => {
        const url = directMediaUrl(el('dialogName').value);
        if (!url) { toast('HTTPS로 시작하는 직접 MP4·MP3 등의 파일 주소를 확인하세요.'); return; }
        if (has('downloadDirectMedia')) window.Android.downloadDirectMedia(url);
        else openExternal(url);
        closeModal();
        toast(native ? '파일 저장을 요청했어요. 알림에서 진행 상태를 확인하세요.' : '브라우저에서 파일 주소를 열었어요.');
      });
    el('dialogName').hidden = false;
    el('dialogName').type = 'url';
    el('dialogName').maxLength = 2048;
    el('dialogName').placeholder = 'https://example.com/my-video.mp4';
    el('dialogName').value = '';
    el('dialogTools').hidden = false;
    el('dialogTools').appendChild(button('주소 붙여넣기', 'tool paste', () => readClipboard().then(t => {
      el('dialogName').value = t.trim();
    }), 'paste'));
  }

  // browser-only persistence for YouTube items (native keeps them in the library)
  function persistBrowserYouTube() {
    if (native) return;
    storage.set('pulse-youtube', JSON.stringify(media.filter(isYT).map(({id, videoId, name, artist, duration: d, favorite, addedAt, thumb}) =>
      ({id, videoId, name, artist, duration: d, favorite, addedAt, thumb}))));
  }

  function addYouTube(text, play = false, meta = {}) {
    const vid = ytIdFrom(text);
    if (!vid) { toast('YouTube 링크나 영상 ID를 확인해 주세요.'); return null; }
    const id = `yt:${vid}`;
    let item = track(id);
    if (!item) {
      if (native && has('addYouTube')) {
        item = parseJson(window.Android.addYouTube(vid, meta.title || '', meta.author || ''), null);
        if (!item) { toast('추가하지 못했어요.'); return null; }
      } else {
        item = {id, videoId: vid, name: meta.title || 'YouTube 영상', artist: meta.author || 'YouTube', type: 'youtube', source: 'youtube',
          duration: 0, size: 0, favorite: false, addedAt: Date.now(), thumb: `https://i.ytimg.com/vi/${vid}/mqdefault.jpg`};
      }
      media.push(item);
      persistBrowserYouTube();
      toast(play ? '저장하고 재생할게요.' : 'YouTube 목록에 저장했어요.');
    } else if (!play) toast('이미 저장된 영상이에요.');
    renderAll();
    renderYtResults();
    if (play) playItem(id);
    return item;
  }

  window.onYouTubeAdded = (id, play) => {
    if (play && track(id)) {
      if (activeFilter !== 'youtube' && view === 'library') setFilter('youtube');
      playItem(id);
    } else renderAll();
  };
  window.onBrowserClosed = () => { renderNow(); };

  // ---------------------------------------------------------------- YouTube without OAuth clients or paid API keys
  // Chrome remains the only Google sign-in surface. Chrome cookies are not
  // accessible to this app, so subscriptions/likes cannot be mirrored here.
  let ytView = null;

  function renderYtAccount() {
    const panel = el('ytAccount');
    if (!panel) return;
    panel.hidden = false;
    panel.replaceChildren();
    panel.append(button('Chrome에서 열기 / 로그인', 'yt-login',
      () => openChromeYouTube('https://www.youtube.com/'), 'external'));
  }

  function ytSearch(query) {
    const q = String(query || '').trim();
    if (!q) { el('ytQuery').focus(); return; }
    const id = ytIdFrom(q);
    if (id) {
      addYouTube(id, true);
      el('ytQuery').value = '';
      return;
    }
    // No paid Data API is needed: the ordinary YouTube search page is browsed in-app.
    const url = `https://m.youtube.com/results?search_query=${encodeURIComponent(q)}`;
    if (native && has('openBrowser')) window.Android.openBrowser(url, 'youtube');
    else window.open(url, '_blank', 'noopener,noreferrer');
  }

  function renderYtResults() {
    const box = el('ytResults');
    if (!box) return;
    box.replaceChildren();
    renderYtAccount();
    // Keep the screen compact: browsing and saved favorites are self-explanatory.
  }

  // ---------------------------------------------------------------- play control
  function playItem(id, preserveQueue = false) {
    const item = track(id);
    if (!item) { toast('파일을 찾을 수 없습니다. 다시 추가해 주세요.'); return; }
    setQueueForPlay(preserveQueue);
    const same = id === currentId;
    const prev = current();
    if (isYT(prev) && !isYT(item)) ytStop();
    currentId = id;
    if (!isYT(item)) duration = item.duration || 0;
    if (!same) position = 0;
    if (!isYT(item)) storage.set('pulse-last-yt', '');
    if (isYT(item)) {
      storage.set('pulse-last-yt', id);
      ytPlay(item);
    } else if (native) {
      playing = false;
      preparing = true;
      // A click on a library row means PLAY, never a stale toggle of the last ID.
      window.Android.play(id);
      renderAll();
    } else if (same && browserElement) {
      if (browserElement.paused) browserElement.play().catch(() => toast('재생할 수 없습니다.'));
      else browserElement.pause();
    } else {
      stopBrowser();
      const element = item.type === 'video' ? browserVideo : browserAudio;
      browserElement = element;
      element.src = item.url;
      element.playbackRate = speed;
      if (item.type === 'video') element.controls = true;
      playing = false;
      renderAll();
      element.play().catch(() => toast('지원되지 않는 파일 형식이거나 코덱입니다.'));
    }
    if (window.matchMedia('(max-width:760px)').matches && !isYT(item) && (item.type === 'audio' || !native)) navigate('player');
  }

  function togglePlayback() {
    const item = current();
    if (!item) {
      const pool = view === 'playlists' ? listTracks(selectedList()) : sortItems(media, sortMode);
      if (!pool.length) { if (!media.length) importFiles(); else toast('재생할 곡을 선택해 주세요.'); return; }
      playItem(pool[0].id);
      return;
    }
    if (isYT(item)) { ytPlay(item); return; }
    if (native) {
      if (item.type === 'video') window.Android.play(item.id);
      else window.Android.toggle(item.id);
    } else if (browserElement) {
      if (browserElement.paused) browserElement.play().catch(() => toast('재생할 수 없습니다.'));
      else browserElement.pause();
    } else playItem(item.id, true);
  }

  function activeQueueItems(type) {
    const list = queueList();
    const pool = list ? listTracks(list) : sortItems(media, sortMode);
    return type ? pool.filter(item => item.type === type) : pool;
  }

  function nextPrevious(direction, automatic = false) {
    const item = current();
    if (!item) { const first = activeQueueItems()[0]; if (first) playItem(first.id, true); return; }
    if (native && item.type === 'audio') {
      if (direction > 0) window.Android.next(); else window.Android.previous();
      return;
    }
    const candidates = activeQueueItems(item.type);
    if (!candidates.length) return;
    const idx = candidates.findIndex(row => row.id === item.id);
    let next = idx + direction;
    if (shuffle && candidates.length > 1) {
      next = Math.floor(Math.random() * (candidates.length - 1));
      if (idx >= 0 && next >= idx) next++;
    } else if (next >= candidates.length) {
      if (automatic && repeat === 0) {
        playing = false;
        if (browserElement) browserElement.pause();
        renderNow();
        return;
      }
      next = 0;
    } else if (next < 0) next = candidates.length - 1;
    playItem(candidates[next].id, true);
  }

  function seekTo(ms) {
    const item = current();
    if (!item) return;
    position = Math.max(0, Math.round(ms));
    if (isYT(item)) { toast('YouTube 영상 재생 화면에서 위치를 조절하세요.'); return; }
    else if (native) window.Android.seekTo(position);
    else if (browserElement && Number.isFinite(browserElement.duration)) browserElement.currentTime = position / 1000;
    updateLyricHighlight();
    renderProgress();
  }

  function handleLocalFiles(files) {
    let added = 0;
    for (const file of Array.from(files)) {
      const suffix = file.name.toLowerCase().split('.').pop();
      const video = file.type.startsWith('video/') || /^(mp4|mkv|webm|mov|m4v|3gp|avi)$/.test(suffix);
      const audio = file.type.startsWith('audio/') || /^(mp3|m4a|aac|wav|flac|ogg|opus|wma|amr)$/.test(suffix);
      if (!video && !audio) continue;
      if (media.some(item => item.fileName === file.name && item.size === file.size)) continue;
      media.push({
        id: `browser:${Date.now()}:${Math.random().toString(36).slice(2)}`,
        name: file.name.replace(/\.[^/.]+$/, ''), fileName: file.name, type: video ? 'video' : 'audio',
        duration: 0, size: file.size, favorite: false, addedAt: file.lastModified || Date.now(),
        artist: video ? '내 영상' : '내 음악', url: URL.createObjectURL(file), source: 'import'
      });
      added++;
    }
    el('fileInput').value = '';
    renderAll();
    toast(added ? `${added}개 파일을 추가했습니다.` : '새로 추가할 파일이 없습니다.');
  }

  function nativeLibrary(items) {
    if (!Array.isArray(items)) return;
    media.splice(0, media.length, ...items.filter(item => item && typeof item.id === 'string'));
    if (currentId && !track(currentId)) {
      if (currentId.startsWith('yt:')) ytStop(true);
      currentId = '';
      position = duration = 0;
      playing = false;
    }
    if (!currentId && media.length) {
      const prefs = parseJson(window.Android.getPreferences(), {});
      if (track(prefs.lastId)) currentId = prefs.lastId;
    }
    // Note: playlists keep references to items that are temporarily missing (e.g. storage permission revoked)
    renderAll();
    if (inlineYouTubeId) updateInlineFavorite();
    if (activeFilter === 'youtube') renderYtResults();
  }

  window.onNativePlaybackFailure = message => {
    playing = false;
    preparing = false;
    lastError = String(message || '오디오 재생 서비스를 실행하지 못했습니다.');
    renderNow();
    toast(lastError);
  };

  window.onNativeLibrary = nativeLibrary;
  window.onNativeState = incoming => {
    if (!incoming || typeof incoming !== 'object') return;
    shuffle = !!incoming.shuffle;
    repeat = Number(incoming.repeat) || 0;
    speed = Number(incoming.speed) || 1;
    if (isYT(current())) {
      const other = incoming.id && track(incoming.id);
      if (!(other && !isYT(other) && (incoming.playing || incoming.preparing || incoming.video))) return;
    }
    const changed = incoming.id && incoming.id !== currentId && !!track(incoming.id);
    if (changed) currentId = incoming.id;
    const wasPlaying = playing;
    playing = !!incoming.playing;
    preparing = !!incoming.preparing;
    position = Number(incoming.position) || 0;
    duration = Number(incoming.duration) || current()?.duration || 0;
    if (incoming.error && incoming.error !== lastError) { lastError = incoming.error; toast(incoming.error); }
    else if (!incoming.error && (incoming.playing || incoming.preparing)) lastError = '';
    if (changed || wasPlaying !== playing) renderLibrary();
    renderNow();
  };
  window.onNativeVideoStart = () => { ytStop(); renderNow(); };
  window.onAppVisibility = visible => {
    if (visible && native && permissionGranted && scanState !== 'scanning' && Date.now() - lastScanAt > 5 * 60 * 1000) requestDeviceScan(false);
  };
  document.addEventListener('visibilitychange', () => window.onAppVisibility(document.visibilityState === 'visible'));

  window.onBackRequest = () => {
    if (!el('ytInlinePlayer').hidden) { ytStop(); renderNow(); return true; }
    if (!el('dialogBackdrop').hidden) { closeModal(); return true; }
    if (reorderMode) { reorderMode = false; renderPlaylists(); renderLibrary(); return true; }
    if (view !== 'library') { navigate('library'); return true; }
    if (search) { search = ''; el('search').value = ''; renderLibrary(); return true; }
    if (activeFilter !== 'all') { setFilter('all'); return true; }
    return false;
  };

  function bindBrowser(element) {
    element.addEventListener('loadedmetadata', () => {
      if (element !== browserElement) return;
      duration = Number.isFinite(element.duration) ? element.duration * 1000 : 0;
      if (current() && duration) current().duration = duration;
      renderProgress(); renderLibrary();
    });
    element.addEventListener('timeupdate', () => {
      if (element !== browserElement) return;
      position = element.currentTime * 1000;
      renderProgress(); updateLyricHighlight();
    });
    element.addEventListener('play', () => { if (element === browserElement) { playing = true; renderAll(); } });
    element.addEventListener('pause', () => { if (element === browserElement) { playing = false; renderAll(); } });
    element.addEventListener('ended', () => {
      if (element !== browserElement) return;
      playing = false;
      if (repeat === 2) { element.currentTime = 0; element.play().catch(() => {}); }
      else nextPrevious(1, true);
    });
    element.addEventListener('error', () => {
      if (element === browserElement) toast('지원되지 않는 파일 또는 재생 코덱입니다.');
    });
  }

  // ---------------------------------------------------------------- settings / backup
  function buildBackup() {
    return JSON.stringify({
      app: 'pulse-player', format: 1, exportedAt: new Date().toISOString(),
      collections,
      favorites: media.filter(i => i.favorite).map(i => i.id),
      youtube: media.filter(isYT).map(({videoId, id, name, artist}) => ({videoId: videoId || id.slice(3), name, artist}))
    });
  }

  function settingsDialog() {
    modal('설정 · 백업', `Pulse Player ${appVersion} · 음악 ${el('musicCount').textContent} · 영상 ${el('videoCount').textContent} · YouTube ${el('youtubeCount').textContent}`, '닫기', closeModal);
    el('dialogCancel').hidden = true;
    const p = el('dialogChoices');
    p.hidden = false;
    if (native) {
      p.appendChild(button(permissionGranted ? '기기 음악·영상 다시 불러오기' : '기기 음악·영상 불러오기 (권한 허용)', 'dialog-option', () => { closeModal(); requestDeviceScan(true); }, 'refresh'));
      if (hiddenCount) p.appendChild(button(`숨긴 기기 파일 다시 보이기 (${hiddenCount}개)`, 'dialog-option', () => {
        closeModal(); window.Android.restoreHidden(); hiddenCount = 0;
      }, 'folder'));
    }
    p.appendChild(button('파일 직접 추가', 'dialog-option', () => { closeModal(); importFiles(); }, 'plus'));
    if (native) {
      p.appendChild(button('자동 업데이트 폴더 지정 (한 번만)', 'dialog-option', () => {
        closeModal();
        if (has('chooseUpdateFolder')) window.Android.chooseUpdateFolder();
      }, 'folder'));
      p.appendChild(button('새 버전 자동 확인 · 설치', 'dialog-option', () => {
        closeModal();
        if (has('checkForUpdates')) window.Android.checkForUpdates();
      }, 'refresh'));
      p.appendChild(button('업데이트 APK 직접 선택', 'dialog-option', () => {
        closeModal();
        if (has('chooseUpdateApk')) window.Android.chooseUpdateApk();
      }, 'down'));
    }
    p.appendChild(button('Chrome에서 YouTube 로그인', 'dialog-option', () => { closeModal(); openChromeYouTube('https://www.youtube.com/'); }, 'youtube'));
    p.appendChild(button('백업 내보내기 (플레이리스트·가사·즐겨찾기·YouTube)', 'dialog-option', () => {
      const json = buildBackup();
      if (has('shareText')) window.Android.shareText(json, 'Pulse Player 백업');
      else if (navigator.clipboard) navigator.clipboard.writeText(json);
      closeModal();
    }, 'external'));
    p.appendChild(button('백업 복사하기 (클립보드)', 'dialog-option', () => {
      const json = buildBackup();
      if (has('copyText')) window.Android.copyText(json);
      else navigator.clipboard?.writeText(json);
      closeModal(); toast('백업 내용을 복사했어요. 메모 앱 등에 붙여넣어 보관하세요.');
    }, 'paste'));
    p.appendChild(button('백업 가져오기', 'dialog-option', () => { closeModal(); restoreDialog(); }, 'down'));
  }

  function restoreDialog() {
    modal('백업 가져오기', '내보낸 백업 내용을 붙여넣고 “가져오기”를 누르세요. 기존 플레이리스트와 가사는 백업 내용으로 바뀝니다.', '가져오기', () => {
      const data = parseJson(el('dialogLyrics').value.trim(), null);
      if (!data || data.app !== 'pulse-player' || !data.collections) { toast('Pulse Player 백업 형식이 아니에요.'); return; }
      if (native && has('restoreBackup')) {
        const n = window.Android.restoreBackup(JSON.stringify(data));
        if (n < 0) { toast('가져오기에 실패했어요.'); return; }
        loadCollections();
      } else {
        collections.lists = data.collections.lists || [];
        collections.lyrics = data.collections.lyrics || {};
        saveCollections();
        for (const y of data.youtube || []) addYouTube(y.videoId, false, {title: y.name, author: y.artist});
        const favs = new Set(data.favorites || []);
        media.forEach(i => { if (favs.has(i.id)) i.favorite = true; });
      }
      closeModal(); renderAll(); toast('백업을 가져왔어요.');
    });
    el('dialogLyrics').hidden = false;
    el('dialogLyrics').value = '';
    el('dialogLyrics').placeholder = '{"app":"pulse-player", … }';
    el('dialogTools').hidden = false;
    el('dialogTools').appendChild(button('붙여넣기', 'tool paste', () => readClipboard().then(t => { el('dialogLyrics').value = t; }), 'paste'));
  }

  // ---------------------------------------------------------------- init
  function init() {
    loadCollections();
    document.body.dataset.view = view;
    el('topAdd').addEventListener('click', importFiles);
    el('topSettings').addEventListener('click', settingsDialog);
    el('libraryAdd').addEventListener('click', importFiles);
    el('deviceRescan').addEventListener('click', () => requestDeviceScan(true));
    el('deviceBannerButton').addEventListener('click', () => requestDeviceScan(true));
    el('fileInput').addEventListener('change', e => handleLocalFiles(e.target.files));
    el('search').addEventListener('input', e => { search = e.target.value; renderLimit = PAGE; renderLibrary(); });
    document.querySelectorAll('.filter').forEach(node => node.addEventListener('click', () => setFilter(node.dataset.filter)));
    el('sortSelect').addEventListener('change', e => {
      const v = e.target.value;
      if (view === 'playlists') { listSortMode = v; storage.set('pulse-sort-list', v); }
      else { sortMode = v; storage.set('pulse-sort', v); }
      renderLimit = PAGE;
      renderLibrary();
      toast(`${SORT_TEXT[v]}으로 정렬했어요.`);
    });
    document.querySelectorAll('.nav-tab').forEach(tab => tab.addEventListener('click', () => {
      const to = tab.dataset.nav;
      if (to === 'youtube') { if (view !== 'library') navigate('library'); setFilter('youtube'); window.scrollTo({top: 0, behavior: 'instant'}); setTimeout(() => el('ytQuery').focus(), 80); return; }
      if (to === 'library' && activeFilter === 'youtube') setFilter('all');
      navigate(to);
    }));
    el('createPlaylist').addEventListener('click', () => nameDialog());
    el('renamePlaylist').addEventListener('click', () => { if (selectedList()) nameDialog(collections.selectedId); });
    el('deletePlaylist').addEventListener('click', deletePlaylist);
    el('addPlaylistTracks').addEventListener('click', addTracksToSelected);
    el('reorderPlaylist').addEventListener('click', () => { reorderMode = !reorderMode; renderPlaylists(); renderLibrary(); });
    el('playlistPlay').addEventListener('click', () => { const l = selectedList(); if (l) playList(l, false); });
    el('playlistShuffle').addEventListener('click', () => { const l = selectedList(); if (l) playList(l, true); });
    el('dialogCancel').addEventListener('click', closeModal);
    el('dialogClose').addEventListener('click', closeModal);
    el('dialogBackdrop').addEventListener('click', e => { if (e.target === el('dialogBackdrop')) closeModal(); });
    document.addEventListener('keydown', e => {
      if (e.key === 'Escape' && !el('dialogBackdrop').hidden) closeModal();
      if (e.key === 'Enter' && !el('dialogName').hidden && !el('dialogBackdrop').hidden) el('dialogConfirm').click();
    });
    el('artworkTab').addEventListener('click', () => setLyricsOpen(false));
    el('lyricsTab').addEventListener('click', () => setLyricsOpen(true));
    el('editLyrics').addEventListener('click', () => { if (current()) lyricsDialog(currentId); });
    el('favoriteCurrent').addEventListener('click', () => { if (current()) toggleFavorite(currentId); });
    el('togglePlayback').addEventListener('click', togglePlayback);
    el('previous').addEventListener('click', () => nextPrevious(-1));
    el('next').addEventListener('click', () => nextPrevious(1));
    el('miniInfo').addEventListener('click', () => navigate('player'));
    el('miniToggle').addEventListener('click', togglePlayback);
    el('miniPrev').addEventListener('click', () => nextPrevious(-1));
    el('miniNext').addEventListener('click', () => nextPrevious(1));
    el('ytForm').addEventListener('submit', e => { e.preventDefault(); ytSearch(el('ytQuery').value); });
    el('ytBrowse').addEventListener('click', () => openYouTubePage(''));
    el('ytInlineClose').addEventListener('click', () => { ytStop(); renderNow(); });
    el('ytInlineFavorite').addEventListener('click', () => {
      const id = `yt:${inlineYouTubeId}`;
      if (track(id)) { toggleFavorite(id); updateInlineFavorite(); }
    });
    el('ytInlineSave').addEventListener('click', saveInlineYouTube);
    el('ytInlinePip').addEventListener('click', youtubePictureInPicture);
    el('ytInlineAlternate').addEventListener('click', playYouTubeInsideApp);
    el('ytInlineOffline').addEventListener('click', () => { if (inlineYouTubeId) openYouTubeOffline(inlineYouTubeId); });
    el('ytInlinePlaylist').addEventListener('click', () => {
      const id = `yt:${inlineYouTubeId}`;
      if (track(id)) addToPlaylist(id);
    });

    el('ytOfficialDownload').addEventListener('click', () => openYouTubeOffline(isYT(current()) ? current().videoId : ''));
    el('ytFileDownload').addEventListener('click', directMediaDownloadDialog);
    el('ytPaste').addEventListener('click', () => readClipboard().then(text => {
      if (ytIdFrom(text)) addYouTube(text, false);
      else toast('복사한 내용에 YouTube 링크가 없어요. YouTube 앱에서 “공유 → 링크 복사” 후 눌러 보세요.');
    }));
    el('shuffle').addEventListener('click', () => { shuffle = !shuffle; setOptions(); toast(shuffle ? '셔플 켜짐' : '셔플 꺼짐'); });
    el('repeat').addEventListener('click', () => {
      repeat = (repeat + 1) % 3; setOptions();
      toast(['반복 꺼짐', '전체 반복', '한 곡 반복'][repeat]);
    });
    el('speed').addEventListener('change', e => {
      const selected = Number(e.target.value);
      if (!speedSteps.includes(selected)) return;
      speed = selected;
      setOptions();
      toast(`재생 속도: ${speed}배`);
    });
    el('progress').addEventListener('pointerdown', () => { dragging = true; });
    el('progress').addEventListener('input', e => {
      dragging = true;
      const whole = duration || current()?.duration || 0;
      const percent = Number(e.target.value) / 1000;
      el('elapsed').textContent = time(whole * percent);
      e.target.style.setProperty('--percent', `${percent * 100}%`);
    });
    const finishSeek = evt => {
      if (!dragging) return;
      dragging = false;
      const whole = duration || current()?.duration || 0;
      seekTo(whole * Number(evt.target.value) / 1000);
    };
    el('progress').addEventListener('change', finishSeek);
    el('progress').addEventListener('pointerup', finishSeek);
    bindBrowser(browserAudio); bindBrowser(browserVideo);
    if (native) {
      const prefs = parseJson(window.Android.getPreferences(), {});
      shuffle = !!prefs.shuffle; repeat = Number(prefs.repeat) || 0;
      speed = Number(prefs.speed) || 1;
      currentId = storage.get('pulse-last-yt') || prefs.lastId || '';
      hiddenCount = Number(prefs.hiddenCount) || 0;
      appVersion = prefs.version || appVersion;
      lastScanAt = Number(prefs.lastScan) || 0;
      permissionGranted = has('hasMediaPermission') ? !!window.Android.hasMediaPermission() : false;
      nativeLibrary(parseJson(window.Android.getLibrary(), []));
      window.Android.requestState();
      if (permissionGranted) requestDeviceScan(false);
      // Poll only while the native WebView is visible; media playback runs independently.
      setInterval(() => { if (document.visibilityState === 'visible' && !isYT(current())) window.Android.requestState(); }, 850);
    } else {
      const prefs = parseJson(storage.get('pulse-options') || '{}', {});
      shuffle = !!prefs.shuffle; repeat = Number(prefs.repeat) || 0;
      speed = Number(prefs.speed) || 1;
      for (const y of parseJson(storage.get('pulse-youtube') || '[]', [])) if (y && y.id) media.push({...y, type: 'youtube', source: 'youtube'});
      renderAll();
    }
  }
  document.addEventListener('DOMContentLoaded', init);
})();
