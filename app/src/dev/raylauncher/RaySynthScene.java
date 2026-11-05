package dev.raylauncher;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.provider.Settings;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Random;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * Synthwave background in plain GLES 2.0,
 * tuned for weak TV boxes: half-res surface, 30 fps cap, ~1700 line vertices.
 * Sun/moon sits right of centre so the left side stays calm for text.
 */
final class RaySynthScene extends GLSurfaceView {
    private static final String TAG = "RaySynthScene";


    private static final float HORIZON = -0.1f;   // horizon height in NDC
    private static final float SUN_X = 0.32f;     // ~66% of the screen width
    private static final float SUN_R = 0.27f;     // radius in NDC-y units
    private static final float EYE = 3.2f;
    private static final float SPEED = 9f;        // world units/s toward camera
    private static final float TILE = 80f, WIDTH = 48f, GAP = 12f, RECYCLE = 50f;
    private static final int SEGX = 5, SEGZ = 8, CHUNKS = 3;
    private static final float GRID_SP = 6f, GRID_Z = 120f, GRID_X = 60f, GRID_XSP = 6f;
    private static final int STARS = 200;
    private static final long FRAME_NS = 33000000L;
    private static final float TWO_PI = (float) (Math.PI * 2);
    private static final float[] MV = {
            0, 0, -0.50f,          // 0 nose
            0, 0.10f, -0.32f,      // 1 head
            0, 0.22f, 0,           // 2 back hump
            0, 0.16f, 0.30f,       // 3
            0, 0.06f, 0.58f,       // 4
            0, 0.02f, 0.72f,       // 5 tail root
            0.30f, 0.03f, -0.42f,  // 6 shoulder
            0.62f, 0.05f, -0.30f,  // 7 leading edge
            1.05f, 0.10f, 0.12f,   // 8 wing tip
            0.72f, 0.04f, 0.25f,   // 9 trailing edge
            0.40f, 0.04f, 0.42f,   // 10
            0.16f, 0.05f, 0.60f,   // 11
            0.17f, 0.17f, -0.20f,  // 12 body ridge
            0.22f, 0.15f, 0.15f,   // 13
            0.12f, 0.10f, 0.45f,   // 14
            0.50f, 0.09f, -0.08f,  // 15 wing mid
            0.55f, 0.08f, 0.05f,   // 16
            0.07f, 0.04f, -0.52f,  // 17 cephalic fin, inner base
            0.20f, -0.04f, -1.00f, // 18 fin tip
            0.16f, 0.09f, -0.66f,  // 19 fin top
            0.03f, 0.02f, 0.72f,   // 20 tail side
            0, 0, 1.7f};           // 21 tail end
    private static final int[] MT = {
            1, 12, 2, 2, 12, 13, 2, 13, 3, 3, 13, 14, 3, 14, 4,
            1, 6, 12, 12, 6, 15, 12, 15, 13, 13, 15, 16, 13, 16, 14,
            14, 16, 10, 14, 10, 11, 14, 11, 4,
            6, 7, 15, 7, 8, 15, 15, 8, 16, 16, 8, 9, 16, 9, 10,
            0, 17, 1, 17, 19, 1, 17, 18, 19, 6, 18, 19, 6, 19, 1,
            4, 11, 5, 5, 11, 20, 5, 20, 21};

    private final float[] cSkyTop, cSkyHor, cSunTop, cSunBot, cMtn, cGrid;
    private final boolean day;
    private final float sunR, gap, hscale;
    private volatile boolean still;

