// Offline WebGL check for the shaders and texture upload path in RayFluidBackdrop.
const { chromium } = require(process.env.RAY_PLAYWRIGHT_PATH || 'playwright-core');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../app/src/dev/raylauncher/RayFluidBackdrop.java'), 'utf8');
function javaString(name) {
  const start = source.indexOf(`private static final String ${name} =`);
  assert(start >= 0, `missing ${name}`);
  let end = start + source.slice(start).indexOf('=') + 1, quoted = false, escaped = false, lineComment = false;
  for (; end < source.length; end++) {
    const char = source[end];
    if (lineComment) { if (char === '\n') lineComment = false; else continue; }
    if (quoted && escaped) escaped = false;
    else if (quoted && char === '\\') escaped = true;
    else if (char === '"') quoted = !quoted;
    else if (!quoted && char === '/' && source[end + 1] === '/') lineComment = true;
    else if (!quoted && char === ';') break;
  }
  const pieces = [...source.slice(start, end).matchAll(/"((?:\\.|[^"\\])*)"/g)].map(m => JSON.parse(`"${m[1]}"`));
  assert(pieces.length, `empty ${name}`);
  return pieces.join('');
}
const shaders = { vertex: javaString('VERTEX'), fragment: javaString('FRAGMENT'), fallback: javaString('FALLBACK') };
const silhouetteDiagnostic = shaders.fragment.replace(
  /gl_FragColor=vec4\(mix\(scene,material,([^)]*)\),1\.0\);\}/,
  (_, alpha) => `gl_FragColor=vec4(vec3(${alpha}),1.0);}`,
);
assert.notEqual(silhouetteDiagnostic, shaders.fragment, 'missing final shell alpha composite');
assert.ok((shaders.fragment.match(/texture2D\(/g) || []).length <= 3, 'main shader must use at most three texture fetches');
assert.match(source, /int\[\] textures = new int\[2\]/, 'renderer must keep two resident textures');
const quadSource = source.match(/quad\.put\(new float\[\]\{([^}]*)\}\)/);
assert(quadSource, 'missing interleaved quad');
const quad = quadSource[1].match(/-?\d+(?:\.\d+)?f/g).map(value => Number.parseFloat(value));
const image = name => `data:image/${name.endsWith('.png') ? 'png' : 'jpeg'};base64,${fs.readFileSync(path.join(__dirname, '../app/assets', name)).toString('base64')}`;

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.RAY_CHROME || '/opt/google/chrome/chrome', args: ['--no-sandbox', '--use-gl=angle', '--use-angle=swiftshader', '--enable-webgl'] });
  try {
    const page = await browser.newPage();
    await page.setContent(`<canvas id="c" width="1280" height="720"></canvas>`);
    const result = await page.evaluate(async ({ shaders, silhouetteDiagnostic, quad, sceneUrl, shellUrl }) => {
      const load = url => new Promise((resolve, reject) => { const im = new Image(); im.onload = () => resolve(im); im.onerror = reject; im.src = url; });
      const [sceneImage, shellImage] = await Promise.all([load(sceneUrl), load(shellUrl)]);
      const gl = document.querySelector('canvas').getContext('webgl', { preserveDrawingBuffer: true, premultipliedAlpha: false });
      if (!gl) throw Error('WebGL unavailable');
      const compile = (type, text) => { const s = gl.createShader(type); gl.shaderSource(s, text); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw Error(`${gl.getShaderInfoLog(s)} source=${text.slice(0,80)} tail=${text.slice(-60)}`); return s; };
      const vertex = compile(gl.VERTEX_SHADER, shaders.vertex);
      const makeProgram = fragment => { const p = gl.createProgram(); gl.attachShader(p, vertex); gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fragment)); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw Error(gl.getProgramInfoLog(p)); return p; };
      const programs = [makeProgram(shaders.fragment), makeProgram(shaders.fallback), makeProgram(silhouetteDiagnostic)];
      const texture = (unit, im, width, height) => {
        const canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height;
        const c = canvas.getContext('2d'); c.drawImage(im, 0, 0, width, height);
        const bytes = c.getImageData(0, 0, width, height).data;
        gl.activeTexture(gl.TEXTURE0 + unit); const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.pixelStorei(gl.UNPACK_ALIGNMENT, 4);
        if (unit === 0) {
          if(width !== 1278 || (width * 2) % 4 !== 0) throw Error('RGB565 scene row must match Android row alignment');
          const packed = new Uint16Array(width * height);
          for (let p = 0, i = 0; p < packed.length; p++, i += 4) packed[p] = (Math.round(bytes[i] * 31 / 255) << 11) | (Math.round(bytes[i+1] * 63 / 255) << 5) | Math.round(bytes[i+2] * 31 / 255);
          gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
          gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, width, height, 0, gl.RGB, gl.UNSIGNED_SHORT_5_6_5, packed);
        } else {
          if(width !== 1279 || (width * 4) % 4 !== 0) throw Error('ARGB shell row must match Android row alignment');
          for (let i = 0; i < bytes.length; i += 4) {
            const alpha = bytes[i+3] / 255;
            bytes[i] = Math.round(bytes[i] * alpha); bytes[i+1] = Math.round(bytes[i+1] * alpha); bytes[i+2] = Math.round(bytes[i+2] * alpha);
          }
          gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
          gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
        }
        return t;
      };
      // Device bitmap scaling yields 1278x720 scene; retain the shell's odd 1279px width.
      texture(0, sceneImage, 1278, 720); texture(1, shellImage, 1279, 720);
      const interleaved = new Float32Array(quad);
      const buffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buffer); gl.bufferData(gl.ARRAY_BUFFER, interleaved, gl.STATIC_DRAW);
      const render = (program, time, day, style = 0, color = [60 / 255, 226 / 255, 239 / 255]) => {
        gl.useProgram(program);
        const pos = gl.getAttribLocation(program, 'aPosition'), uv = gl.getAttribLocation(program, 'aUv');
        gl.vertexAttribPointer(pos, 2, gl.FLOAT, false, 16, 0); gl.enableVertexAttribArray(pos);
        gl.vertexAttribPointer(uv, 2, gl.FLOAT, false, 16, 8); gl.enableVertexAttribArray(uv);
        gl.uniform1i(gl.getUniformLocation(program, 'uScene'), 0); gl.uniform1i(gl.getUniformLocation(program, 'uShell'), 1);
        const size = gl.getUniformLocation(program, 'uSize'); if (size) gl.uniform2f(size, 1280, 720);
        const t = gl.getUniformLocation(program, 'uTime'); if (t) gl.uniform1f(t, time);
        gl.uniform1f(gl.getUniformLocation(program, 'uDay'), day);
        const uStyle=gl.getUniformLocation(program,'uStyle'); if(uStyle) gl.uniform1f(uStyle,style);
        const uColor=gl.getUniformLocation(program,'uColor'); if(uColor) gl.uniform3f(uColor,...color);
        gl.viewport(0, 0, 1280, 720); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        const pixels = new Uint8Array(1280*720*4); gl.readPixels(0, 0, 1280, 720, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
        return pixels;
      };
      const sceneCanvas = document.createElement('canvas'); sceneCanvas.width=1278; sceneCanvas.height=720;
      sceneCanvas.getContext('2d').drawImage(sceneImage,0,0,1278,720);
      const scenePixels = sceneCanvas.getContext('2d').getImageData(0,0,1278,720).data;
      const shellCanvas = document.createElement('canvas'); shellCanvas.width=1279; shellCanvas.height=720;
      shellCanvas.getContext('2d').drawImage(shellImage,0,0,1279,720);
      const alpha = shellCanvas.getContext('2d').getImageData(0,0,1279,720).data;
      const samples = [render(programs[0], 0.3, 1), render(programs[0], 2.1, 1), render(programs[0], 0.3, 0)];
      const fallback = render(programs[1], 0, 1);
      const fallbackPlain = render(programs[1], 0, 1, 3);
      const silhouette = [render(programs[2], 0.3, 1), render(programs[2], 2.1, 1)];
      const themes = Array.from({length:6},(_,style)=>render(programs[0],0.3,1,style,style===5?[132/255,12/255,24/255]:undefined));
      const custom = render(programs[0],0.3,1,0,[1,0.04,0.02]);
      const sample = (pixels,x,y) => { const i=((719-y)*1280+x)*4; return [pixels[i],pixels[i+1],pixels[i+2]]; };
      const sceneAt = (x,y) => { const sx=Math.min(1277,Math.floor((x+0.5)*1278/1280)), sy=Math.min(719,Math.floor((y+0.5)*720/720)); const i=(sy*1278+sx)*4; return [Math.round(scenePixels[i]*31/255)*255/31,Math.round(scenePixels[i+1]*63/255)*255/63,Math.round(scenePixels[i+2]*31/255)*255/31]; };
      const shellAlpha = (x,y) => alpha[(Math.min(719,y)*1279+Math.min(1278,Math.floor((x+0.5)*1279/1280)))*4+3];
      let transparent=0, moving=0, boundary=0, movingBoundary=0, staticArea=0, daySum=0, nightSum=0, sceneError=0, glassDelta=0, clearDelta=0, count=0;
      for(let y=24;y<696;y+=24) for(let x=32;x<1248;x+=32) {
        const a=shellAlpha(x,y), one=sample(samples[0],x,y), two=sample(samples[1],x,y), n=sample(samples[2],x,y);
        if(a<2) { transparent++; const expected=sceneAt(x,y), base=sample(fallback,x,y); sceneError+=base.reduce((sum,v,k)=>sum+Math.abs(v-expected[k]),0); }
        const delta=one.reduce((sum,v,k)=>sum+Math.abs(v-two[k]),0);
        if(a>80) { glassDelta+=delta; if(delta>3) moving++; }
        if(a>=20 && a<=235) { boundary++; if(delta>3) movingBoundary++; }
        if(a<2) { staticArea++; clearDelta+=delta; }
        daySum+=one[0]+one[1]+one[2]; nightSum+=n[0]+n[1]+n[2]; count++;
      }
      let movingSilhouette=0;
      for(let i=0;i<silhouette[0].length;i+=4) if(Math.abs(silhouette[0][i]-silhouette[1][i])>8) movingSilhouette++;
      let plainError=0, fallbackPlainError=0, customDelta=0;
      const styleDeltas=Array(6).fill(0);
      for(let y=24;y<696;y+=24) for(let x=32;x<1248;x+=32) {
        const plain=sample(themes[3],x,y), expected=sceneAt(x,y);
        plainError+=plain.reduce((sum,v,k)=>sum+Math.abs(v-expected[k]),0);
        const fallbackScene=sample(fallbackPlain,x,y);
        fallbackPlainError+=fallbackScene.reduce((sum,v,k)=>sum+Math.abs(v-expected[k]),0);
        if(shellAlpha(x,y)>80) {
          const glass=sample(themes[0],x,y), recolored=sample(custom,x,y);
          customDelta+=glass.reduce((sum,v,k)=>sum+Math.abs(v-recolored[k]),0);
          for(let style=1;style<6;style++) {
            const themed=sample(themes[style],x,y);
            styleDeltas[style]+=glass.reduce((sum,v,k)=>sum+Math.abs(v-themed[k]),0);
          }
        }
      }
      if(gl.getError()!==gl.NO_ERROR) throw Error('WebGL error after shader rendering');
      if(!transparent || !moving || !boundary || !movingBoundary || !staticArea) throw Error(`coverage insufficient: transparent=${transparent}, moving=${moving}, boundary=${boundary}, movingBoundary=${movingBoundary}`);
      if(!movingSilhouette) throw Error('shell alpha boundary did not move between frames');
      if(plainError/(transparent*3)>5) throw Error(`plain theme differs from scene (${plainError/(transparent*3)})`);
      if(fallbackPlainError/(transparent*3)>5) throw Error(`fallback plain theme differs from scene (${fallbackPlainError/(transparent*3)})`);
      if(customDelta<100 || styleDeltas.slice(1).some(delta=>delta<100)) throw Error(`theme variants lack visible color/style changes: custom=${customDelta}, styles=${styleDeltas.slice(1)}`);
      if(sceneError/(transparent*3)>5) throw Error(`transparent scene mean error too high: ${sceneError/(transparent*3)}`);
      if(glassDelta/moving<=clearDelta/staticArea) throw Error('animation is not concentrated inside the static shell silhouette');
      if(daySum<=nightSum) throw Error(`day mode did not brighten output (${daySum} <= ${nightSum})`);
      return { renderer: gl.getParameter(gl.RENDERER), transparent, moving, movingBoundary, movingSilhouette, customDelta, styleDeltas:styleDeltas.slice(1), dayMean:Math.round(daySum/(count*3)), nightMean:Math.round(nightSum/(count*3)) };
    }, { shaders, silhouetteDiagnostic, quad, sceneUrl: image('ray-landscape.jpg'), shellUrl: image('ray-fluid-shell.png') });
    console.log(`PASS: WebGL ${result.renderer}; RGB565/RGBA uploads, shader+fallback compile, no GL errors; scene alpha samples=${result.transparent}, animated glass samples=${result.moving}, moving boundary samples=${result.movingBoundary}, moving silhouette pixels=${result.movingSilhouette}, color/style deltas=${result.customDelta}/${result.styleDeltas}, day/night mean=${result.dayMean}/${result.nightMean}`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
