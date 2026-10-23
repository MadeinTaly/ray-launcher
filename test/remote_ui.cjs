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
        window.mockState = {active:true,globalInput:true,vpn:{active:false,known:true,available:true,package:'com.example.vpnclient'},theme:{color:'#123456',style:'metal'},device:'Test TV',apps:[{name:'Impostazioni',package:'test.settings'}],audio:{volume:{level:5,max:10}},filter:{bright:100}};
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
      await page.locator('[data-tab="settings"]').click();
      assert.equal(await page.locator('#themeSettings').isDisabled(),false);
      assert.equal(await page.locator('#themeColor').inputValue(),'#123456');
      assert.equal(await page.locator('#themeStyle').inputValue(),'metal');
      await page.locator('#themeColor').fill('#abcdef');
      await page.locator('#themeColor').dispatchEvent('change');
      assert(await page.evaluate(()=>window.sent.some(m=>m.type==='theme.set'&&m.payload.color==='#abcdef'&&m.payload.style==='metal')));
      await page.locator('#themeStyle').selectOption('glow');
      assert(await page.evaluate(()=>window.sent.some(m=>m.type==='theme.set'&&m.payload.color==='#abcdef'&&m.payload.style==='glow')));
      for(const style of ['plain','flood','horror']){await page.locator('#themeStyle').selectOption(style);assert(await page.evaluate(style=>window.sent.some(m=>m.type==='theme.set'&&m.payload.style===style),style))}
      await page.locator('[data-theme-color="#30d890"]').click();
      assert(await page.evaluate(()=>window.sent.some(m=>m.type==='theme.set'&&m.payload.color==='#30d890')));
      if(width===390) await page.screenshot({path:path.join(__dirname,'../docs/RAY_THEME_SETTINGS.png'),fullPage:true});
      assert.equal(await page.locator('#vpnStatus').textContent(),'La TV non usa la VPN');
      await page.locator('#vpnOpen').click();
      assert(await page.evaluate(()=>window.sent.some(m=>m.type==='app.launch'&&m.payload.package==='com.example.vpnclient')));
      await page.evaluate(()=>window.socket.onmessage({data:JSON.stringify({type:'state.snapshot',payload:{...window.mockState,vpn:{...window.mockState.vpn,active:true}}})}));
      assert.equal(await page.locator('#vpnStatus').textContent(),'La TV usa la VPN');
      await page.locator('#themeColor').focus();
      await page.keyboard.press('ArrowRight');
      assert.equal(await page.evaluate(()=>window.sent.filter(m=>m.type==='remote.key'&&m.payload.key==='DPAD_RIGHT').length),0);
      await page.locator('[data-tab="remote"]').click();
      await page.evaluate(()=>{window.RayNativeMotionEnabled(true,true);window.RayNativeMotion(.2,.3);window.RayNativeSelect();window.RayNativeRecenter();});
      const messages=await page.evaluate(()=>window.sent);
      assert(messages.some(m=>m.type==='remote.motion'&&m.payload.x===.2&&m.payload.y===.3));
      assert(messages.some(m=>m.type==='remote.key'&&m.payload.key==='OK'));
      assert(messages.some(m=>m.type==='remote.pointer'&&m.payload.x===.5&&m.payload.y===.5));
      await page.locator('[data-volume="1"]').click();
      await page.locator('[data-media="MEDIA_PLAY_PAUSE"]').click();
      assert(await page.evaluate(()=>window.sent.some(m=>m.type==='audio.set'&&m.payload.volume===6)));
      assert(await page.evaluate(()=>window.sent.some(m=>m.type==='remote.key'&&m.payload.key==='MEDIA_PLAY_PAUSE')));
      await page.evaluate(()=>{window.socket.onmessage({data:JSON.stringify({type:'state.snapshot',payload:{...window.mockState,active:false}})});});
      assert.equal(await page.locator('[data-media="MEDIA_PLAY_PAUSE"]').isDisabled(),false);
      await page.locator('[data-tab="apps"]').click();
      await page.locator('#appsList button').click();
      assert(await page.evaluate(()=>window.sent.some(m=>m.type==='app.launch'&&m.payload.package==='test.settings')));
      await page.locator('[data-tab="remote"]').click();
      await page.evaluate(()=>window.RayNativeMotionEnabled(false,true));
      assert(await page.evaluate(()=>window.sent.some(m=>m.type==='remote.pointer'&&m.payload.visible===false)));
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
      const ok = await page.locator('[data-c="ok"]').boundingBox();
      const nav = await page.locator('.nav').boundingBox();
      assert(ok.width>=100 && ok.y+ok.height<nav.y,'Primary OK target must be large and unobscured');
      assert.deepEqual(errors,[]);
      if(width===390) await page.screenshot({path:path.join(__dirname,'../docs/RAY_COMPANION.png'),fullPage:true});
      await context.close();
    }
    console.log('PASS: 360/390/412px, large OK, volume/media/apps/theme, native select/recenter, motion stop, global capability');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exit(1)});