    RaySynthScene(Context c, boolean day) {
        super(c);
        this.day = day;
        cSkyTop = rgb(day ? 0x1C0E30 : 0x070A1C);
        cSkyHor = rgb(day ? 0x7A2A3E : 0x1A1440);
        cSunTop = rgb(day ? 0xFFC46A : 0xE8ECF8);
        cSunBot = rgb(day ? 0xEC3E3A : 0x9FB4E0);
        cMtn = rgb(day ? 0xFF8A5A : 0x00CDFE);
        cGrid = rgb(day ? 0xFF6A4A : 0xFF70CE);
        sunR = SUN_R;
        gap = GAP;
        hscale = 1f;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(new EGLConfigChooser() {
            @Override public EGLConfig chooseConfig(EGL10 egl, EGLDisplay d) {
                int[][] sizes = {{8, 8, 8}, {5, 6, 5}, {0, 0, 0}};
                EGLConfig[] cfg = new EGLConfig[1];
                int[] n = new int[1];
                for (int[] s : sizes) {
                    int[] attr = {EGL10.EGL_RED_SIZE, s[0], EGL10.EGL_GREEN_SIZE, s[1], EGL10.EGL_BLUE_SIZE, s[2],
                            EGL10.EGL_DEPTH_SIZE, 0, 0x3040 /* RENDERABLE_TYPE */, 4 /* ES2 */, EGL10.EGL_NONE};
                    if (egl.eglChooseConfig(d, attr, cfg, 1, n) && n[0] > 0) return cfg[0];
                }
                throw new IllegalArgumentException("no EGL config");
            }
        });
        setRenderer(new Scene());
        applyMode();
    }

    static boolean supported(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.getDeviceConfigurationInfo().reqGlEsVersion >= 0x20000;
    }

    private static float[] rgb(int c) {
        return new float[]{Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f};
    }

    private void applyMode() {
        still = Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        setRenderMode(still ? RENDERMODE_WHEN_DIRTY : RENDERMODE_CONTINUOUSLY);
        if (still) requestRender();
    }

