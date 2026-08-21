package local.lanoverlay.tv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Renders a video whose left half contains RGB and right half contains its alpha mask.
 * Chroma-key extraction and encoding are completed by the desktop before upload.
 */
final class PackedAlphaVideoView extends TextureView implements
        TextureView.SurfaceTextureListener, OverlayVideoPlayer {
    private static final String TAG = "MirrorToTvAlphaVideo";

    private final String mediaPath;
    private final OverlayVideoView.AspectListener aspectListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread renderThread;
    private Handler renderHandler;
    private AlphaRenderer renderer;
    private MediaPlayer player;
    private Surface decoderSurface;
    private boolean playWhenReady = true;
    private boolean muted;

    PackedAlphaVideoView(Context context, String mediaPath, boolean muted,
                         OverlayVideoView.AspectListener aspectListener) {
        super(context);
        this.mediaPath = mediaPath;
        this.muted = muted;
        this.aspectListener = aspectListener;
        setOpaque(false);
        setSurfaceTextureListener(this);
    }

    @Override
    public void startPlayback() {
        playWhenReady = true;
        if (player != null) {
            player.start();
        }
    }

    @Override
    public void pausePlayback() {
        playWhenReady = false;
        if (player != null && player.isPlaying()) {
            player.pause();
        }
    }

    @Override
    public void setMuted(boolean muted) {
        this.muted = muted;
        if (player != null) {
            float volume = muted ? 0f : 1f;
            player.setVolume(volume, volume);
        }
    }

    @Override
    public void releasePlayer() {
        releaseMediaPlayer();
        final CountDownLatch released = new CountDownLatch(1);
        if (renderHandler != null) {
            renderHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (renderer != null) {
                        renderer.release();
                        renderer = null;
                    }
                    released.countDown();
                }
            });
            try {
                released.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (renderThread != null) {
            renderThread.quitSafely();
            renderThread = null;
            renderHandler = null;
        }
    }

    private void releaseMediaPlayer() {
        if (player != null) {
            try {
                player.stop();
            } catch (RuntimeException ignored) { }
            player.release();
            player = null;
        }
        if (decoderSurface != null) {
            decoderSurface.release();
            decoderSurface = null;
        }
    }

    private void openVideo(Surface inputSurface) {
        releaseMediaPlayer();
        decoderSurface = inputSurface;
        final MediaPlayer mediaPlayer = new MediaPlayer();
        player = mediaPlayer;
        try {
            mediaPlayer.setDataSource(mediaPath);
            mediaPlayer.setSurface(inputSurface);
            mediaPlayer.setLooping(true);
            float volume = muted ? 0f : 1f;
            mediaPlayer.setVolume(volume, volume);
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer prepared) {
                    int width = prepared.getVideoWidth();
                    int height = prepared.getVideoHeight();
                    if (width > 1 && height > 0 && aspectListener != null) {
                        aspectListener.onVideoAspect((width / 2f) / height);
                    }
                    if (playWhenReady) {
                        prepared.start();
                    }
                }
            });
            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer failed, int what, int extra) {
                    Log.e(TAG, "Playback error: " + what + "/" + extra);
                    return true;
                }
            });
            mediaPlayer.prepareAsync();
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "Could not open alpha-packed video", error);
            releaseMediaPlayer();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(final SurfaceTexture output, final int width,
                                          final int height) {
        renderThread = new HandlerThread("mirror-to-tv-alpha-video");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
        renderHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    renderer = new AlphaRenderer(output, width, height, renderHandler);
                    final Surface inputSurface = renderer.initialize();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (renderThread == null || renderer == null) {
                                inputSurface.release();
                            } else {
                                openVideo(inputSurface);
                            }
                        }
                    });
                } catch (RuntimeException error) {
                    Log.e(TAG, "Could not initialize alpha renderer", error);
                    if (renderer != null) {
                        renderer.release();
                        renderer = null;
                    }
                }
            }
        });
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, final int width,
                                            final int height) {
        if (renderHandler != null) {
            renderHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (renderer != null) {
                        renderer.setSize(width, height);
                    }
                }
            });
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        releasePlayer();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) { }

    private static final class AlphaRenderer implements SurfaceTexture.OnFrameAvailableListener {
        private static final float[] VERTICES = {
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f
        };
        private static final String VERTEX_SHADER =
                "attribute vec2 aPosition;\n"
                + "attribute vec2 aTexture;\n"
                + "varying vec2 vTexture;\n"
                + "void main() {\n"
                + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
                + "  vTexture = aTexture;\n"
                + "}\n";
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\n"
                + "uniform samplerExternalOES uTexture;\n"
                + "uniform mat4 uTextureMatrix;\n"
                + "varying vec2 vTexture;\n"
                + "void main() {\n"
                + "  vec2 rgbUv = (uTextureMatrix * vec4(vTexture.x * 0.5, vTexture.y, 0.0, 1.0)).xy;\n"
                + "  vec2 alphaUv = (uTextureMatrix * vec4(0.5 + vTexture.x * 0.5, vTexture.y, 0.0, 1.0)).xy;\n"
                + "  vec3 rgb = texture2D(uTexture, rgbUv).rgb;\n"
                + "  float alpha = texture2D(uTexture, alphaUv).r;\n"
                + "  gl_FragColor = vec4(rgb * alpha, alpha);\n"
                + "}\n";

        private final SurfaceTexture outputTexture;
        private final Handler handler;
        private final float[] textureMatrix = new float[16];
        private final FloatBuffer vertices;
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface surface = EGL14.EGL_NO_SURFACE;
        private SurfaceTexture inputTexture;
        private int program;
        private int textureId;
        private int positionLocation;
        private int textureLocation;
        private int textureMatrixLocation;
        private int samplerLocation;
        private int width;
        private int height;
        private boolean released;

        AlphaRenderer(SurfaceTexture outputTexture, int width, int height, Handler handler) {
            this.outputTexture = outputTexture;
            this.width = width;
            this.height = height;
            this.handler = handler;
            vertices = ByteBuffer.allocateDirect(VERTICES.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertices.put(VERTICES).position(0);
        }

        Surface initialize() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0,
                    version, 1)) {
                throw new IllegalStateException("EGL initialization failed");
            }
            int[] configAttributes = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] configCount = new int[1];
            if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1,
                    configCount, 0) || configCount[0] == 0) {
                throw new IllegalStateException("EGL config not found");
            }
            int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    contextAttributes, 0);
            surface = EGL14.eglCreateWindowSurface(display, configs[0], outputTexture,
                    new int[]{EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IllegalStateException("EGL surface creation failed");
            }

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
            textureLocation = GLES20.glGetAttribLocation(program, "aTexture");
            textureMatrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix");
            samplerLocation = GLES20.glGetUniformLocation(program, "uTexture");
            int[] texture = new int[1];
            GLES20.glGenTextures(1, texture, 0);
            textureId = texture[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            inputTexture = new SurfaceTexture(textureId);
            inputTexture.setOnFrameAvailableListener(this, handler);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            return new Surface(inputTexture);
        }

        void setSize(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            if (released || display == EGL14.EGL_NO_DISPLAY) {
                return;
            }
            inputTexture.updateTexImage();
            inputTexture.getTransformMatrix(textureMatrix);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            vertices.position(0);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16,
                    vertices);
            vertices.position(2);
            GLES20.glEnableVertexAttribArray(textureLocation);
            GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 16,
                    vertices);
            GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, textureMatrix, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(samplerLocation, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            EGL14.eglSwapBuffers(display, surface);
        }

        void release() {
            released = true;
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface);
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context);
                }
                EGL14.eglTerminate(display);
            }
            if (inputTexture != null) {
                inputTexture.release();
                inputTexture = null;
            }
            display = EGL14.EGL_NO_DISPLAY;
            context = EGL14.EGL_NO_CONTEXT;
            surface = EGL14.EGL_NO_SURFACE;
        }

        private static int createProgram(String vertexSource, String fragmentSource) {
            int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int result = GLES20.glCreateProgram();
            GLES20.glAttachShader(result, vertex);
            GLES20.glAttachShader(result, fragment);
            GLES20.glLinkProgram(result);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            if (linked[0] == 0) {
                String error = GLES20.glGetProgramInfoLog(result);
                GLES20.glDeleteProgram(result);
                throw new IllegalStateException("GL program link failed: " + error);
            }
            return result;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                String error = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("GL shader compile failed: " + error);
            }
            return shader;
        }
    }
}
