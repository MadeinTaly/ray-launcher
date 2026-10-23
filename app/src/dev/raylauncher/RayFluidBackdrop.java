package dev.raylauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Flowing glass shell with GPU refraction and highlights. */
final class RayFluidBackdrop extends GLSurfaceView implements Runnable {
    private static final String TAG = "RayFluid";
    private static final long FRAME_MS = 42;
    /** Wave speed: 1 = original, lower is calmer. */
    private static final float SPEED = 1f / 3f;
    private static final String VERTEX =
            "attribute vec2 aPosition; attribute vec2 aUv; varying vec2 vUv;"
            + "void main(){vUv=aUv;gl_Position=vec4(aPosition,0.0,1.0);}";
    private static final String FRAGMENT =
            "precision mediump float; varying vec2 vUv;"
            + "uniform sampler2D uScene,uShell; uniform vec2 uSize; uniform float uTime,uDay,uStyle; uniform vec3 uColor;"
            + "void main(){"
            + "vec2 uv=vUv; vec2 inset=vec2(0.5)/uSize;"
            + "vec3 scene=texture2D(uScene,uv).rgb;"
            + "if(uStyle>6.5){gl_FragColor=vec4(scene,1.0);return;}"
            + "vec3 sceneTone=mix(vec3(0.68,0.76,0.88),vec3(1.0),uDay);scene*=sceneTone;"
            + "if(uStyle>2.5&&uStyle<3.5){gl_FragColor=vec4(scene,1.0);return;}"
            + "if(uStyle>5.5){vec4 k=texture2D(uShell,uv);gl_FragColor=vec4(scene*(1.0-k.a)+k.rgb*mix(0.75,1.0,uDay),1.0);return;}"
            + "vec2 p=uv*vec2(uSize.x/uSize.y,1.0);"
            + "float a=dot(p,vec2(18.0,10.0))-uTime*1.6;"
            + "float b=dot(p,vec2(9.0,-21.0))+uTime*0.8;"
            + "float c=dot(p,vec2(31.0,16.0))-uTime*1.2;"
            + "vec2 flow=vec2(cos(a)+0.65*cos(b),0.7*cos(a)-cos(b)+0.3*cos(c));"
            + "if(uStyle>3.5&&uStyle<4.5){float fa=dot(p,vec2(5.0,3.0))-uTime*0.4;float fb=dot(p,vec2(2.0,-6.0))+uTime*0.8;flow=vec2(sin(fa)+0.55*sin(fb),0.7*cos(fa)-cos(fb));}"
            + "float waveSize=mix(4.5,11.0,step(3.5,uStyle)*step(uStyle,4.5));"
            + "vec2 offset=flow*vec2(waveSize)/uSize;"
            + "vec4 movingGlass=texture2D(uShell,clamp(uv+offset,inset,1.0-inset));"
            + "vec3 movingRgb=movingGlass.rgb/max(movingGlass.a,0.004);"
            + "vec3 glass=movingRgb;"
            + "vec3 refracted=texture2D(uScene,clamp(uv-offset*1.25,inset,1.0-inset)).rgb;"
            + "refracted*=sceneTone;"
            + "float light=max(glass.r,max(glass.g,glass.b));"
            + "vec3 navy=vec3(0.003,0.005,0.014)+uColor*(vec3(0.018)+glass*0.19);"
            + "navy+=smoothstep(0.68,0.97,light)*uColor*0.22;"
            + "vec3 material=mix(navy,glass*uColor,uDay);"
            + "material=mix(material,refracted,0.12);"
            + "float ridge=smoothstep(0.72,0.98,sin(a+0.55*sin(b)));"
            + "ridge*=smoothstep(0.15,0.80,sin(b+0.6*cos(c)));"
            + "if(uStyle>0.5&&uStyle<1.5){float chrome=smoothstep(0.22,0.92,light);material=mix(navy,mix(vec3(1.0),uColor,0.38)*chrome*0.55,uDay);material+=vec3(chrome)*0.10+ridge*0.08;}"
            + "else if(uStyle>1.5&&uStyle<2.5){float rim=smoothstep(0.78,0.98,light);material+=uColor*rim*0.65;}"
            + "else if(uStyle>4.5){material=vec3(0.002)+uColor*(0.015+light*0.18+ridge*0.12);}"
            + "else{material+=ridge*uColor*0.09;}"
            + "gl_FragColor=vec4(mix(scene,material,movingGlass.a),1.0);}";
    private static final String FALLBACK =
            "precision mediump float; varying vec2 vUv; uniform sampler2D uScene,uShell;"
            + "uniform float uDay,uStyle; uniform vec3 uColor; void main(){vec3 c=texture2D(uScene,vUv).rgb;"
            + "if(uStyle>6.5){gl_FragColor=vec4(c,1.0);return;}"
            + "c*=mix(vec3(0.68,0.76,0.88),vec3(1.0),uDay);"
            + "if(uStyle>2.5&&uStyle<3.5){gl_FragColor=vec4(c,1.0);return;}"
            + "if(uStyle>5.5){vec4 k=texture2D(uShell,vUv);gl_FragColor=vec4(c*(1.0-k.a)+k.rgb*mix(0.75,1.0,uDay),1.0);return;}"
            + "vec4 s=texture2D(uShell,vUv);vec3 tint=uColor;"
            + "gl_FragColor=vec4(c*(1.0-s.a)+s.rgb*mix(tint*0.22,vec3(1.0),uDay),1.0);} ";

