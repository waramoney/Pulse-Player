"""UI integration smoke test using a local Chromium instance and stub native bridge.
YouTube responses are mocked; this never claims to test actual YouTube playback.
"""
from pathlib import Path
from playwright.sync_api import sync_playwright

root=Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets'
lib=[
 {'id':'content://media/external/audio/media/42','name':'테스트 음악','fileName':'test.mp3','type':'audio','source':'device','artist':'가수','duration':130000,'favorite':False,'addedAt':3},
 {'id':'yt:dQw4w9WgXcQ','name':'테스트 유튜브 영상','videoId':'dQw4w9WgXcQ','type':'youtube','source':'youtube','artist':'YouTube','duration':0,'favorite':False,'addedAt':2,'thumb':'https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg'}
]
setup_js=r'''(() => {
 const saved = new Map();
 Object.defineProperty(window, 'localStorage', {configurable:true, value:{getItem:k=>saved.has(k)?saved.get(k):null,setItem:(k,v)=>saved.set(k,String(v)),removeItem:k=>saved.delete(k)}});
 const audio = JSON.parse(`{{LIBRARY}}`);
 window.calls = [];
 window.Android={
  getLibrary:()=>JSON.stringify(audio),getCollections:()=>localStorage.getItem('test-collections')||'{"lists":[],"lyrics":{}}',
  saveCollections:x=>localStorage.setItem('test-collections',x),
  getPreferences:()=>JSON.stringify({version:'1.3.7',lastId:'',shuffle:false,repeat:0,speed:1,lastScan:Date.now()}),
  hasMediaPermission:()=>true,scanDevice:()=>{},requestState:()=>{},pauseAll:()=>window.calls.push(['pauseAll']),
  play:id=>window.calls.push(['play',id]),toggle:id=>window.calls.push(['toggle',id]),
  setOptions:()=>{},addYouTube:(id,title,author)=>{
   const newOne={id:'yt:'+id,videoId:id,type:'youtube',source:'youtube',name:title||'YouTube 영상',artist:author||'YouTube',duration:0,favorite:false,addedAt:Date.now()};
   audio.push(newOne);window.calls.push(['addYouTube',id]);return JSON.stringify(newOne);
  },
  toggleFavorite:id=>{
   const i=audio.find(x=>x.id===id);if(i){i.favorite=!i.favorite;window.calls.push(['favorite',id,i.favorite]);}
  },
  deleteOriginal:id=>window.calls.push(['deleteOriginal',id]),
  renameDisplayName:(id,name)=>{const i=audio.find(x=>x.id===id);if(!i)return false;
   i.name=name; window.calls.push(['renameDisplayName',id,name]); return true;},
  renameOriginalFile:id=>window.calls.push(['renameOriginalFile',id]),
  chooseUpdateFolder:()=>window.calls.push(['chooseUpdateFolder']),
  checkForUpdates:()=>window.calls.push(['checkForUpdates']),
  chooseUpdateApk:()=>window.calls.push(['chooseUpdateApk']),
  openBrowser:(u,t)=>window.calls.push(['openBrowser',u,t]),
  openChrome:u=>window.calls.push(['chrome',u]),importFiles:()=>{},
  downloadDirectMedia:()=>{},getClipboard:()=>'',
  enterMiniPlayer:()=>window.calls.push(['pip']),
  setYouTubeOverlayActive:active=>window.calls.push(['ytActive',active])
 };
 window.testLibrary=audio;
})();'''.replace('{{LIBRARY}}',__import__('json').dumps(lib,ensure_ascii=False).replace('`','\\`'))

