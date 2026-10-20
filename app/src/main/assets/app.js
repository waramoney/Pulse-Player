(() => {
  'use strict';

  const el = id => document.getElementById(id);
  const native = !!(window.Android && typeof window.Android.getLibrary === 'function');
  const browserAudio = el('browserAudio');
  const browserVideo = el('browserVideo');
  const media = [];
  const speedSteps = [0.75, 1, 1.25, 1.5, 2];
  const collections = {lists: [], lyrics: {}, selectedId: '', queueId: ''};
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

  const storage = {
    get(key) { try { return localStorage.getItem(key); } catch (_) { return null; } },
    set(key, val) { try { localStorage.setItem(key, val); } catch (_) { /* optional browser mode */ } }
  };
  const parseJson = (raw, fallback) => { try { return JSON.parse(raw); } catch (_) { return fallback; } };
  const track = id => media.find(item => item.id === id);
  const current = () => track(currentId);
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
    node.appendChild(document.createTextNode(text));
    node.addEventListener('click', handler);
    return node;
  };
  const uniqueStrings = (values, limit = 20000) => {
    if (!Array.isArray(values)) return [];
    return [...new Set(values.filter(value => typeof value === 'string' && value.length <= 2048))].slice(0, limit);
  };

  function toast(message) {
    const node = el('toast');
    node.textContent = message;
    node.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => node.classList.remove('show'), 2700);
  }

  function loadCollections() {
    const raw = native && typeof window.Android.getCollections === 'function'
      ? window.Android.getCollections() : storage.get('pulse-collections-v2');
    const saved = parseJson(raw || '{}', {});
    if (!saved || typeof saved !== 'object') return;
    const found = new Set();
    for (const item of (Array.isArray(saved.lists) ? saved.lists : []).slice(0, 200)) {
      if (!item || typeof item.id !== 'string' || !item.id.startsWith('list-') || found.has(item.id)) continue;
      found.add(item.id);
      collections.lists.push({
        id: item.id,
        name: String(item.name || '이름 없는 목록').trim().slice(0, 64),
        tracks: uniqueStrings(item.tracks)
      });
    }
    if (saved.lyrics && typeof saved.lyrics === 'object' && !Array.isArray(saved.lyrics)) {
      for (const [id, words] of Object.entries(saved.lyrics).slice(0, 1000)) {
        if (id.length <= 2048 && typeof words === 'string' && words.length <= 30000) {
          collections.lyrics[id] = words;
        }
      }
    }
    collections.selectedId = collections.lists.some(list => list.id === saved.selectedId) ? saved.selectedId : '';
    collections.queueId = collections.lists.some(list => list.id === saved.queueId) ? saved.queueId : '';
  }

  function saveCollections() {
    const json = JSON.stringify(collections);
    if (native && typeof window.Android.saveCollections === 'function') {
      window.Android.saveCollections(json);
    } else {
      storage.set('pulse-collections-v2', json);
    }
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

  function navigate(to, scrollTop = true) {
    view = ['library', 'playlists', 'player'].includes(to) ? to : 'library';
    document.body.dataset.view = view;
    document.querySelectorAll('.nav-tab').forEach(node => {
      const selected = node.dataset.view === view;
      node.classList.toggle('active', selected);
      if (selected) node.setAttribute('aria-current', 'page');
      else node.removeAttribute('aria-current');
    });
    renderLibrary();
    renderPlaylists();
    if (scrollTop) window.scrollTo({top: 0, behavior: 'instant'});
  }

  function renderCounts() {
    el('allCount').textContent = media.length;
    el('musicCount').textContent = media.filter(item => item.type === 'audio').length;
    el('videoCount').textContent = media.filter(item => item.type === 'video').length;
  }

  function listTracks(list) {
    return list ? list.tracks.map(id => track(id)).filter(Boolean) : [];
  }

  function renderPlaylists() {
    const panel = el('playlistCards');
    const selected = selectedList();
    panel.replaceChildren();
    if (!collections.lists.length) {
      const hint = document.createElement('p');
      hint.className = 'playlist-empty';
      hint.textContent = '아직 만든 플레이리스트가 없어요. 새 목록을 만들고 좋아하는 곡을 담아보세요.';
      panel.appendChild(hint);
    } else {
      for (const list of collections.lists) {
        const active = list.id === collections.selectedId;
        const card = button('', 'playlist-card' + (active ? ' active' : ''), () => {
          collections.selectedId = list.id;
          saveCollections();
          renderPlaylists();
          renderLibrary();
        });
        const symbol = document.createElement('span');
        symbol.className = 'playlist-icon';
        symbol.appendChild(icon('list'));
        const description = document.createElement('span');
        description.className = 'playlist-card-text';
        const name = document.createElement('strong');
        name.textContent = list.name;
        const count = document.createElement('small');
        count.textContent = `${list.tracks.length}곡 · ${list.id === collections.queueId ? '현재 재생 목록' : '나만의 목록'}`;
        description.append(name, count);
        card.append(symbol, description);
        if (active) card.appendChild(icon('chevron'));
        panel.appendChild(card);
      }
    }
    el('playlistTools').hidden = !selected;
    if (selected) {
      el('selectedPlaylistName').textContent = selected.name;
      el('selectedPlaylistCount').textContent = `${listTracks(selected).length}개 항목`;
      el('playlistPlay').disabled = listTracks(selected).length === 0;
      el('addPlaylistTracks').disabled = media.length === 0;
    }
  }

  function modal(title, hint, confirmText, onConfirm) {
    modalReturnFocus = document.activeElement;
    el('dialogBackdrop').hidden = false;
    document.body.classList.add('modal-open');
    el('dialogTitle').textContent = title;
    el('dialogHint').textContent = hint || '';
    el('dialogName').hidden = true;
    el('dialogLyrics').hidden = true;
    el('dialogSearch').hidden = true;
    el('dialogChoices').hidden = true;
    el('dialogChoices').replaceChildren();
    el('dialogConfirm').hidden = false;
    el('dialogConfirm').textContent = confirmText || '저장';
    el('dialogConfirm').onclick = onConfirm;
    el('dialogBackdrop').scrollTop = 0;
  }

  function closeModal() {
    el('dialogBackdrop').hidden = true;
    document.body.classList.remove('modal-open');
    el('dialogConfirm').onclick = null;
    el('dialogSearch').oninput = null;
    if (modalReturnFocus?.isConnected) modalReturnFocus.focus();
    modalReturnFocus = null;
  }

  function nameDialog(id = '', addTrackId = '') {
    const existing = collections.lists.find(item => item.id === id);
    modal(existing ? '플레이리스트 이름 변경' : '새 플레이리스트',
      existing ? '목록 안의 음악과 순서는 그대로 유지됩니다.' : '어떤 음악을 모을지 이름을 정해보세요.',
      existing ? '변경' : '만들기', () => {
        const name = el('dialogName').value.trim();
        if (!name) { toast('플레이리스트 이름을 입력하세요.'); el('dialogName').focus(); return; }
        if (existing) existing.name = name;
        else {
          const list = {id: makeId(), name, tracks: addTrackId ? [addTrackId] : []};
          collections.lists.push(list);
          collections.selectedId = list.id;
        }
        saveCollections();
        closeModal();
        renderPlaylists();
        renderLibrary();
        toast(existing ? '이름을 변경했습니다.' : '플레이리스트를 만들었습니다.');
        if (!existing) navigate('playlists');
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
    modal(`'${list.name}' 곡 선택`, '추가하거나 제외할 음악·영상을 체크하고 저장하세요.', '선택 저장', () => {
      const allChecked = Array.from(el('dialogChoices').querySelectorAll('input[type="checkbox"]:checked')).map(x => x.value);
      const set = new Set(allChecked);
      // Keep the existing relative order; append newly checked items in library order.
      list.tracks = [...list.tracks.filter(id => set.has(id)), ...media.map(row => row.id).filter(id => set.has(id) && !list.tracks.includes(id))];
      saveCollections(); closeModal(); renderPlaylists(); renderLibrary();
      toast(`'${list.name}'에 ${list.tracks.length}개 항목을 저장했습니다.`);
    });
    el('dialogSearch').hidden = false;
    el('dialogChoices').hidden = false;
    const controls = el('dialogChoices');
    const selected = new Set(list.tracks);
    const nodes = [];
    for (const item of media) {
      const label = document.createElement('label');
      label.className = 'choice-track';
      const check = document.createElement('input');
      check.type = 'checkbox';
      check.value = item.id;
      check.checked = selected.has(item.id);
      const name = document.createElement('span');
      name.textContent = item.name;
      const kind = document.createElement('small');
      kind.textContent = item.type === 'video' ? 'VIDEO' : 'AUDIO';
      label.append(check, name, kind);
      controls.append(label);
      nodes.push({label, item});
    }
    el('dialogSearch').oninput = evt => {
      const q = evt.target.value.trim().toLocaleLowerCase('ko');
      for (const {label, item} of nodes) label.hidden = !!q && !`${item.name} ${item.artist}`.toLocaleLowerCase('ko').includes(q);
    };
  }

  function addToPlaylist(id) {
    const item = track(id);
    if (!item) return;
    if (!collections.lists.length) { nameDialog('', id); return; }
    modal('플레이리스트에 담기', `'${item.name}'을 추가할 목록을 선택하세요.`, '닫기', closeModal);
    el('dialogChoices').hidden = false;
    for (const list of collections.lists) {
      const included = list.tracks.includes(id);
      el('dialogChoices').appendChild(button(
        `${included ? '✓ ' : '+ '}${list.name}  ·  ${list.tracks.length}곡`, 'dialog-option', () => {
          if (included) list.tracks = list.tracks.filter(trackId => trackId !== id);
          else list.tracks.push(id);
          saveCollections(); closeModal(); renderPlaylists(); renderLibrary();
          toast(included ? '해당 목록에서 제외했습니다.' : `'${list.name}'에 추가했습니다.`);
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
    const dest = idx + offset;
    if (idx < 0 || dest < 0 || dest >= list.tracks.length) return;
    [list.tracks[idx], list.tracks[dest]] = [list.tracks[dest], list.tracks[idx]];
    saveCollections(); renderLibrary();
    toast('플레이리스트 순서를 바꿨습니다.');
  }

  function removeFromSelected(id) {
    const list = selectedList();
    if (!list) return;
    list.tracks = list.tracks.filter(itemId => itemId !== id);
    saveCollections(); renderPlaylists(); renderLibrary();
    toast('플레이리스트에서 제외했습니다. 원본 파일은 그대로입니다.');
  }

  function trackActions(id) {
    const item = track(id);
    if (!item) return;
    const selected = view === 'playlists' ? selectedList() : null;
    modal(item.name, '원하는 작업을 선택하세요.', '닫기', closeModal);
    const panel = el('dialogChoices');
    panel.hidden = false;
    panel.appendChild(button('플레이리스트에 추가 / 제외', 'dialog-option', () => {
      closeModal(); addToPlaylist(id);
    }, 'list-add'));
    panel.appendChild(button(collections.lyrics[id] ? '가사 보기 / 편집' : '가사 입력', 'dialog-option', () => {
      closeModal(); lyricsDialog(id);
    }, 'lyrics'));
    if (selected && selected.tracks.includes(id)) {
      const idx = selected.tracks.indexOf(id);
      if (idx > 0) panel.appendChild(button('목록에서 위로 이동', 'dialog-option', () => {
        closeModal(); moveTrack(id, -1);
      }, 'up'));
      if (idx < selected.tracks.length - 1) panel.appendChild(button('목록에서 아래로 이동', 'dialog-option', () => {
        closeModal(); moveTrack(id, 1);
      }, 'down'));
      panel.appendChild(button('현재 플레이리스트에서 제외', 'dialog-option', () => {
        closeModal(); removeFromSelected(id);
      }, 'trash'));
    }
    panel.appendChild(button('라이브러리에서 파일 제거', 'dialog-option danger', () => {
      closeModal(); removeFile(id);
    }, 'trash'));
  }

  function toggleFavorite(id) {
    const item = track(id);
    if (!item) return;
    if (native) window.Android.toggleFavorite(id);
    else {
      item.favorite = !item.favorite;
      renderLibrary(); renderNow();
    }
  }

  function removeFile(id) {
    const item = track(id);
    if (!item) return;
    modal('라이브러리에서 제거', `'${item.name}'을 앱 목록에서 제거할까요? 원본 파일은 삭제하지 않습니다.`,
      '목록에서 제거', () => {
        refreshCollectionReferences(id);
        if (native) window.Android.remove(id);
        else {
          if (id === currentId) { stopBrowser(); currentId = ''; position = duration = 0; playing = false; }
          const index = media.findIndex(row => row.id === id);
          if (index >= 0) {
            const [removed] = media.splice(index, 1);
            if (removed.url) URL.revokeObjectURL(removed.url);
          }
          renderAll();
        }
        closeModal();
        toast('목록에서 제거했습니다. 원본 파일은 보존됩니다.');
      });
  }

  function createTrackRow(item) {
    const row = document.createElement('article');
    row.className = 'track-row' + (currentId === item.id ? ' current' : '');
    const visual = document.createElement('div');
    visual.className = `media-tile ${item.type}`;
    visual.appendChild(icon(item.type === 'video' ? 'video' : 'music'));
    row.appendChild(visual);

    const name = button('', 'row-body', () => playItem(item.id));
    name.title = item.fileName || item.name;
    name.setAttribute('aria-label', `${item.name} 재생`);
    const title = document.createElement('span');
    title.className = 'row-title'; title.textContent = item.name || '이름 없는 파일';
    const meta = document.createElement('span');
    meta.className = 'row-meta';
    const kind = document.createElement('span');
    kind.className = 'kind-tag' + (item.type === 'video' ? ' video' : '');
    kind.textContent = item.type === 'video' ? 'VIDEO' : 'AUDIO';
    const artist = document.createElement('span');
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
    const fav = button('', 'row-heart' + (item.favorite ? ' active' : ''), () => toggleFavorite(item.id), 'heart');
    fav.title = item.favorite ? '즐겨찾기 해제' : '즐겨찾기 추가';
    fav.setAttribute('aria-label', fav.title);
    row.appendChild(fav);
    const menu = button('', 'row-menu', () => trackActions(item.id), 'more');
    menu.title = '플레이리스트 및 가사 관리';
    menu.setAttribute('aria-label', `${item.name} 더보기`);
    row.appendChild(menu);
    return row;
  }

  function emptyState(why) {
    const wrapper = document.createElement('div');
    wrapper.className = 'empty-state';
    const illustration = document.createElement('div');
    illustration.className = 'empty-illustration';
    illustration.appendChild(icon(why === 'missing-list' ? 'list' : (why === 'empty-list' ? 'list-add' : why === 'search' ? 'search' : 'folder')));
    const heading = document.createElement('h3');
    heading.textContent = ({'missing-list':'플레이리스트를 만들어 보세요', 'empty-list':'목록이 아직 비어 있어요', 'search':'검색 결과가 없어요', 'library':'아직 추가된 파일이 없어요'})[why];
    const p = document.createElement('p');
    p.textContent = ({'missing-list':'새 목록을 만든 다음 원하는 음악을 담아 보세요.', 'empty-list':'위의 “곡 담기” 버튼을 눌러 추가해 보세요.', 'search':'다른 검색어나 카테고리를 선택해 보세요.', 'library':'휴대폰의 음악과 영상을 먼저 추가해 보세요.'})[why];
    wrapper.append(illustration, heading, p);
    if (why === 'library') wrapper.appendChild(button('+ 첫 파일 추가하기', '', importFiles));
    if (why === 'missing-list') wrapper.appendChild(button('+ 새 플레이리스트', '', () => nameDialog()));
    if (why === 'empty-list') wrapper.appendChild(button('+ 곡 담기', '', addTracksToSelected));
    return wrapper;
  }

  function renderLibrary() {
    renderCounts();
    const isList = view === 'playlists';
    const selected = isList ? selectedList() : null;
    el('libraryHeading').textContent = isList ? '목록의 음악' : '내 라이브러리';
    const list = el('trackList');
    list.replaceChildren();
    const pool = isList ? listTracks(selected) : media;
    el('mediaCount').textContent = pool.length;
    const phrase = search.trim().toLocaleLowerCase('ko');
    const result = pool.filter(item => {
      if (activeFilter === 'favorite' && !item.favorite) return false;
      if (activeFilter === 'audio' && item.type !== 'audio') return false;
      if (activeFilter === 'video' && item.type !== 'video') return false;
      return !phrase || `${item.name} ${item.artist || ''} ${item.fileName || ''}`.toLocaleLowerCase('ko').includes(phrase);
    });
    el('searchCount').hidden = !phrase;
    el('searchCount').textContent = `${result.length}개`;
    if (!result.length) {
      const why = isList && !selected ? 'missing-list' : isList && !pool.length ? 'empty-list' : !media.length ? 'library' : 'search';
      list.appendChild(emptyState(why));
    } else {
      const fragment = document.createDocumentFragment();
      for (const item of result) fragment.appendChild(createTrackRow(item));
      list.appendChild(fragment);
    }
  }

  function activeQueueItems(type) {
    const list = queueList();
    const pool = list ? listTracks(list) : media;
    return type ? pool.filter(item => item.type === type) : pool;
  }

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
      lines.appendChild(button('가사가 없습니다\n여기를 눌러 직접 입력하세요.', 'lyrics-placeholder', () => lyricsDialog(item.id), 'edit'));
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

  function lyricsDialog(id) {
    const item = track(id);
    if (!item) { toast('가사를 등록할 곡을 먼저 선택하세요.'); return; }
    modal('가사 입력 / 편집', `${item.name} · 일반 가사 또는 [00:12.34] 형식의 싱크 가사를 입력하세요.`, '가사 저장', () => {
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
    el('dialogLyrics').hidden = false;
    el('dialogLyrics').value = collections.lyrics[id] || '';
    setTimeout(() => el('dialogLyrics').focus(), 30);
  }

  function renderNow() {
    const item = current();
    el('currentTitle').textContent = item ? item.name : '음악을 추가해 보세요';
    el('currentArtist').textContent = item ? item.artist || '내 기기 · 오프라인' : '내 기기에서 파일을 선택하세요';
    el('trackEyebrow').textContent = item ? (item.type === 'video' ? 'NOW WATCHING · VIDEO' : 'NOW PLAYING · AUDIO') : 'READY TO PLAY';
    const fav = el('favoriteCurrent');
    fav.classList.toggle('active', !!item?.favorite);
    fav.setAttribute('aria-label', item?.favorite ? '즐겨찾기 해제' : '즐겨찾기 추가');
    fav.disabled = !item;
    el('vinylWrap').classList.toggle('spinning', !!(item && item.type === 'audio' && playing && !lyricsOpen));
    el('miniEqualizer').classList.toggle('active', playing);
    document.querySelector('.vinyl-sticker use').setAttribute('href', item?.type === 'video' ? '#i-video' : '#i-music');
    el('visualCaption').textContent = item?.type === 'video' ? 'VIDEO COLLECTION' : 'YOUR MUSIC, YOUR MOMENT';
    el('footerHint').textContent = item?.type === 'video' ? (native ? '전체 화면으로 영상 재생' : '화면에서 영상 재생')
      : (collections.queueId && queueList() ? `${queueList().name} · 화면 꺼짐 재생` : native ? '화면을 꺼도 음악은 계속' : '내 기기에서 직접 재생');
    el('togglePlayback').setAttribute('aria-label', playing ? '일시정지' : '재생');
    document.querySelector('#playIcon use').setAttribute('href', playing ? '#i-pause' : '#i-play');
    el('shuffle').classList.toggle('on', shuffle);
    el('repeat').classList.toggle('on', repeat !== 0);
    el('repeatOne').hidden = repeat !== 2;
    el('speed').firstChild.textContent = `${speed.toFixed(2).replace(/0$/, '').replace(/\.0$/, '.0')}× `;
    el('artworkTab').classList.toggle('active', !lyricsOpen);
    el('lyricsTab').classList.toggle('active', lyricsOpen);
    el('artworkTab').setAttribute('aria-selected', String(!lyricsOpen));
    el('lyricsTab').setAttribute('aria-selected', String(lyricsOpen));
    el('editLyrics').disabled = !item;
    el('vinylWrap').hidden = lyricsOpen;
    el('lyricsPanel').hidden = !lyricsOpen;
    el('visual').classList.toggle('lyrics-open', lyricsOpen);
    el('visual').classList.toggle('has-browser-video', !native && item?.type === 'video' && !lyricsOpen);
    browserVideo.hidden = !!(native || item?.type !== 'video' || lyricsOpen);
    if (lyricsOpen) { renderLyrics(); updateLyricHighlight(); }
    const mini = el('miniPlayer');
    mini.hidden = !item;
    if (item) {
      el('miniTitle').textContent = item.name;
      el('miniArtist').textContent = item.artist || '내 파일';
      el('miniToggle').setAttribute('aria-label', playing ? '일시정지' : '재생');
      el('miniToggle').replaceChildren(icon(playing ? 'pause' : 'play'));
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
    else {
      storage.set('pulse-options', JSON.stringify({shuffle, repeat, speed}));
      if (browserElement) browserElement.playbackRate = speed;
    }
    renderNow();
  }

  function stopBrowser() {
    browserAudio.pause(); browserVideo.pause();
    browserAudio.removeAttribute('src'); browserVideo.removeAttribute('src');
    browserVideo.hidden = true;
    browserAudio.load(); browserVideo.load();
    browserElement = null;
  }

  function playItem(id, preserveQueue = false) {
    const item = track(id);
    if (!item) { toast('파일을 찾을 수 없습니다. 다시 추가해 주세요.'); return; }
    setQueueForPlay(preserveQueue);
    const same = id === currentId;
    currentId = id;
    duration = item.duration || 0;
    if (!same) position = 0;
    if (native) {
      playing = false;
      preparing = true;
      if (same) window.Android.toggle(id);
      else window.Android.play(id);
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
    if (window.matchMedia('(max-width:760px)').matches) navigate('player');
  }

  function togglePlayback() {
    const item = current();
    if (!item) {
      const pool = view === 'playlists' ? listTracks(selectedList()) : media;
      if (!pool.length) { if (!media.length) importFiles(); else toast('재생할 곡을 선택해 주세요.'); return; }
      playItem(pool[0].id);
      return;
    }
    if (native) {
      if (item.type === 'video') window.Android.play(item.id);
      else window.Android.toggle(item.id);
    } else if (browserElement) {
      if (browserElement.paused) browserElement.play().catch(() => toast('재생할 수 없습니다.'));
      else browserElement.pause();
    } else playItem(item.id, true);
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
    if (native) window.Android.seekTo(position);
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
        duration: 0, size: file.size, favorite: false, addedAt: Date.now(),
        artist: video ? '내 영상' : '내 음악', url: URL.createObjectURL(file)
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
      currentId = '';
      position = duration = 0;
      playing = false;
    }
    if (!currentId && media.length) {
      const prefs = parseJson(window.Android.getPreferences(), {});
      if (track(prefs.lastId)) currentId = prefs.lastId;
    }
    const available = new Set(media.map(item => item.id));
    let changed = false;
    for (const list of collections.lists) {
      const before = list.tracks.length;
      list.tracks = list.tracks.filter(id => available.has(id));
      changed ||= list.tracks.length !== before;
    }
    if (changed) saveCollections();
    renderAll();
  }

  window.onNativeLibrary = nativeLibrary;
  window.onNativeState = incoming => {
    if (!incoming || typeof incoming !== 'object') return;
    const changed = incoming.id && incoming.id !== currentId && !!track(incoming.id);
    if (changed) currentId = incoming.id;
    const wasPlaying = playing;
    playing = !!incoming.playing;
    preparing = !!incoming.preparing;
    position = Number(incoming.position) || 0;
    duration = Number(incoming.duration) || current()?.duration || 0;
    shuffle = !!incoming.shuffle;
    repeat = Number(incoming.repeat) || 0;
    speed = Number(incoming.speed) || 1;
    if (incoming.error && incoming.error !== lastError) { lastError = incoming.error; toast(incoming.error); }
    if (changed || wasPlaying !== playing) renderLibrary();
    renderNow();
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

  function init() {
    loadCollections();
    document.body.dataset.view = view;
    el('topAdd').addEventListener('click', importFiles);
    el('libraryAdd').addEventListener('click', importFiles);
    el('fileInput').addEventListener('change', e => handleLocalFiles(e.target.files));
    el('search').addEventListener('input', e => { search = e.target.value; renderLibrary(); });
    document.querySelectorAll('.filter').forEach(node => node.addEventListener('click', () => {
      activeFilter = node.dataset.filter;
      document.querySelectorAll('.filter').forEach(tab => {
        const active = tab === node;
        tab.classList.toggle('active', active);
        tab.setAttribute('aria-selected', String(active));
      });
      renderLibrary();
    }));
    document.querySelectorAll('.nav-tab').forEach(tab => tab.addEventListener('click', () => navigate(tab.dataset.view)));
    el('createPlaylist').addEventListener('click', () => nameDialog());
    el('renamePlaylist').addEventListener('click', () => { if (selectedList()) nameDialog(collections.selectedId); });
    el('deletePlaylist').addEventListener('click', deletePlaylist);
    el('addPlaylistTracks').addEventListener('click', addTracksToSelected);
    el('playlistPlay').addEventListener('click', () => {
      const pool = listTracks(selectedList());
      if (pool.length) playItem(pool[0].id);
    });
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
    el('shuffle').addEventListener('click', () => { shuffle = !shuffle; setOptions(); toast(shuffle ? '셔플 켜짐' : '셔플 꺼짐'); });
    el('repeat').addEventListener('click', () => {
      repeat = (repeat + 1) % 3; setOptions();
      toast(['반복 꺼짐', '전체 반복', '한 곡 반복'][repeat]);
    });
    el('speed').addEventListener('click', () => {
      const idx = speedSteps.findIndex(item => Math.abs(item - speed) < .02);
      speed = speedSteps[(idx + 1) % speedSteps.length]; setOptions(); toast(`${speed}배속`);
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
      speed = Number(prefs.speed) || 1; currentId = prefs.lastId || '';
      nativeLibrary(parseJson(window.Android.getLibrary(), []));
      window.Android.requestState();
      // Poll only while the native WebView is visible; media playback runs independently.
      setInterval(() => { if (document.visibilityState === 'visible') window.Android.requestState(); }, 850);
    } else {
      const prefs = parseJson(storage.get('pulse-options') || '{}', {});
      shuffle = !!prefs.shuffle; repeat = Number(prefs.repeat) || 0;
      speed = Number(prefs.speed) || 1;
      renderAll();
    }
  }
  document.addEventListener('DOMContentLoaded', init);
})();