    @Override public void onResume() {
        applyMode();
        super.onResume();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 1 && h > 1) getHolder().setFixedSize(w / 2, h / 2);
    }

    private final class Scene implements GLSurfaceView.Renderer {
        static final String QUAD_VS = "attribute vec2 aPos; uniform vec4 uXf; varying vec2 vUv;"
                + "void main(){ vUv=aPos; gl_Position=vec4(uXf.xy+aPos*uXf.zw,0.0,1.0); }";
        static final String SKY_FS = "precision mediump float; varying vec2 vUv;"
                + "uniform vec3 uTop; uniform vec3 uHor; uniform float uH;"
                + "void main(){ float t=clamp((vUv.y-uH)/(1.0-uH),0.0,1.0);"
                + " gl_FragColor=vec4(mix(uHor,uTop,sqrt(t)),1.0); }";
        static final String SUN_FS = "precision mediump float; varying vec2 vUv;"
                + "uniform vec3 uTop; uniform vec3 uBot; uniform float uMoon;"
                + "float cr(vec2 c,float r){ return 1.0-smoothstep(r*0.5,r,length(vUv-c)); }"
                + "void main(){ float a=1.0-smoothstep(0.95,1.0,length(vUv)); float y=vUv.y*0.5+0.5;"
                + " vec3 col=mix(uBot,uTop,y);"
                + " if(uMoon>0.5){"
                + "  float k=cr(vec2(-0.35,0.30),0.24)+cr(vec2(0.30,0.45),0.17)+cr(vec2(0.10,-0.25),0.28)"
                + "   +cr(vec2(-0.45,-0.35),0.15)+cr(vec2(0.55,-0.20),0.14)+cr(vec2(-0.05,0.72),0.12);"
                + "  col*=1.0-0.24*clamp(k,0.0,1.0);"
                + " } else if(y<0.5){ float fill=mix(0.8,0.42,(0.5-y)*2.0);"
                + "  a*=1.0-smoothstep(fill,fill+0.06,fract(y*7.0)); }"
                + " gl_FragColor=vec4(col,a); }";
        static final String GLOW_FS = "precision mediump float; varying vec2 vUv; uniform vec3 uCol;"
                + "void main(){ float a=1.0-clamp(length(vUv),0.0,1.0); a*=a;"
                + " gl_FragColor=vec4(uCol*a,a); }";
        static final String STAR_VS = "attribute vec2 aPos; attribute float aPh; uniform float uPh; varying float vTw;"
                + "void main(){ vTw=0.55+0.45*sin(uPh+aPh); gl_PointSize=(1.5+2.0*fract(aPh*3.7))*(0.7+0.5*vTw);"
                + " gl_Position=vec4(aPos,0.0,1.0); }";
        static final String STAR_FS = "precision mediump float; varying float vTw;"
                + "void main(){ float d=length(gl_PointCoord-0.5);"
                + " gl_FragColor=vec4(1.0,1.0,1.0,(1.0-smoothstep(0.0,0.5,d))*vTw*0.8); }";
        static final String LINE_VS = "attribute vec3 aPos; uniform mat4 uP; uniform vec3 uOff; uniform vec4 uCol;"
                + "uniform vec2 uFade; varying vec4 vC;"
                + "void main(){ vec4 p=uP*vec4(aPos+uOff,1.0);"
                + " vC=vec4(uCol.rgb,uCol.a*(1.0-smoothstep(uFade.x,uFade.y,p.w))); gl_Position=p; }";
        static final String LINE_FS = "precision mediump float; varying vec4 vC; void main(){ gl_FragColor=vC; }";

        boolean ok, wide;
        int pSky, pSun, pGlow, pStar, pLine;
        int skXf, skTop, skHor, skH, snXf, snTop, snBot, snMoon, glXf, glCol, stPh, lP, lOff, lCol, lFade;
        int fP, fOff, fFog, mXf, mAn, mEd, mAl;
        int quadVbo, starVbo, mtnVbo, gridVbo, mtnV, gridHV, gridLV;
        final float[] proj = new float[16];
        final float[] mz = new float[2 * CHUNKS];
        final float[] glowCol = new float[3];
        float aspect = 1f, gridScroll, tw, flapPh = 0.8f, bobPh = 1.1f, rollPh = 0.4f;
        long lastNs;

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(cSkyTop[0], cSkyTop[1], cSkyTop[2], 1f);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            pSky = link(QUAD_VS, SKY_FS);
            pSun = link(QUAD_VS, SUN_FS);
            pGlow = link(QUAD_VS, GLOW_FS);
            pStar = link(STAR_VS, STAR_FS);
            pLine = link(LINE_VS, LINE_FS);
            ok = pSky != 0 && pSun != 0 && pGlow != 0 && pStar != 0 && pLine != 0;
            if (!ok) {
                Log.w(TAG, "shader setup failed, clearing to sky colour");
                return;
            }
            skXf = u(pSky, "uXf"); skTop = u(pSky, "uTop"); skHor = u(pSky, "uHor"); skH = u(pSky, "uH");
            snXf = u(pSun, "uXf"); snTop = u(pSun, "uTop"); snBot = u(pSun, "uBot"); snMoon = u(pSun, "uMoon");
            glXf = u(pGlow, "uXf"); glCol = u(pGlow, "uCol");
            stPh = u(pStar, "uPh");
            lP = u(pLine, "uP"); lOff = u(pLine, "uOff"); lCol = u(pLine, "uCol"); lFade = u(pLine, "uFade");
            for (int i = 0; i < 3; i++) glowCol[i] = (cSunTop[i] + cSunBot[i]) * 0.25f;

            quadVbo = vbo(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
            starVbo = vbo(buildStars());
            mtnVbo = vbo(buildMountains());
            gridVbo = vbo(buildGrid());
            for (int c = 0; c < mz.length; c++) mz[c] = 20f - (c % CHUNKS) * TILE;
            float[] r = new float[2];
            GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, r, 0);
            wide = r[1] >= 2f;
            lastNs = 0;
            measured = 0; // a new context after a pause starts its judgement again
            measuredNs = 0;
            judged = false;
        }

        @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
            GLES20.glViewport(0, 0, w, h);
            aspect = w / (float) Math.max(1, h);
            float f = 1f / (float) Math.tan(Math.toRadians(36)), n = 0.1f, fa = 300f;
            Arrays.fill(proj, 0f);
            proj[0] = f / aspect;
            proj[5] = f;
            proj[9] = -HORIZON;
            proj[10] = -(fa + n) / (fa - n);
            proj[11] = -1f;
            proj[14] = -2f * fa * n / (fa - n);
        }

        private int measured;
        private long measuredNs;
        private boolean judged;

        /** After the start-up settles, 5 s of frames decide: too slow here and the home goes back to the still wallpaper. */
        private void watch(long frameNs) {
            if (judged || frameNs > 1000000000L) return; // over a second: paused, not slow
            if (++measured <= 60) return;
            measuredNs += frameNs;
            if (measured < 210) return;
            judged = true;
            if (measuredNs / 150 / 1000000f > RayGraphics.SLOW_MS) {
                RayGraphics.markSlow(getContext());
                RayLauncherActivity.graphicsChanged();
            }
        }

        @Override public void onDrawFrame(GL10 gl) {
            long now = System.nanoTime();
            float dt = 0f;
            if (!still) {
                long wait = FRAME_NS - (now - lastNs);
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1000000L, (int) (wait % 1000000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    now = System.nanoTime();
                }
                if (lastNs != 0) watch(now - lastNs);
                dt = Math.min(0.05f, (now - lastNs) / 1e9f);
                lastNs = now;
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (!ok) return;

            for (int c = 0; c < mz.length; c++) {
                mz[c] += SPEED * dt;
                if (mz[c] > RECYCLE) mz[c] -= CHUNKS * TILE;
            }
            gridScroll = (gridScroll + SPEED * dt) % GRID_SP;
            tw = (tw + 1.6f * dt) % TWO_PI;
            flapPh = (flapPh + 1.3f * dt) % TWO_PI;
            bobPh = (bobPh + 0.9f * dt) % TWO_PI;
            rollPh = (rollPh + 0.55f * dt) % TWO_PI;

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo);
            GLES20.glEnableVertexAttribArray(0);
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);

            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUseProgram(pSky);
            GLES20.glUniform4f(skXf, 0f, 0f, 1f, 1f);
            GLES20.glUniform3f(skTop, cSkyTop[0], cSkyTop[1], cSkyTop[2]);
            GLES20.glUniform3f(skHor, cSkyHor[0], cSkyHor[1], cSkyHor[2]);
            GLES20.glUniform1f(skH, HORIZON);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glEnable(GLES20.GL_BLEND);

            // stars
            if (!day) {
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
                GLES20.glUseProgram(pStar);
                GLES20.glUniform1f(stPh, tw);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, starVbo);
                GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 12, 0);
                GLES20.glEnableVertexAttribArray(1);
                GLES20.glVertexAttribPointer(1, 1, GLES20.GL_FLOAT, false, 12, 8);
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, STARS);
                GLES20.glDisableVertexAttribArray(1);
            }

            // sun glow, then sun
            float sunY = HORIZON + sunR + 0.07f;
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo);
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            GLES20.glUseProgram(pGlow);
            GLES20.glUniform4f(glXf, SUN_X, sunY, sunR * 2.4f / aspect, sunR * 2.4f);
            GLES20.glUniform3f(glCol, glowCol[0], glowCol[1], glowCol[2]);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(pSun);
            GLES20.glUniform4f(snXf, SUN_X, sunY, sunR / aspect, sunR);
            GLES20.glUniform3f(snTop, cSunTop[0], cSunTop[1], cSunTop[2]);
            GLES20.glUniform3f(snBot, cSunBot[0], cSunBot[1], cSunBot[2]);
            GLES20.glUniform1f(snMoon, day ? 0f : 1f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // mountains + grid, optional wide low-alpha pass first as fake glow
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
            GLES20.glUseProgram(pLine);
            GLES20.glUniformMatrix4fv(lP, 1, false, proj, 0);
            if (wide) lines(2f, 0.3f);
            lines(1f, 1f);
            GLES20.glDisableVertexAttribArray(0);
        }

        void lines(float width, float k) {
            GLES20.glLineWidth(width);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mtnVbo);
            GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glUniform2f(lFade, 30f, 90f);
            for (int c = 0; c < mz.length; c++) mountainLines(c, (c < CHUNKS ? 0.14f : 0.3f) * k);
            gridLines(k);
        }

        void mountainLines(int c, float a) {
            GLES20.glUniform3f(lOff, 0f, -EYE, mz[c]);
            GLES20.glUniform4f(lCol, cMtn[0], cMtn[1], cMtn[2], a);
            GLES20.glDrawArrays(GLES20.GL_LINES, c * mtnV, mtnV);
        }

        void gridLines(float k) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo);
            GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glUniform2f(lFade, 10f, 55f);
            GLES20.glUniform4f(lCol, cGrid[0], cGrid[1], cGrid[2], 0.5f * k);
            GLES20.glUniform3f(lOff, 0f, -EYE, gridScroll);
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridHV);
            GLES20.glUniform3f(lOff, 0f, -EYE, 0f);
            GLES20.glDrawArrays(GLES20.GL_LINES, gridHV, gridLV);
        }

        int facet(float[] d, int o, int side, float[] hh, int ax, int az, int bx, int bz, int cx, int cz, float jit) {
            int nx = SEGX + 1;
            float h = (hh[az * nx + ax] + hh[bz * nx + bx] + hh[cz * nx + cx]) / 3f;
            float t = Math.min(1f, Math.max(0f, h / 5f + (jit - 0.5f) * 0.25f));
            float cr = (27f + 31f * t) / 255f, cg = (16f + 12f * t) / 255f, cb = (64f + 56f * t) / 255f;
            int[] ix = {ax, bx, cx}, iz = {az, bz, cz};
            for (int k = 0; k < 3; k++) {
                o = vtx(d, o, side, ix[k], iz[k], hh);
                d[o++] = cr; d[o++] = cg; d[o++] = cb;
            }
            return o;
        }

        float[] buildStars() {
            Random r = new Random(7);
            float[] d = new float[STARS * 3];
            for (int i = 0; i < STARS; i++) {
                d[i * 3] = r.nextFloat() * 2f - 1f;
                d[i * 3 + 1] = r.nextFloat() * 1.02f - 0.02f;
                d[i * 3 + 2] = r.nextFloat() * TWO_PI;
            }
            return d;
        }

        float[] buildGrid() {
            int n = (int) (GRID_Z / GRID_SP) + 1;
            int m = (int) (GRID_X * 2 / GRID_XSP) + 1;
            gridHV = n * 2;
            gridLV = m * 2;
            float[] d = new float[(gridHV + gridLV) * 3];
            int o = 0;
            for (int k = 0; k < n; k++) {
                d[o++] = -GRID_X; d[o++] = 0; d[o++] = -k * GRID_SP;
                d[o++] = GRID_X; d[o++] = 0; d[o++] = -k * GRID_SP;
            }
            for (int i = 0; i < m; i++) {
                float x = -GRID_X + i * GRID_XSP;
                d[o++] = x; d[o++] = 0; d[o++] = 0;
                d[o++] = x; d[o++] = 0; d[o++] = -GRID_Z - GRID_SP;
            }
            return d;
        }

        void heights(int c, float[] hh) {
            int nx = SEGX + 1, nz = SEGZ + 1, side = c < CHUNKS ? -1 : 1;
            float[] pk = new float[5 * 4];
            Random r = new Random(1984L + 31 * c);
            for (int p = 0; p < 5; p++) {
                pk[p * 4] = (r.nextFloat() - 0.5f) * WIDTH;
                pk[p * 4 + 1] = (r.nextFloat() - 0.5f) * TILE;
                pk[p * 4 + 2] = 9f + r.nextFloat() * 15f;
                pk[p * 4 + 3] = 7f + r.nextFloat() * 8f;
            }
            for (int iz = 0; iz < nz; iz++) {
                for (int ix = 0; ix < nx; ix++) {
                    float x = -WIDTH / 2 + WIDTH * ix / SEGX, z = -TILE / 2 + TILE * iz / SEGZ, h = 0;
                    for (int p = 0; p < 5; p++) {
                        float dx = x - pk[p * 4], dz = z - pk[p * 4 + 1], rr = pk[p * 4 + 3];
                        h = Math.max(h, pk[p * 4 + 2] * (float) Math.exp(-(dx * dx + dz * dz) / (rr * rr)));
                    }
                    float out = Math.min(1f, Math.max(0f, x * side / (WIDTH * 0.5f) * 0.5f + 0.5f));
                    hh[iz * nx + ix] = h * (0.12f + 0.88f * out) * hscale;
                }
            }
        }

        // Low-poly wireframe patches with gaussian peaks, lower toward the corridor.
        float[] buildMountains() {
            int nx = SEGX + 1, nz = SEGZ + 1;
            mtnV = 2 * (SEGX * nz + nx * SEGZ + SEGX * SEGZ);
            float[] d = new float[2 * CHUNKS * mtnV * 3];
            float[] hh = new float[nx * nz];
            int o = 0;
            for (int c = 0; c < 2 * CHUNKS; c++) {
                int side = c < CHUNKS ? -1 : 1;
                heights(c, hh);
                for (int iz = 0; iz < nz; iz++) {
                    for (int ix = 0; ix < nx; ix++) {
                        if (ix < SEGX) { o = vtx(d, o, side, ix, iz, hh); o = vtx(d, o, side, ix + 1, iz, hh); }
                        if (iz < SEGZ) { o = vtx(d, o, side, ix, iz, hh); o = vtx(d, o, side, ix, iz + 1, hh); }
                        if (ix < SEGX && iz < SEGZ) { o = vtx(d, o, side, ix, iz, hh); o = vtx(d, o, side, ix + 1, iz + 1, hh); }
                    }
                }
            }
            return d;
        }

        int vtx(float[] d, int o, int side, int ix, int iz, float[] hh) {
            d[o] = side * (gap + WIDTH / 2) - WIDTH / 2 + WIDTH * ix / SEGX;
            d[o + 1] = hh[iz * (SEGX + 1) + ix];
            d[o + 2] = -TILE / 2 + TILE * iz / SEGZ;
            return o + 3;
        }

        int vbo(float[] data) {
            FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(data).position(0);
            int[] id = new int[1];
            GLES20.glGenBuffers(1, id, 0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id[0]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.length * 4, fb, GLES20.GL_STATIC_DRAW);
            return id[0];
        }

        int u(int p, String name) {
            return GLES20.glGetUniformLocation(p, name);
        }

        int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            if (s == 0) return 0;
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] st = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0);
            if (st[0] == 0) {
                Log.w(TAG, "compile: " + GLES20.glGetShaderInfoLog(s));
                GLES20.glDeleteShader(s);
                return 0;
            }
            return s;
        }

        int link(String vs, String fs) {
            int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
            if (v == 0 || f == 0) return 0;
            int p = GLES20.glCreateProgram();
            if (p == 0) return 0;
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glBindAttribLocation(p, 0, "aPos");
            GLES20.glBindAttribLocation(p, 1, "aPh");
            GLES20.glBindAttribLocation(p, 2, "aCol");
            GLES20.glLinkProgram(p);
            int[] st = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0);
            if (st[0] == 0) {
                Log.w(TAG, "link: " + GLES20.glGetProgramInfoLog(p));
                GLES20.glDeleteProgram(p);
                return 0;
            }
            return p;
        }
    }
}
