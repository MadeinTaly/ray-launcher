// Offline UI/bridge check: RAY_PLAYWRIGHT_PATH=/path/to/playwright-core node test/remote_ui.cjs
const {chromium} = require(process.env.RAY_PLAYWRIGHT_PATH || 'playwright-core');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../app/assets/ray.html'), 'utf8');
(async () => {
  const browser = await chromium.launch({executablePath:process.env.RAY_CHROME || '/opt/google/chrome/chrome', args:['--no-sandbox']});
  try {
    for (const width of [360,390,412]) {
      const context = await browser.newContext({viewport:{width,height:844},locale:'it-IT',userAgent:'Mozilla/5.0 RayApp/1'});
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.addInitScript(() => {
        window.sent = [];
        window.mockState = {active:true,globalInput:true,device:'Test TV',foregroundPackage:'dev.raylauncher',media:{access:false},
          home:[{k:'watch',index:0,title:'Film',name:'Cine',progress:.3,minutes:10},{k:'app',package:'test.settings',name:'Impostazioni'}],
          apps:[{name:'Impostazioni',package:'test.settings'}],
          vpn:{active:false,known:true,apps:[{pkg:'com.example.vpnclient',name:'VPN'}]},
          theme:{skin:'cinema',remoteSkin:'match',daynight:'auto',skins:[{id:'cinema',name:{en:'Cinema',it:'Cinema'},accent:'#EDB980'}]},
          audio:{volume:{level:5,max:10}},filter:{bright:100}};
        window.WebSocket = class {
          constructor() { this.readyState=1;window.socket=this;setTimeout(()=>this.onopen(),0); }
          send(text) {
            const message=JSON.parse(text);window.sent.push(message);
            if(message.type==='auth'){
              this.onmessage({data:JSON.stringify({type:'auth.ok',protocol:1})});
              this.onmessage({data:JSON.stringify({type:'state.snapshot',payload:window.mockState})});
            }
          }
          close(){this.readyState=3;}
        };
      });
      await page.route('http://ray.test/**',route=>route.fulfill({contentType:'text/html',body:html}));
      await page.goto('http://ray.test/ray#k=test');
      await page.waitForFunction(()=>document.querySelector('#status').textContent==='Connesso');
      const sent = type => page.evaluate(t=>window.sent.filter(m=>m.type===t),type);
      const snapshot = extra => page.evaluate(x=>window.socket.onmessage({data:JSON.stringify({type:'state.snapshot',payload:{...window.mockState,...x}})}),extra);

      // three tabs, no Device tab; the removed theme controls are gone
      assert.equal(await page.locator('.nav [data-tab]').count(),3);
      for (const gone of ['#themeColor','#themeStyle','[data-theme-color]','[data-tab="device"]','[data-tab="apps"]']) assert.equal(await page.locator(gone).count(),0,gone);

      // Settings: a list, one page at a time, back returns to the list
      await page.locator('[data-tab="settings"]').click();
      assert.equal(await page.locator('#sList').isVisible(),true);
      await page.locator('[data-open="look"]').click();
      assert.equal(await page.locator('#sList').isVisible(),false);
      assert.equal(await page.locator('#lookSet').isDisabled(),false);
      await page.locator('#themeSkin').selectOption('cinema');
      assert((await sent('theme.set')).some(m=>m.payload.skin==='cinema'&&!('color' in m.payload)&&!('style' in m.payload)));
      await page.locator('#remoteSkin').selectOption('match');
      assert((await sent('theme.set')).some(m=>m.payload.remoteSkin==='match'));
      await page.locator('#dayNight').selectOption('night');
      assert((await sent('theme.set')).some(m=>m.payload.daynight==='night'));
      await page.locator('#dayNight').focus();
      await page.keyboard.press('ArrowRight');
      assert.equal((await sent('remote.key')).filter(m=>m.payload.key==='DPAD_RIGHT').length,0);
      assert.equal(await page.evaluate(()=>window.RayBack()),true);
      assert.equal(await page.locator('#sList').isVisible(),true);
      assert.equal(await page.evaluate(()=>window.RayBack()),false);
      await page.locator('[data-open="screen"]').click();
      await page.locator('[data-scene="cinema"]').click();
      assert((await sent('scene.apply')).some(m=>m.payload.scene==='cinema'));
      if (width===390) await page.screenshot({path:path.join(__dirname,'../docs/RAY_THEME_SETTINGS.png'),fullPage:true});
      await page.evaluate(()=>window.RayBack());
      await page.locator('[data-open="network"]').click();
      assert.equal(await page.locator('#vpnStatus').textContent(),'La TV non usa la VPN');
      await page.locator('#vpnOpen').click();
      assert((await sent('vpn.open')).some(m=>m.payload.pkg==='com.example.vpnclient'));
      await snapshot({vpn:{known:true,active:true,apps:[{pkg:'com.example.vpnclient',name:'VPN'}]}});
      assert.equal(await page.locator('#vpnStatus').textContent(),'La TV usa la VPN');
      await page.evaluate(()=>window.RayBack());
      await page.locator('[data-open="weather"]').click();
      await page.locator('#wxEnable').click();
      await page.locator('#wxYes').click();
      assert((await sent('weather.consent')).some(m=>m.payload.accepted===true));
      await page.evaluate(()=>window.RayBack());
      await page.locator('[data-open="about"]').click();
      assert.equal(await page.locator('#dbg').isVisible(),false,'debug only when the state has debug');
      await page.evaluate(()=>window.RayBack());

      // Remote: native motion bridge, volume and media keys
      await page.locator('[data-tab="remote"]').click();
      await page.evaluate(()=>{window.RayNativeMotionEnabled(true,true);window.RayNativeDelta(3,4);window.RayNativeSelect();window.RayNativeRecenter();});
      const messages=await page.evaluate(()=>window.sent);
      assert(messages.some(m=>m.type==='remote.delta'&&m.payload.dx===3&&m.payload.dy===4));
      assert(messages.some(m=>m.type==='remote.key'&&m.payload.key==='OK'));
      assert(messages.some(m=>m.type==='remote.pointer'&&m.payload.x===.5&&m.payload.y===.5));
      await page.locator('[data-volume="1"]').click();
      await page.locator('[data-media="MEDIA_PLAY_PAUSE"]').click();
      assert((await sent('audio.set')).some(m=>m.payload.volume===6));
      assert((await sent('remote.key')).some(m=>m.payload.key==='MEDIA_PLAY_PAUSE'));
      await snapshot({active:false});
      assert.equal(await page.locator('[data-media="MEDIA_PLAY_PAUSE"]').isDisabled(),false);
      await page.evaluate(()=>window.RayNativeMotionEnabled(false,true));
      assert((await sent('remote.pointer')).some(m=>m.payload.visible===false));

      // On TV, TV home: the feed in the TV's order, tap launches; the full grid opens with "All apps"
      await page.locator('[data-tab="tv"]').click();
      await page.locator('#tvFeed .tile').first().click();
      assert((await sent('watch.open')).some(m=>m.payload.index===0));
      await page.locator('#tvApps .tile').first().click();
      assert((await sent('app.launch')).some(m=>m.payload.package==='test.settings'));
      assert.equal(await page.locator('#allGrid').isVisible(),false);
      await page.locator('#moreApps').click();
      await page.locator('#allGrid .tile').click();
      assert.equal((await sent('app.launch')).length,2);
      // no notification access: one quiet line, dismissed for good
      assert.equal(await page.locator('#tvNote').isVisible(),true);
      await page.locator('#tvNoteX').click();
      assert.equal(await page.locator('#tvNote').isVisible(),false);
      // an app with a session: the playing view
      await snapshot({foregroundPackage:'x.tube',media:{access:true,session:true,app:'Tube',package:'x.tube',title:'Clip',duration:60000,position:1000,playing:true,canSeek:true,searchable:true,continue:[{id:'wn:1',title:'Show',progress:.4,minutes:5}],suggestions:[{id:'pp:3',title:'Next'}]}});
      await page.locator('#tvToggle').click();
      assert((await sent('media.control')).some(m=>m.payload.action==='toggle'));
      await page.locator('#tvContL .tile').click();
      assert((await sent('media.open')).some(m=>m.payload.id==='wn:1'));
      await page.locator('#tvSuggL .tile').click();
      assert((await sent('media.open')).some(m=>m.payload.id==='pp:3'));
      await page.locator('#tvQuery').fill('cats');
      await page.locator('#tvSearch button').click();
      assert((await sent('media.search')).some(m=>m.payload.query==='cats'));
      // an app without a session: shortcuts, no transport
      await snapshot({foregroundPackage:'x.tube',media:{access:true,app:'Tube',package:'x.tube',searchable:true}});
      assert.equal(await page.locator('#tvShort').isVisible(),true);
      assert.equal(await page.locator('#tvPlay').isVisible(),false);
      // a link shared to the phone goes out as url.open once signed in
      await page.evaluate(()=>window.RayShare('https://example.org/a'));
      assert((await sent('url.open')).some(m=>m.payload.url==='https://example.org/a'));

      await page.locator('[data-tab="remote"]').click();
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
      const ok = await page.locator('[data-c="ok"]').boundingBox();
      const nav = await page.locator('.nav').boundingBox();
      assert(ok.width>=100 && ok.y+ok.height<nav.y,'Primary OK target must be large and unobscured');
      assert.deepEqual(errors,[]);
      if (width===390) await page.screenshot({path:path.join(__dirname,'../docs/RAY_COMPANION.png'),fullPage:true});
      await context.close();
    }
    console.log('PASS: 360/390/412px, three tabs, settings drill-down, On TV states, share, large OK, volume/media, native delta/select/recenter, motion stop, global capability');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exit(1)});