    private final LiquidRenderer liquid = new LiquidRenderer();
    private volatile boolean day;
    private volatile int themeColor = Color.rgb(60, 226, 239);
    private volatile int themeStyle;
    private volatile String shellAsset = "ray-fluid-shell.png";
    private volatile String skinShell;
    private volatile float animationTime;
    private boolean resumed, running, released;
    private long animationStarted;
    private volatile long renderedFrames;
    /** Latest scene from the UI thread; the GL thread uploads it lazily (one slot, bounded memory, survives context loss). */
    private final Object sceneLock = new Object();
    private Bitmap sceneBitmap;
    private Bitmap uploading, deferredRecycle; // GL thread reads `uploading` outside the lock
    private boolean sceneDirty;

    RayFluidBackdrop(Context context, boolean dayMode) {
        super(context);
        day = dayMode;
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 0, 0, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(liquid);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    void setDayMode(boolean enabled) {
        day = enabled;
        if (!released) requestRender();
    }

    /** style 6 draws skinShell as it is, without tint or animation; style 7 (frameless skins) draws only the scene, once. */
    /** Replaces the scene behind the glass (landscape plus cards); the backdrop owns and recycles the bitmap. */
    void setScene(final Bitmap scene) {
        Bitmap old;
        synchronized (sceneLock) {
            if (released) { scene.recycle(); return; }
            old = sceneBitmap;
            if (old == scene) old = null;
            sceneBitmap = scene;
            sceneDirty = true;
            if (old != null && old == uploading) { deferredRecycle = old; old = null; } // GL thread still reading it
        }
        if (old != null) old.recycle();
        requestRender();
    }

    void setTheme(int color, int style, String skinShell) {
        this.skinShell = skinShell;
        style = Math.max(0, Math.min(7, style));
        int selectedColor = color;
        float[] hsv = new float[3];
        Color.colorToHSV(selectedColor, hsv);
        String asset = style == 7 ? "" : skinShell != null ? skinShell : style == 3 ? shellAsset : style == 5 ? "ray-theme-horror.png"
                : style == 4 ? "ray-theme-cyan.png" : shellForHue(hsv[0]);
        themeColor = selectedColor;
        themeStyle = style;
        if (!asset.equals(shellAsset)) {
            shellAsset = asset; // GL thread reloads it at the next draw (no queued GL work while paused)
        }
        if (!released) {
            if (resumed) updateAnimationLoop();
            else requestRender();
        }
    }

    private static String shellForHue(float hue) {
        String[] assets = {"ray-theme-cyan.png", "ray-theme-violet.png", "ray-theme-amber.png", "ray-theme-emerald.png"};
        float[] hues = {185f, 275f, 42f, 142f};
        int closest = 0;
        float distance = 360f;
        for (int i = 0; i < hues.length; i++) {
            float delta = Math.abs(hue - hues[i]);
            delta = Math.min(delta, 360f - delta);
            if (delta < distance) { distance = delta; closest = i; }
        }
        return assets[closest];
    }

    void start() {
        if (released) return;
        if (!resumed) {
            onResume();
            resumed = true;
        }
        updateAnimationLoop();
    }

    private void updateAnimationLoop() {
        removeCallbacks(this);
        running = resumed && !released && themeStyle != 3 && themeStyle < 6
                && Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        animationStarted = SystemClock.uptimeMillis() - (long) (animationTime * 1000f / SPEED);
        requestRender();
        if (running) post(this);
    }

    void stop() {
        running = false;
        removeCallbacks(this);
        if (resumed) {
            onPause();
            resumed = false;
        }
    }

    void release() {
        if (released) return;
        synchronized (sceneLock) {
            released = true;
            if (sceneBitmap != null && sceneBitmap != uploading) sceneBitmap.recycle();
            else if (sceneBitmap != null) deferredRecycle = sceneBitmap;
            sceneBitmap = null;
            sceneDirty = false;
        }
        queueEvent(new Runnable() {
            @Override public void run() { liquid.dispose(); }
        });
        stop();
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w <= 0 || h <= 0) return;
        float scale = Math.min(1f, Math.min(1280f / w, 720f / h));
        getHolder().setFixedSize(Math.max(1, Math.round(w * scale)),
                Math.max(1, Math.round(h * scale)));
    }