with sync_playwright() as playwright:
 browser=playwright.chromium.launch(executable_path='/usr/bin/chromium',args=['--no-sandbox','--disable-dev-shm-usage'])
 page=browser.new_page(viewport={'width':390,'height':844},device_scale_factor=2)
 page.set_default_timeout(4000)
 errors=[]
 page.on('pageerror',lambda ex:errors.append(str(ex)))
 # No network navigation in test sandbox; inject HTML/CSS/JS directly into about:blank.
 page.route('https://www.youtube.com/**',lambda route:route.fulfill(status=200,body='<html><body>mock: official youtube embed</body></html>',headers={'content-type':'text/html'}))
 page.route('https://i.ytimg.com/**',lambda route:route.abort())
 markup=(assets/'index.html').read_text(encoding='utf-8')
 markup=markup.replace('<script src="app.js"></script>','').replace('<link rel="stylesheet" href="app.css">','')
 page.set_content(markup,wait_until='domcontentloaded')
 page.add_style_tag(content=(assets/'app.css').read_text(encoding='utf-8'))
 page.evaluate(setup_js)
 page.evaluate((assets/'app.js').read_text(encoding='utf-8'))
 page.evaluate("document.dispatchEvent(new Event('DOMContentLoaded'))")
 page.get_by_role('button',name='테스트 음악 재생').click()
 assert ['play','content://media/external/audio/media/42'] in page.evaluate('window.calls'), 'library playback must use play not stale toggle'
 # A title edit must stay in the local metadata; it must not rename/delete the source file.
 page.get_by_role('button',name='라이브러리',exact=True).last.click()
 page.get_by_role('button',name='테스트 음악 더보기').click()
 page.get_by_role('button',name='목록에서 보이는 이름 변경').click()
 page.locator('#dialogName').fill('내가 바꾼 노래 이름')
 page.locator('#dialogConfirm').click()
 assert ['renameDisplayName','content://media/external/audio/media/42','내가 바꾼 노래 이름'] in page.evaluate('window.calls')
 assert page.get_by_role('button',name='내가 바꾼 노래 이름 재생').count()==1
 # Rename it back for the original deletion regression.
 page.get_by_role('button',name='내가 바꾼 노래 이름 더보기').click()
 page.get_by_role('button',name='목록에서 보이는 이름 변경').click()
 page.locator('#dialogName').fill('테스트 음악')
 page.locator('#dialogConfirm').click()
 page.get_by_role('button',name='테스트 음악 더보기').click()
 page.get_by_role('button',name='휴대폰 원본 파일명 변경').click()
 assert ['renameOriginalFile','content://media/external/audio/media/42'] in page.evaluate('window.calls')

 # Actual file delete is separate from hide and does NOT drop the library until OS approval.
 page.get_by_role('button',name='라이브러리',exact=True).last.click()
 page.get_by_role('button',name='테스트 음악 더보기').click()
 assert page.get_by_role('button',name='라이브러리에서 숨기기 (파일은 그대로)').is_visible()
 page.get_by_role('button',name='휴대폰 원본 파일 삭제').click()
 assert ['deleteOriginal','content://media/external/audio/media/42'] in page.evaluate('window.calls')
 assert page.get_by_role('button',name='테스트 음악 재생').count() == 1, 'file removed before native approval'
 page.evaluate("window.onNativeFileDeleteFailed('파일 삭제를 취소했습니다.')")
 assert page.get_by_role('button',name='테스트 음악 재생').count() == 1, 'cancel removed file'
 # Native code calls deletion result only after OS confirms and file removal succeeds.
 page.evaluate("""window.testLibrary.splice(window.testLibrary.findIndex(x=>x.name==='테스트 음악'),1);
     window.onNativeFileDeleted('content://media/external/audio/media/42');""")
 assert page.get_by_role('button',name='테스트 음악 재생').count() == 0, 'file remained after OS delete success'
 # Video play within the app, not Chrome; toolbar heart and playlist.
 page.get_by_role('button',name='YouTube',exact=True).last.click()
 page.get_by_role('button',name='테스트 유튜브 영상 재생').click()
 assert page.locator('#ytInlinePlayer').is_visible(), 'inline YouTube viewer did not open'
 src=page.locator('#ytInlineFrame').get_attribute('src')
 assert '/embed/dQw4w9WgXcQ?' in src and 'origin=https%3A%2F%2Fappassets.androidplatform.net' in src
 assert not page.locator('#miniPlayer').is_visible(), 'floating play dock visible on youtube'
 page.locator('#ytInlineFavorite').click()
 assert page.locator('#ytInlineFavorite').inner_text() == '♥', 'inline favorite toggle failed'
 assert ['favorite','yt:dQw4w9WgXcQ',True] in page.evaluate('window.calls')
 page.locator('#ytInlineSave').click()
 assert '저장됨' in page.locator('#ytInlineSave').inner_text(), 'saving YouTube link must report saved item'
 page.locator('#ytInlinePip').click()
 assert ['pip'] in page.evaluate('window.calls'), 'PiP button must request Android small window'
 page.evaluate('window.onPipChanged(true)')
 assert page.locator('body').evaluate("e=>e.classList.contains('yt-pip')"), 'PiP styling not applied'
 page.evaluate('window.onPipChanged(false)')
 page.locator('#ytInlinePlaylist').click()
 page.locator('#dialogName').fill('내 유튜브 재생목록')
 page.locator('#dialogConfirm').click()
 assert page.locator('#dialogBackdrop').is_hidden(), 'playlist name dialog did not close'
 saved=page.evaluate('JSON.parse(localStorage.getItem("test-collections"))')
 assert len(saved['lists'])==1 and saved['lists'][0]['tracks']==['yt:dQw4w9WgXcQ']
 page.locator('#ytInlineClose').click()
 assert page.locator('#ytInlinePlayer').is_hidden() and not page.locator('#ytInlineFrame').get_attribute('src'), 'close should unload video iframe'
 # In-app YouTube browsing and explicit in-app fallback are separate from Chrome login.
 page.get_by_role('button',name='테스트 유튜브 영상 재생').evaluate('(el) => el.click()')
 page.locator('#ytInlineAlternate').click()
 assert any(x[0]=='openBrowser' and x[2]=='youtube-watch' and 'watch?v=dQw4w9WgXcQ' in x[1] for x in page.evaluate('window.calls')), 'alternate playback must stay in app'
 assert not page.locator('#ytInlinePlayer').is_visible()
 page.locator('#ytQuery').fill('좋아하는 가수')
 page.locator('#ytForm button[type="submit"]').click()
 assert any(x[0]=='openBrowser' and 'search_query=' in x[1] for x in page.evaluate('window.calls')), 'search must browse in-app without Data API'
 page.get_by_role('button',name='테스트 유튜브 영상 재생').click()
 assert not page.locator('#ytInlineChrome').count(), 'Chrome playback button must be absent'
 assert not any(x[0]=='chrome' for x in page.evaluate('window.calls')), 'YouTube playback must never auto-launch Chrome'
 page.locator('#ytInlineClose').click()
 page.get_by_role('button', name='Chrome에서 열기 / 로그인').click()
 assert any(x[0]=='chrome' and 'youtube.com' in x[1] for x in page.evaluate('window.calls')), 'Only the explicit Chrome login launches Chrome'
 assert page.locator('#ytInlinePlayer').is_hidden(), 'Chrome login closes embedded video'
 page.locator('#topSettings').click()
 page.get_by_role('button',name='자동 업데이트 폴더 지정 (한 번만)').click()
 assert ['chooseUpdateFolder'] in page.evaluate('window.calls')
 page.locator('#topSettings').click()
 page.get_by_role('button',name='새 버전 자동 확인 · 설치').click()
 assert ['checkForUpdates'] in page.evaluate('window.calls')
 page.locator('#topSettings').click()
 page.get_by_role('button',name='업데이트 APK 직접 선택').click()
 assert ['chooseUpdateApk'] in page.evaluate('window.calls')

 assert not errors, 'Browser JavaScript errors: '+str(errors)
 print('PASS browser: local audio, in-app YouTube embed and watch, PiP, save/favorite, playlist, Chrome opt-in, unload')
 browser.close()
