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
  getPreferences:()=>JSON.stringify({version:'1.3.6',lastId:'',shuffle:false,repeat:0,speed:1,lastScan:Date.now()}),
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
  openBrowser:(u,t)=>window.calls.push(['openBrowser',u,t]),
  openChrome:u=>window.calls.push(['chrome',u]),importFiles:()=>{},
  downloadDirectMedia:()=>{},getClipboard:()=>''
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
 page.locator('#ytInlinePlaylist').click()
 page.locator('#dialogName').fill('내 유튜브 재생목록')
 page.locator('#dialogConfirm').click()
 assert page.locator('#dialogBackdrop').is_hidden(), 'playlist name dialog did not close'
 saved=page.evaluate('JSON.parse(localStorage.getItem("test-collections"))')
 assert len(saved['lists'])==1 and saved['lists'][0]['tracks']==['yt:dQw4w9WgXcQ']
 page.locator('#ytInlineClose').click()
 assert page.locator('#ytInlinePlayer').is_hidden() and not page.locator('#ytInlineFrame').get_attribute('src'), 'close should unload video iframe'
 # In-app YouTube browsing and fallback Chrome button separate.
 page.locator('#ytQuery').fill('좋아하는 가수')
 page.locator('#ytForm button[type="submit"]').click()
 assert any(x[0]=='openBrowser' and 'search_query=' in x[1] for x in page.evaluate('window.calls')), 'search must browse in-app without Data API'
 page.get_by_role('button',name='테스트 유튜브 영상 재생').click()
 page.locator('#ytInlineChrome').click()
 assert any(x[0]=='chrome' and 'watch?v=dQw4w9WgXcQ' in x[1] for x in page.evaluate('window.calls'))
 assert page.locator('#ytInlinePlayer').is_hidden(), 'Chrome fallback must stop iframe'
 assert not errors, 'Browser JavaScript errors: '+str(errors)
 print('PASS browser: audio PLAY, in-app YouTube iframe, favorite, named playlist, browser search, Chrome fallback, stop + mini-player hidden')
 browser.close()