    @Override public void run() {
        if (!running || released || themeStyle == 3 || themeStyle >= 6 || liquid.initializationFailed) {
            running = false;
            return;
        }
        // All three waves repeat together; bounded time also preserves mediump accuracy.
        animationTime = (float) (((SystemClock.uptimeMillis() - animationStarted) / 1000.0 * SPEED)
                % (Math.PI * 5.0));
        requestRender();
        postDelayed(this, FRAME_MS);
    }

    long getRenderedFrames() { return renderedFrames; }

    /** Instrumentation only: SurfaceView pixels do not appear in View.draw(). */
    void captureFrame(final File file) {
        if (released) throw new IllegalStateException("backdrop released");
        if (file == null) throw new IllegalArgumentException("capture file missing");
        queueEvent(new Runnable() {
            @Override public void run() { liquid.capture(file); }
        });
        requestRender();
    }

    private Bitmap load(String name, Bitmap.Config config, int maxSize) throws IOException {
        if (name.isEmpty()) return Bitmap.createBitmap(2, 2, config); // placeholder texture: the scene comes from setScene
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = config;
        options.inScaled = false;
        Bitmap bitmap;
        try (InputStream stream = RaySkin.openPath(getContext(), name)) {
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap == null) throw new IOException("Cannot decode " + name);
        float scale = Math.min(1f, Math.min((float) Math.min(1280, maxSize) / bitmap.getWidth(),
                (float) Math.min(720, maxSize) / bitmap.getHeight()));
        if (scale < 1f) {
            Bitmap resized = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            if (resized != bitmap) bitmap.recycle();
            bitmap = resized;
        }
        if (config == Bitmap.Config.RGB_565 && (bitmap.getWidth() & 1) != 0) {
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, Math.max(2, bitmap.getWidth() - 1),
                    bitmap.getHeight(), true);
            bitmap.recycle();
            bitmap = resized;
        }
        return bitmap;
    }

    private final class LiquidRenderer implements GLSurfaceView.Renderer {
        private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final int[] textures = new int[2];
        private int program, position, uv, sceneUniform, shellUniform, sizeUniform, timeUniform, dayUniform;
        private int colorUniform, styleUniform, maxTextureSize;
        private int width, height;
        private String loadedShell, lastShellFailure;
        private Bitmap.Config texConfig;
        private int texW, texH;
        private File pendingCapture;
        private volatile boolean initializationFailed;

        LiquidRenderer() {
            quad.put(new float[]{-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f,
                    -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f}).position(0);
            if (quad.remaining() != 16) throw new AssertionError("invalid full-screen quad");
        }

        @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            program = 0;
            initializationFailed = false;
            loadedShell = lastShellFailure = null;
            texConfig = null;
            textures[0] = textures[1] = 0;
            Log.i(TAG, "GPU " + GLES20.glGetString(GLES20.GL_RENDERER)
                    + " / " + GLES20.glGetString(GLES20.GL_VERSION));
            try {
                try {
                    program = link(FRAGMENT);
                } catch (IllegalStateException error) {
                    Log.e(TAG, "Liquid shader failed; using static fallback", error);
                    program = link(FALLBACK);
                }
                position = GLES20.glGetAttribLocation(program, "aPosition");
                uv = GLES20.glGetAttribLocation(program, "aUv");
                sceneUniform = GLES20.glGetUniformLocation(program, "uScene");
                shellUniform = GLES20.glGetUniformLocation(program, "uShell");
                sizeUniform = GLES20.glGetUniformLocation(program, "uSize");
                timeUniform = GLES20.glGetUniformLocation(program, "uTime");
                dayUniform = GLES20.glGetUniformLocation(program, "uDay");
                colorUniform = GLES20.glGetUniformLocation(program, "uColor");
                styleUniform = GLES20.glGetUniformLocation(program, "uStyle");
                if (position < 0 || uv < 0 || sceneUniform < 0 || shellUniform < 0 || dayUniform < 0
                        || colorUniform < 0 || styleUniform < 0)
                    throw new IllegalStateException("Required shader input missing");
                int[] maxSize = new int[1];
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0);
                maxTextureSize = maxSize[0];
                GLES20.glGenTextures(2, textures, 0);
                upload(0, themeStyle == 7 ? "" : "ray-landscape.jpg", Bitmap.Config.RGB_565, maxSize[0]);
                String requestedShell = shellAsset;
                try {
                    upload(1, requestedShell, Bitmap.Config.ARGB_8888, maxSize[0]);
                    loadedShell = requestedShell;
                } catch (IOException | IllegalStateException error) {
                    if (!"ray-fluid-shell.png".equals(requestedShell)) {
                        Log.w(TAG, "Theme shell unavailable; using original", error);
                        upload(1, "ray-fluid-shell.png", Bitmap.Config.ARGB_8888, maxSize[0]);
                        loadedShell = "ray-fluid-shell.png";
                    } else throw error;
                }
                checkGl("texture upload");
                synchronized (sceneLock) { sceneDirty = sceneBitmap != null; } // new context: texture is gone
                Log.i(TAG, "Ready: two textures, one quad, 24 fps; max texture " + maxSize[0]);
            } catch (IOException error) {
                initializationFailed = true;
                Log.e(TAG, "Backdrop assets failed", error);
                dispose();
            } catch (RuntimeException | OutOfMemoryError error) {
                initializationFailed = true;
                Log.e(TAG, "Backdrop GL initialization failed", error);
                dispose();
            }
        }

        /** GL thread only, once per draw: applies whatever the UI thread changed since. */
        private void syncTextures() {
            String asset = shellAsset;
            if (!asset.equals(loadedShell)) reloadShell(asset);
            Bitmap scene;
            synchronized (sceneLock) {
                if (!sceneDirty || sceneBitmap == null) return;
                sceneDirty = false;
                scene = sceneBitmap;
                uploading = scene;
            }
            {
                try {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
                    if (scene.getConfig() == texConfig && scene.getWidth() == texW && scene.getHeight() == texH) {
                        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, scene); // no realloc per frame
                    } else {
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, scene, 0);
                        texConfig = scene.getConfig();
                        texW = scene.getWidth();
                        texH = scene.getHeight();
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "Scene upload failed", error);
                }
            }
            Bitmap stale;
            synchronized (sceneLock) {
                uploading = null;
                stale = deferredRecycle;
                deferredRecycle = null;
            }
            if (stale != null) stale.recycle();
        }

        void reloadShell(String asset) {
            if (program == 0 || textures[1] == 0 || asset.equals(loadedShell) || asset.equals(lastShellFailure)) return;
            try {
                upload(1, asset, Bitmap.Config.ARGB_8888, maxTextureSize);
                checkGl("theme shell upload");
                loadedShell = asset;
                lastShellFailure = null;
            } catch (IOException | RuntimeException | OutOfMemoryError error) {
                if (!asset.equals(lastShellFailure)) {
                    Log.w(TAG, "Theme shell reload failed; retaining current shell", error);
                    lastShellFailure = asset;
                }
            }
        }

        private void upload(int index, String asset, Bitmap.Config config, int maxSize) throws IOException {
            Bitmap bitmap = load(asset, config, maxSize);
            try {
                int width = bitmap.getWidth();
                int bytesPerPixel = config == Bitmap.Config.RGB_565 ? 2
                        : config == Bitmap.Config.ARGB_8888 ? 4 : 0;
                int expectedRowBytes = (width * bytesPerPixel + 3) & ~3;
                if (bytesPerPixel == 0 || bitmap.getConfig() != config
                        || width < 2 || (config == Bitmap.Config.RGB_565 && (width & 1) != 0)
                        || bitmap.getRowBytes() != expectedRowBytes) {
                    throw new IOException("Unsupported bitmap layout for " + asset + ": "
                            + width + "x" + bitmap.getHeight() + ", config=" + bitmap.getConfig()
                            + ", rowBytes=" + bitmap.getRowBytes() + ", expected=" + expectedRowBytes);
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                if (index == 0) { texConfig = config; texW = width; texH = bitmap.getHeight(); }
                Log.i(TAG, asset + " " + width + "x" + bitmap.getHeight() + ", config="
                        + bitmap.getConfig() + ", rowBytes=" + bitmap.getRowBytes());
            } finally {
                bitmap.recycle();
            }
        }

        @Override public void onSurfaceChanged(GL10 unused, int w, int h) {
            width = w;
            height = h;
            GLES20.glViewport(0, 0, w, h);
        }

        @Override public void onDrawFrame(GL10 unused) {
            GLES20.glClearColor(0.012f, 0.032f, 0.065f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (program == 0 || width <= 0 || height <= 0) return;
            syncTextures();
            GLES20.glUseProgram(program);
            quad.position(0);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad);
            quad.position(2);
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glEnableVertexAttribArray(uv);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
            GLES20.glUniform1i(sceneUniform, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1]);
            GLES20.glUniform1i(shellUniform, 1);
            GLES20.glUniform2f(sizeUniform, width, height);
            GLES20.glUniform1f(timeUniform, animationTime);
            GLES20.glUniform1f(dayUniform, day ? 1f : 0f);
            int color = themeColor;
            GLES20.glUniform3f(colorUniform, Color.red(color) / 255f,
                    Color.green(color) / 255f, Color.blue(color) / 255f);
            GLES20.glUniform1f(styleUniform, themeStyle);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            renderedFrames++;
            if (renderedFrames <= 2) {
                Log.i(TAG, "Frame " + renderedFrames + ", " + width + "x" + height
                        + ", time=" + animationTime + ", GL error=" + GLES20.glGetError());
            }
            if (pendingCapture != null) {
                File file = pendingCapture;
                pendingCapture = null;
                saveCapture(file);
            }
        }

        void capture(File file) {
            pendingCapture = file;
            requestRender();
        }

        private void saveCapture(File file) {
            Bitmap bitmap = null;
            File temporary = new File(file.getPath() + ".tmp");
            try {
                ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
                checkGl("capture");
                int[] pixels = new int[width * height];
                for (int y = 0; y < height; y++) {
                    int destination = (height - 1 - y) * width;
                    for (int x = 0; x < width; x++) {
                        int r = rgba.get() & 255, g = rgba.get() & 255;
                        int b = rgba.get() & 255, a = rgba.get() & 255;
                        pixels[destination + x] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                }
                bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
                try (FileOutputStream stream = new FileOutputStream(temporary)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                        throw new IOException("PNG capture failed");
                }
                if (!temporary.renameTo(file)) throw new IOException("Cannot publish capture " + file);
                Log.i(TAG, "Captured frame " + renderedFrames + " time=" + animationTime + " to " + file);
            } catch (IOException error) {
                Log.e(TAG, "Frame capture failed", error);
            } catch (IllegalStateException error) {
                Log.e(TAG, "Frame capture GL failed", error);
            } finally {
                if (bitmap != null) bitmap.recycle();
                if (temporary.exists() && !temporary.delete()) Log.w(TAG, "Cannot remove capture temporary file");
            }
        }

        void dispose() {
            GLES20.glDeleteTextures(2, textures, 0);
            textures[0] = textures[1] = 0;
            if (program != 0) GLES20.glDeleteProgram(program);
            program = 0;
            loadedShell = null;
        }
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Shader " + type + ": " + error);
        }
        return shader;
    }

    private static int link(String fragment) {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
        int pixel = 0, program = 0;
        try {
            pixel = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, pixel);
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) throw new IllegalStateException("Shader link: " + GLES20.glGetProgramInfoLog(program));
            return program;
        } catch (IllegalStateException error) {
            if (program != 0) GLES20.glDeleteProgram(program);
            throw error;
        } finally {
            GLES20.glDeleteShader(vertex);
            if (pixel != 0) GLES20.glDeleteShader(pixel);
        }
    }

    private static void checkGl(String operation) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException(operation + ": GL error " + error);
    }
}
