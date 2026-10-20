(() => {
  'use strict';

  const native = typeof window.Android !== 'undefined' && typeof window.Android.getLibrary === 'function';
  const el = id => document.getElementById(id);
  const browserAudio = el('browserAudio');
  const browserVideo = el('browserVideo');
  const playlist = [];
  const speedSteps = [0.75, 1, 1.25, 1.5, 2];
  let currentId = '';
  let activeFilter = 'all';
  let search = '';
  let shuffle = false;
  let repeat = 0;
  let speed = 1;
  let playing = false;
  let preparing = false;
  let isVideoOverlay = false;
  let position = 0;
  let duration = 0;
  let dragging = false;
  let activeBrowserElement = null;
  let activeBrowserUrl = '';
  let activeToastTimeout;
  let lastError = '';

  const safeStore = {
    get(key) { try { return localStorage.getItem(key); } catch (_) { return null; } },
    set(key, value) { try { localStorage.setItem(key, value); } catch (_) { /* sandboxed preview */ } }
  };
  const safeJson = (value, fallback) => {
    try { return JSON.parse(value); } catch (error) { return fallback; }
  };
  const formatTime = value => {
    const total = Math.max(0, Math.floor((Number(value) || 0) / 1000));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = String(total % 60).padStart(2, '0');
    return h ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
  };
  const icon = (name, cls = 'ico') => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', cls);
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `#i-${name}`);
    svg.appendChild(use);
    return svg;
  };
  const getTrack = id => playlist.find(item => item.id === id);
  const current = () => getTrack(currentId);
  const allOfType = type => playlist.filter(item => item.type === type);

  function toast(message) {
    const target = el('toast');
    target.textContent = message;
    target.classList.add('show');
    clearTimeout(activeToastTimeout);
    activeToastTimeout = setTimeout(() => target.classList.remove('show'), 2600);
  }

  function importFiles() {
    if (native) window.Android.importFiles();
    else el('fileInput').click();
  }

  function renderCounts() {
    const a = playlist.filter(item => item.type === 'audio').length;
    const v = playlist.filter(item => item.type === 'video').length;
    el('mediaCount').textContent = playlist.length;
    el('allCount').textContent = playlist.length;
    el('musicCount').textContent = a;
    el('videoCount').textContent = v;
  }

  function toggleFavorite(id) {
    const item = getTrack(id);
    if (!item) return;
    if (native) window.Android.toggleFavorite(id);
    else {
      item.favorite = !item.favorite;
      renderLibrary();
      renderNow();
    }
  }

  function removeFile(id) {
    const item = getTrack(id);
    if (!item || !window.confirm(`'${item.name}' 파일을 목록에서 제거할까요?\n원본 파일은 삭제하지 않습니다.`)) return;
    if (native) window.Android.remove(id);
    else {
      if (id === currentId) {
        stopBrowser();
        currentId = '';
        position = 0; duration = 0; playing = false;
      }
      const index = playlist.findIndex(row => row.id === id);
      if (index >= 0) {
        const [removed] = playlist.splice(index, 1);
        if (removed.url) URL.revokeObjectURL(removed.url);
      }
      renderAll();
    }
    toast('목록에서 제거했습니다. 원본 파일은 보존됩니다.');
  }

  function createTrackRow(item) {
    const row = document.createElement('article');
    row.className = 'track-row' + (currentId === item.id ? ' current' : '');
    const visual = document.createElement('div');
    visual.className = `media-tile ${item.type}`;
    visual.appendChild(icon(item.type === 'video' ? 'video' : 'music'));
    row.appendChild(visual);

    const content = document.createElement('button');
    content.type = 'button';
    content.className = 'row-body';
    content.title = item.fileName || item.name;
    content.setAttribute('aria-label', `${item.name} 재생`);
    content.onclick = () => playItem(item.id);
    const title = document.createElement('span');
    title.className = 'row-title';
    title.textContent = item.name || '이름 없는 파일';
    const meta = document.createElement('span');
    meta.className = 'row-meta';
    const badge = document.createElement('span');
    badge.className = 'kind-tag' + (item.type === 'video' ? ' video' : '');
    badge.textContent = item.type === 'video' ? 'VIDEO' : 'AUDIO';
    meta.appendChild(badge);
    const artist = document.createElement('span');
    artist.textContent = `${item.artist || '내 파일'} · ${item.duration ? formatTime(item.duration) : '—:—'}`;
    meta.appendChild(artist);
    content.append(title, meta);
    row.appendChild(content);

    if (currentId === item.id && playing) {
      const bars = document.createElement('span');
      bars.className = 'current-bars animate';
      bars.innerHTML = '<i></i><i></i><i></i>';
      bars.setAttribute('aria-label', '재생 중');
      row.appendChild(bars);
    }
    const favorite = document.createElement('button');
    favorite.type = 'button';
    favorite.className = 'row-heart' + (item.favorite ? ' active' : '');
    favorite.title = item.favorite ? '즐겨찾기 해제' : '즐겨찾기 추가';
    favorite.setAttribute('aria-label', favorite.title);
    favorite.appendChild(icon('heart'));
    favorite.onclick = () => toggleFavorite(item.id);
    row.appendChild(favorite);

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'row-remove';
    remove.title = '목록에서 제거';
    remove.setAttribute('aria-label', `${item.name} 목록에서 제거`);
    remove.appendChild(icon('trash'));
    remove.onclick = () => removeFile(item.id);
    row.appendChild(remove);
    return row;
  }

  function renderEmpty() {
    const wrapper = document.createElement('div');
    wrapper.className = 'empty-state';
    const illustration = document.createElement('div');
    illustration.className = 'empty-illustration';
    illustration.appendChild(icon(playlist.length ? 'search' : 'folder'));
    const heading = document.createElement('h3');
    heading.textContent = playlist.length ? '검색 결과가 없어요' : '아직 추가된 파일이 없어요';
    const p = document.createElement('p');
    if (playlist.length) p.textContent = '다른 검색어나 카테고리를 선택해 보세요.';
    else p.innerHTML = '휴대폰의 음악과 동영상을 추가하면<br>나만의 플레이리스트가 만들어져요.';
    wrapper.append(illustration, heading, p);
    if (!playlist.length) {
      const action = document.createElement('button');
      action.type = 'button';
      action.textContent = '+ 첫 파일 추가하기';
      action.onclick = importFiles;
      wrapper.appendChild(action);
    }
    return wrapper;
  }

  function renderLibrary() {
    renderCounts();
    const list = el('trackList');
    list.replaceChildren();
    const phrase = search.trim().toLocaleLowerCase('ko');
    const selected = playlist.filter(item => {
      if (activeFilter === 'favorite' && !item.favorite) return false;
      if (activeFilter === 'audio' && item.type !== 'audio') return false;
      if (activeFilter === 'video' && item.type !== 'video') return false;
      return !phrase || `${item.name} ${item.artist || ''} ${item.fileName || ''}`.toLocaleLowerCase('ko').includes(phrase);
    });
    if (phrase) {
      el('searchCount').hidden = false;
      el('searchCount').textContent = selected.length + '개';
    } else el('searchCount').hidden = true;
    if (!selected.length) list.appendChild(renderEmpty());
    else {
      const fragment = document.createDocumentFragment();
      for (const item of selected) fragment.appendChild(createTrackRow(item));
      list.appendChild(fragment);
    }
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
    const spinning = !!(item && item.type === 'audio' && playing);
    el('vinylWrap').classList.toggle('spinning', spinning);
    el('miniEqualizer').classList.toggle('active', playing);
    const sticker = document.querySelector('.vinyl-sticker use');
    sticker.setAttribute('href', item?.type === 'video' ? '#i-video' : '#i-music');
    el('visualCaption').textContent = item?.type === 'video' ? 'VIDEO COLLECTION' : 'YOUR MUSIC, YOUR MOMENT';
    el('footerHint').textContent = item?.type === 'video' ?
      (native ? '선택하면 전체 화면으로 재생' : '플레이어에서 영상 재생') :
      (native ? '화면을 꺼도 음악은 계속' : '내 기기에서 직접 재생');

    el('togglePlayback').setAttribute('aria-label', playing ? '일시정지' : '재생');
    document.querySelector('#playIcon use').setAttribute('href', playing ? '#i-pause' : '#i-play');
    el('shuffle').classList.toggle('on', shuffle);
    el('shuffle').setAttribute('aria-label', shuffle ? '셔플 끄기' : '셔플 켜기');
    el('repeat').classList.toggle('on', repeat !== 0);
    el('repeatOne').hidden = repeat !== 2;
    el('repeat').setAttribute('aria-label', ['반복 꺼짐', '전체 반복', '한 곡 반복'][repeat] || '반복');
    el('speed').firstChild.textContent = speed.toFixed(2).replace(/0$/, '').replace(/\.0$/, '.0') + '× ';
    el('visual').classList.toggle('has-browser-video', !native && item?.type === 'video');
    if (native || item?.type !== 'video') browserVideo.hidden = true;
    renderProgress();
  }

  function renderProgress() {
    const item = current();
    const full = duration || item?.duration || 0;
    const pct = full > 0 ? Math.min(100, Math.max(0, position / full * 100)) : 0;
    const slider = el('progress');
    if (!dragging) {
      slider.value = Math.round(pct * 10);
      slider.style.setProperty('--percent', `${pct}%`);
      el('elapsed').textContent = formatTime(position);
    }
    el('duration').textContent = formatTime(full);
    slider.disabled = !item || !(full > 0);
  }

  function renderAll() { renderCounts(); renderNow(); renderLibrary(); }

  function setNativeOptions() {
    if (native) window.Android.setOptions(shuffle, repeat, speed);
    else {
      safeStore.set('pulse-options', JSON.stringify({shuffle, repeat, speed}));
      if (activeBrowserElement) activeBrowserElement.playbackRate = speed;
    }
    renderNow();
  }

  function stopBrowser() {
    browserAudio.pause();
    browserVideo.pause();
    browserAudio.removeAttribute('src');
    browserVideo.removeAttribute('src');
    browserVideo.hidden = true;
    browserAudio.load();
    browserVideo.load();
    activeBrowserElement = null;
    activeBrowserUrl = '';
  }

  function playItem(id) {
    const item = getTrack(id);
    if (!item) { toast('파일을 찾을 수 없어요. 다시 추가해 주세요.'); return; }
    const same = currentId === id;
    currentId = id;
    duration = item.duration || 0;
    if (!same) position = 0;
    if (native) {
      playing = false;
      preparing = true;
      if (same) window.Android.toggle(id);
      else window.Android.play(id);
      renderAll();
      return;
    }
    if (same && activeBrowserElement) {
      if (activeBrowserElement.paused) {
        activeBrowserElement.play().catch(() => toast('이 파일을 재생할 수 없습니다.'));
      } else activeBrowserElement.pause();
      return;
    }
    stopBrowser();
    const element = item.type === 'video' ? browserVideo : browserAudio;
    activeBrowserElement = element;
    element.src = item.url;
    element.playbackRate = speed;
    if (item.type === 'video') {
      browserVideo.hidden = false;
      browserVideo.controls = true;
    }
    playing = false;
    renderAll();
    element.play().catch(() => toast('지원되지 않는 형식이거나 코덱을 재생할 수 없어요.'));
  }

  function togglePlayback() {
    const item = current();
    if (!item) {
      if (!playlist.length) importFiles();
      else playItem(playlist[0].id);
      return;
    }
    if (native) {
      if (item.type === 'video') window.Android.play(item.id);
      else window.Android.toggle(item.id);
      return;
    }
    if (activeBrowserElement) {
      if (activeBrowserElement.paused) activeBrowserElement.play().catch(() => toast('재생할 수 없습니다.'));
      else activeBrowserElement.pause();
    } else playItem(item.id);
  }

  function nextPrevious(direction, auto = false) {
    const item = current();
    if (!item) { if (playlist.length) playItem(playlist[0].id); return; }
    if (native && item.type === 'audio') {
      if (direction > 0) window.Android.next();
      else window.Android.previous();
      return;
    }
    const candidates = allOfType(item.type);
    if (!candidates.length) return;
    const currentIndex = Math.max(0, candidates.findIndex(row => row.id === item.id));
    let newIndex = currentIndex + direction;
    if (shuffle && candidates.length > 1) {
      newIndex = Math.floor(Math.random() * (candidates.length - 1));
      if (newIndex >= currentIndex) newIndex++;
    } else if (newIndex >= candidates.length) {
      if (auto && repeat === 0) {
        playing = false;
        if (activeBrowserElement) activeBrowserElement.pause();
        renderNow();
        return;
      }
      newIndex = 0;
    } else if (newIndex < 0) newIndex = candidates.length - 1;
    playItem(candidates[newIndex].id);
  }

  function handleLocalFiles(files) {
    let count = 0;
    for (const file of Array.from(files)) {
      const suffix = file.name.toLowerCase().split('.').pop();
      const video = file.type.startsWith('video/') || /^(mp4|mkv|webm|mov|m4v|3gp|avi)$/.test(suffix);
      const audio = file.type.startsWith('audio/') || /^(mp3|m4a|aac|wav|flac|ogg|opus|wma|amr)$/.test(suffix);
      if (!video && !audio) continue;
      if (playlist.some(row => row.fileName === file.name && row.size === file.size)) continue;
      const url = URL.createObjectURL(file);
      playlist.push({
        id: `browser:${Date.now()}:${Math.random().toString(36).slice(2)}`,
        name: file.name.replace(/\.[^/.]+$/, ''),
        fileName: file.name,
        type: video ? 'video' : 'audio',
        duration: 0,
        size: file.size,
        favorite: false,
        addedAt: Date.now(),
        artist: video ? '내 영상' : '내 음악',
        url
      });
      count++;
    }
    el('fileInput').value = '';
    renderAll();
    toast(count ? `${count}개 파일을 추가했어요.` : '추가할 미디어 파일이 없습니다.');
  }

  function nativeLibrary(items) {
    if (!Array.isArray(items)) return;
    playlist.splice(0, playlist.length, ...items.filter(item => item && typeof item.id === 'string'));
    if (currentId && !getTrack(currentId)) {
      currentId = '';
      playing = false;
      position = duration = 0;
    }
    if (!currentId && playlist.length) {
      const prefs = safeJson(window.Android.getPreferences(), {});
      if (getTrack(prefs.lastId)) currentId = prefs.lastId;
    }
    renderAll();
  }

  window.onNativeLibrary = nativeLibrary;
  window.onNativeState = incoming => {
    if (!incoming || typeof incoming !== 'object') return;
    const changed = incoming.id && incoming.id !== currentId && !!getTrack(incoming.id);
    if (changed) currentId = incoming.id;
    const wasPlaying = playing;
    playing = !!incoming.playing;
    preparing = !!incoming.preparing;
    isVideoOverlay = !!incoming.video;
    position = Number(incoming.position) || 0;
    duration = Number(incoming.duration) || current()?.duration || 0;
    shuffle = !!incoming.shuffle;
    repeat = Number(incoming.repeat) || 0;
    speed = Number(incoming.speed) || 1;
    if (incoming.error && incoming.error !== lastError) {
      lastError = incoming.error;
      toast(incoming.error);
    }
    if (changed || wasPlaying !== playing) renderLibrary();
    renderNow();
  };

  function wireBrowserEngine(element) {
    element.addEventListener('loadedmetadata', () => {
      if (element !== activeBrowserElement) return;
      duration = Number.isFinite(element.duration) ? element.duration * 1000 : 0;
      const item = current();
      if (item && duration) item.duration = duration;
      renderProgress();
      renderLibrary();
    });
    element.addEventListener('timeupdate', () => {
      if (element !== activeBrowserElement) return;
      position = element.currentTime * 1000;
      renderProgress();
    });
    element.addEventListener('play', () => {
      if (element !== activeBrowserElement) return;
      playing = true;
      renderAll();
    });
    element.addEventListener('pause', () => {
      if (element !== activeBrowserElement) return;
      playing = false;
      renderAll();
    });
    element.addEventListener('ended', () => {
      if (element !== activeBrowserElement) return;
      playing = false;
      if (repeat === 2) { element.currentTime = 0; element.play().catch(() => {}); }
      else nextPrevious(1, true);
    });
    element.addEventListener('error', () => {
      if (element !== activeBrowserElement) return;
      toast('이 형식은 현재 브라우저에서 재생되지 않아요.');
    });
  }

  function init() {
    el('topAdd').addEventListener('click', importFiles);
    el('libraryAdd').addEventListener('click', importFiles);
    el('fileInput').addEventListener('change', e => handleLocalFiles(e.target.files));
    el('search').addEventListener('input', e => { search = e.target.value; renderLibrary(); });
    document.querySelectorAll('.filter').forEach(button => {
      button.addEventListener('click', () => {
        activeFilter = button.dataset.filter;
        document.querySelectorAll('.filter').forEach(tab => {
          const selected = tab === button;
          tab.classList.toggle('active', selected);
          tab.setAttribute('aria-selected', String(selected));
        });
        renderLibrary();
      });
    });
    el('favoriteCurrent').addEventListener('click', () => { if (current()) toggleFavorite(currentId); });
    el('togglePlayback').addEventListener('click', togglePlayback);
    el('previous').addEventListener('click', () => nextPrevious(-1));
    el('next').addEventListener('click', () => nextPrevious(1));
    el('shuffle').addEventListener('click', () => { shuffle = !shuffle; setNativeOptions(); toast(shuffle ? '셔플 재생 켜짐' : '셔플 재생 꺼짐'); });
    el('repeat').addEventListener('click', () => {
      repeat = (repeat + 1) % 3;
      setNativeOptions();
      toast(['반복 재생 꺼짐', '전체 반복', '한 곡 반복'][repeat]);
    });
    el('speed').addEventListener('click', () => {
      const index = speedSteps.findIndex(value => Math.abs(value - speed) < 0.02);
      speed = speedSteps[(index + 1) % speedSteps.length];
      setNativeOptions();
      toast(`${speed}배속 재생`);
    });
    el('progress').addEventListener('pointerdown', () => { dragging = true; });
    el('progress').addEventListener('input', e => {
      dragging = true;
      const full = duration || current()?.duration || 0;
      const percent = Number(e.target.value) / 1000;
      el('elapsed').textContent = formatTime(full * percent);
      e.target.style.setProperty('--percent', percent * 100 + '%');
    });
    const finishSeek = e => {
      if (!dragging) return;
      dragging = false;
      const full = duration || current()?.duration || 0;
      position = Math.round(full * Number(e.target.value) / 1000);
      if (native) {
        if (current()?.type === 'audio') window.Android.seekTo(position);
      } else if (activeBrowserElement && Number.isFinite(activeBrowserElement.duration)) {
        activeBrowserElement.currentTime = position / 1000;
      }
      renderProgress();
    };
    el('progress').addEventListener('change', finishSeek);
    el('progress').addEventListener('pointerup', finishSeek);
    wireBrowserEngine(browserAudio);
    wireBrowserEngine(browserVideo);
    if (native) {
      const pref = safeJson(window.Android.getPreferences(), {});
      shuffle = !!pref.shuffle;
      repeat = Number(pref.repeat) || 0;
      speed = Number(pref.speed) || 1;
      currentId = pref.lastId || '';
      nativeLibrary(safeJson(window.Android.getLibrary(), []));
      window.Android.requestState();
      setInterval(() => {
        if (document.visibilityState !== 'hidden') window.Android.requestState();
      }, 850);
    } else {
      const pref = safeJson(safeStore.get('pulse-options') || '{}', {});
      shuffle = !!pref.shuffle;
      repeat = Number(pref.repeat) || 0;
      speed = Number(pref.speed) || 1;
      renderAll();
    }
  }

  document.addEventListener('DOMContentLoaded', init);
})();
