package local.lanoverlay.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("deprecation")
public final class OverlayService extends Service {
    public static final String ACTION_HIDE = "local.lanoverlay.tv.HIDE";

    private static final String TAG = "MirrorToTvService";
    private static final String CHANNEL_ID = "mirror_to_tv_receiver";
    private static final int NOTIFICATION_ID = 901;
    private static final long VSYNC_FRAME_MS = 50L;
    private static final long MOTION_IDLE_MS = 5_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService connections = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();

    private volatile boolean running;
    private volatile boolean visible;
    private volatile long lastHeartbeat;
    private ServerSocket serverSocket;
    private WindowManager windowManager;
    private File currentAudioFile;
    private MediaPlayer audioPlayer;
    private volatile String currentKind = "none";
    private volatile boolean vSyncEnabled;
    private volatile int vSyncIntervalMs = 500;
    private volatile int vSyncTransitionMs = 500;
    private volatile boolean motionActive;
    private volatile boolean audioActive;
    private volatile boolean audioPlaying;
    private volatile boolean globalMuted;
    private volatile boolean videoMuted;
    private final Map<String, OverlayEntry> overlays = new ConcurrentHashMap<>();
    private volatile String currentMediaId = "none";
    private boolean smoothTargetValid;
    private boolean smoothingRequested;
    private int smoothStartX;
    private int smoothStartY;
    private int smoothStartWidth = 250;
    private int smoothStartOpacity = 100;
    private int smoothTargetX;
    private int smoothTargetY;
    private int smoothTargetWidth = 250;
    private int smoothTargetOpacity = 100;
    private long smoothSegmentStarted;
    private long lastMotionChange;

    private static final class OverlayEntry {
        final String id;
        final View view;
        final String kind;
        final File mediaFile;
        final Bitmap bitmap;
        float aspect;
        int x;
        int y;
        int width;
        int opacity;
        boolean videoMuted;

        OverlayEntry(String id, View view, String kind, File mediaFile, Bitmap bitmap,
                     float aspect, int x, int y, int width, int opacity,
                     boolean videoMuted) {
            this.id = id;
            this.view = view;
            this.kind = kind;
            this.mediaFile = mediaFile;
            this.bitmap = bitmap;
            this.aspect = aspect;
            this.x = x;
            this.y = y;
            this.width = width;
            this.opacity = opacity;
            this.videoMuted = videoMuted;
        }
    }

    private final Runnable vSyncStep = new Runnable() {
        @Override
        public void run() {
            if (!vSyncEnabled || !visible || !overlays.containsKey(currentMediaId)) {
                smoothingRequested = false;
                motionActive = false;
                return;
            }

            long now = SystemClock.elapsedRealtime();
            if (smoothingRequested) {
                float progress = Math.min(1f,
                        (now - smoothSegmentStarted) / (float) Math.max(1, vSyncTransitionMs));
                float eased = progress * progress * (3f - 2f * progress);
                moveOverlay(
                        interpolate(smoothStartX, smoothTargetX, eased),
                        interpolate(smoothStartY, smoothTargetY, eased),
                        interpolate(smoothStartWidth, smoothTargetWidth, eased),
                        interpolate(smoothStartOpacity, smoothTargetOpacity, eased));
                if (progress >= 1f) {
                    smoothingRequested = false;
                }
            }

            if (smoothingRequested) {
                mainHandler.postDelayed(this, VSYNC_FRAME_MS);
                return;
            }

            if (motionActive) {
                long idleRemaining = MOTION_IDLE_MS - (now - lastMotionChange);
                if (idleRemaining <= 0L) {
                    motionActive = false;
                } else {
                    // One sleeping callback replaces repeated polling while the target is still.
                    mainHandler.postDelayed(this, idleRemaining);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        deleteStaleCacheFiles();
        SharedPreferences audioState = getSharedPreferences("audio_state", MODE_PRIVATE);
        globalMuted = audioState.getBoolean("global_muted", false);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for the desktop panel on the local network"));
        startServer();
        watchdog.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if ((visible || audioActive)
                        && SystemClock.elapsedRealtime() - lastHeartbeat > Config.HEARTBEAT_TIMEOUT_MS) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            hideOverlay();
                            stopAudioPlayback();
                        }
                    });
                }
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            hideOverlay();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) { }
        connections.shutdownNow();
        watchdog.shutdownNow();
        hideOverlay();
        stopAudioPlayback();
        super.onDestroy();
    }

    private void startServer() {
        if (running) {
            return;
        }
        running = true;
        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ServerSocket socket = new ServerSocket();
                    socket.setReuseAddress(true);
                    // Xiaomi's firmware blocks ordinary incoming app ports. The Windows
                    // controller reaches this loopback-only socket through a temporary
                    // authenticated ADB tunnel, so the receiver is never exposed to LAN.
                    socket.bind(new InetSocketAddress("127.0.0.1", Config.PORT));
                    serverSocket = socket;
                    while (running) {
                        final Socket client = socket.accept();
                        connections.execute(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(client);
                            }
                        });
                    }
                } catch (IOException error) {
                    if (running) {
                        Log.e(TAG, "Receiver stopped", error);
                    }
                } finally {
                    running = false;
                }
            }
        }, "mirror-to-tv-server");
        serverThread.start();
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(12_000);
            if (!isPrivateAddress(client.getInetAddress())) {
                writeResponse(client.getOutputStream(), 403, "{\"ok\":false,\"error\":\"local_network_only\"}");
                return;
            }

            InputStream input = client.getInputStream();
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.length() > 4096) {
                writeResponse(client.getOutputStream(), 400, "{\"ok\":false,\"error\":\"bad_request\"}");
                return;
            }
            String[] first = requestLine.split(" ", 3);
            if (first.length < 2) {
                writeResponse(client.getOutputStream(), 400, "{\"ok\":false,\"error\":\"bad_request\"}");
                return;
            }

            Map<String, String> headers = new HashMap<>();
            String line;
            int headerBytes = requestLine.length();
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                headerBytes += line.length();
                if (headerBytes > 16_384) {
                    writeResponse(client.getOutputStream(), 431, "{\"ok\":false,\"error\":\"headers_too_large\"}");
                    return;
                }
                int separator = line.indexOf(':');
                if (separator > 0) {
                    headers.put(line.substring(0, separator).trim().toLowerCase(Locale.US),
                            line.substring(separator + 1).trim());
                }
            }

            if (!tokenMatches(headers.get("x-overlay-token"))) {
                writeResponse(client.getOutputStream(), 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
                return;
            }

            String method = first[0].toUpperCase(Locale.US);
            String target = first[1];
            String path = target;
            String query = "";
            int queryStart = target.indexOf('?');
            if (queryStart >= 0) {
                path = target.substring(0, queryStart);
                query = target.substring(queryStart + 1);
            }

            if ("GET".equals(method) && "/status".equals(path)) {
                writeResponse(client.getOutputStream(), 200,
                        "{\"ok\":true,\"visible\":" + visible + ",\"kind\":\""
                                 + currentKind + "\",\"timeoutSeconds\":30,\"vSync\":"
                                 + vSyncEnabled + ",\"vSyncIntervalMs\":" + vSyncIntervalMs
                                 + ",\"vSyncTransitionMs\":" + vSyncTransitionMs
                                 + ",\"motionActive\":" + motionActive
                                + ",\"globalMuted\":" + globalMuted
                                + ",\"videoMuted\":" + videoMuted
                                + ",\"audioActive\":" + audioActive
                                + ",\"audioPlaying\":" + audioPlaying
                                + ",\"mediaCount\":" + overlays.size()
                                + ",\"videoCount\":" + countVideoOverlays()
                                + ",\"mutedVideoCount\":" + countMutedVideoOverlays()
                                + ",\"currentMediaId\":\"" + currentMediaId + "\""
                                + ",\"x\":" + currentXPermille + ",\"y\":" + currentYPermille
                                + ",\"w\":" + currentWidthPermille + ",\"opacity\":"
                                + currentOpacity + "}");
                return;
            }
            if ("POST".equals(method) && "/ping".equals(path)) {
                if (visible || audioActive) {
                    lastHeartbeat = SystemClock.elapsedRealtime();
                }
                writeResponse(client.getOutputStream(), 200, "{\"ok\":true,\"visible\":" + visible + "}");
                return;
            }
            if ("POST".equals(method) && "/hide".equals(path)) {
                boolean hidden = runOnMainAndWait(new Runnable() {
                    @Override
                    public void run() {
                        hideOverlay();
                    }
                });
                if (!hidden) {
                    writeResponse(client.getOutputStream(), 503,
                            "{\"ok\":false,\"error\":\"hide_failed\"}");
                    return;
                }
                writeResponse(client.getOutputStream(), 200, "{\"ok\":true,\"visible\":false}");
                return;
            }
            if ("POST".equals(method) && "/delete-media".equals(path)) {
                final String mediaId = allowedMediaId(parseQuery(query).get("id"));
                runOnMainAndWait(new Runnable() {
                    @Override
                    public void run() {
                        removeOverlay(mediaId);
                    }
                });
                writeResponse(client.getOutputStream(), 200,
                        "{\"ok\":true,\"mediaCount\":" + overlays.size() + "}");
                return;
            }
            if ("POST".equals(method) && "/move".equals(path)) {
                handleMove(client.getOutputStream(), parseQuery(query));
                return;
            }
            if ("POST".equals(method) && "/vsync".equals(path)) {
                handleVSync(client.getOutputStream(), parseQuery(query));
                return;
            }
            if ("POST".equals(method) && "/motion-start".equals(path)) {
                final String mediaId = allowedMediaId(parseQuery(query).get("id"));
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (activateMedia(mediaId)) {
                            beginMotionSession();
                        }
                    }
                });
                lastHeartbeat = SystemClock.elapsedRealtime();
                writeResponse(client.getOutputStream(), 200, "{\"ok\":true}");
                return;
            }
            if ("POST".equals(method) && "/play".equals(path)) {
                final String mediaId = allowedMediaId(parseQuery(query).get("id"));
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        OverlayEntry entry = overlays.get(mediaId);
                        if (entry != null && entry.view instanceof OverlayVideoPlayer) {
                            ((OverlayVideoPlayer) entry.view).startPlayback();
                        }
                    }
                });
                lastHeartbeat = SystemClock.elapsedRealtime();
                writeResponse(client.getOutputStream(), 200, "{\"ok\":true}");
                return;
            }
            if ("POST".equals(method) && "/pause".equals(path)) {
                final String mediaId = allowedMediaId(parseQuery(query).get("id"));
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        OverlayEntry entry = overlays.get(mediaId);
                        if (entry != null && entry.view instanceof OverlayVideoPlayer) {
                            ((OverlayVideoPlayer) entry.view).pausePlayback();
                        }
                    }
                });
                lastHeartbeat = SystemClock.elapsedRealtime();
                writeResponse(client.getOutputStream(), 200, "{\"ok\":true}");
                return;
            }
            if ("POST".equals(method) && "/audio-mute".equals(path)) {
                final boolean muted = queryBoolean(parseQuery(query).get("muted"));
                runOnMainAndWait(new Runnable() {
                    @Override
                    public void run() {
                        setGlobalMuted(muted);
                    }
                });
                lastHeartbeat = SystemClock.elapsedRealtime();
                writeResponse(client.getOutputStream(), 200,
                        "{\"ok\":true,\"globalMuted\":" + globalMuted + "}");
                return;
            }
            if ("POST".equals(method) && "/video-mute".equals(path)) {
                Map<String, String> videoMuteQuery = parseQuery(query);
                final boolean muted = queryBoolean(videoMuteQuery.get("muted"));
                final String mediaId = allowedMediaId(videoMuteQuery.get("id"));
                runOnMainAndWait(new Runnable() {
                    @Override
                    public void run() {
                        setVideoMuted(mediaId, muted);
                    }
                });
                lastHeartbeat = SystemClock.elapsedRealtime();
                writeResponse(client.getOutputStream(), 200,
                        "{\"ok\":true,\"videoMuted\":" + muted + "}");
                return;
            }
            if ("POST".equals(method) && "/stop-audio".equals(path)) {
                runOnMainAndWait(new Runnable() {
                    @Override
                    public void run() {
                        stopAudioPlayback();
                    }
                });
                writeResponse(client.getOutputStream(), 200,
                        "{\"ok\":true,\"audioActive\":false}");
                return;
            }
            if ("POST".equals(method) && "/show".equals(path)) {
                handleShow(input, client.getOutputStream(), headers, parseQuery(query));
                return;
            }
            if ("POST".equals(method) && "/show-media".equals(path)) {
                handleShowMedia(input, client.getOutputStream(), headers, parseQuery(query));
                return;
            }
            if ("POST".equals(method) && "/play-audio".equals(path)) {
                handlePlayAudio(input, client.getOutputStream(), headers, parseQuery(query));
                return;
            }

            writeResponse(client.getOutputStream(), 404, "{\"ok\":false,\"error\":\"not_found\"}");
        } catch (Exception error) {
            Log.w(TAG, "Client request failed", error);
        }
    }

    private void handleShow(InputStream input, OutputStream output, Map<String, String> headers,
                            Map<String, String> query) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            writeResponse(output, 409, "{\"ok\":false,\"error\":\"overlay_permission_required\"}");
            return;
        }

        int contentLength = safeInt(headers.get("content-length"), -1);
        if (contentLength <= 0 || contentLength > Config.MAX_IMAGE_BYTES) {
            writeResponse(output, 413, "{\"ok\":false,\"error\":\"invalid_image_size\"}");
            return;
        }
        byte[] imageBytes = readExactly(input, contentLength);
        if (imageBytes == null) {
            writeResponse(output, 400, "{\"ok\":false,\"error\":\"incomplete_image\"}");
            return;
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            writeResponse(output, 415, "{\"ok\":false,\"error\":\"unsupported_image\"}");
            return;
        }
        int sample = 1;
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
            sample *= 2;
        }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        final Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, decode);
        if (bitmap == null) {
            writeResponse(output, 415, "{\"ok\":false,\"error\":\"decode_failed\"}");
            return;
        }

        final String corner = allowedCorner(query.get("corner"));
        final int widthDp = clamp(safeInt(query.get("width"), 240), 60, 900);
        final int marginDp = clamp(safeInt(query.get("margin"), 20), 0, 180);
        final boolean freePosition = query.containsKey("x") && query.containsKey("y")
                && query.containsKey("w");
        final int xPermille = clamp(safeInt(query.get("x"), 0), 0, 1000);
        final int yPermille = clamp(safeInt(query.get("y"), 0), 0, 1000);
        final int widthPermille = clamp(safeInt(query.get("w"), 250), 10, 1000);
        final int opacity = clamp(safeInt(query.get("opacity"), 100), 0, 100);
        final String mediaId = allowedMediaId(query.get("id"));
        lastHeartbeat = SystemClock.elapsedRealtime();
        boolean displayed = runOnMainAndWait(new Runnable() {
            @Override
            public void run() {
                showOverlay(mediaId, bitmap, corner, widthDp, marginDp, opacity,
                        freePosition, xPermille, yPermille, widthPermille);
            }
        });
        OverlayEntry displayedEntry = overlays.get(mediaId);
        if (!displayed || displayedEntry == null || !"image".equals(displayedEntry.kind)) {
            writeResponse(output, 503, "{\"ok\":false,\"error\":\"display_failed\"}");
            return;
        }
        writeResponse(output, 200, "{\"ok\":true,\"visible\":true,\"id\":\""
                + mediaId + "\",\"mediaCount\":" + overlays.size() + "}");
    }

    private void handleMove(OutputStream output, Map<String, String> query) throws IOException {
        final String mediaId = allowedMediaId(query.get("id"));
        if (!visible || !overlays.containsKey(mediaId)) {
            writeResponse(output, 409, "{\"ok\":false,\"error\":\"nothing_visible\"}");
            return;
        }
        final int xPermille = clamp(safeInt(query.get("x"), 0), 0, 1000);
        final int yPermille = clamp(safeInt(query.get("y"), 0), 0, 1000);
        final int widthPermille = clamp(safeInt(query.get("w"), 250), 10, 1000);
        final int opacity = clamp(safeInt(query.get("opacity"), 100), 0, 100);
        lastHeartbeat = SystemClock.elapsedRealtime();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (activateMedia(mediaId)) {
                    queueOverlayMove(xPermille, yPermille, widthPermille, opacity);
                }
            }
        });
        writeResponse(output, 200, "{\"ok\":true,\"visible\":true,\"id\":\""
                + mediaId + "\"}");
    }

    private void handleVSync(OutputStream output, Map<String, String> query) throws IOException {
        final boolean enabled = "1".equals(query.get("enabled"))
                || "true".equalsIgnoreCase(query.get("enabled"));
        final int intervalMs = clamp(safeInt(query.get("interval"), 500), 50, 10_000);
        final int transitionMs = clamp(safeInt(query.get("transition"), 500), 50, 5_000);
        boolean configured = runOnMainAndWait(new Runnable() {
            @Override
            public void run() {
                configureVSync(enabled, intervalMs, transitionMs);
            }
        });
        if (!configured) {
            writeResponse(output, 503, "{\"ok\":false,\"error\":\"vsync_config_failed\"}");
            return;
        }
        lastHeartbeat = SystemClock.elapsedRealtime();
        writeResponse(output, 200, "{\"ok\":true,\"vSync\":" + vSyncEnabled
                + ",\"vSyncIntervalMs\":" + vSyncIntervalMs
                + ",\"vSyncTransitionMs\":" + vSyncTransitionMs + "}");
    }

    private void handleShowMedia(InputStream input, OutputStream output,
                                 Map<String, String> headers,
                                 Map<String, String> query) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            writeResponse(output, 409, "{\"ok\":false,\"error\":\"overlay_permission_required\"}");
            return;
        }

        int contentLength = safeInt(headers.get("content-length"), -1);
        if (contentLength <= 0 || contentLength > Config.MAX_MEDIA_BYTES) {
            writeResponse(output, 413, "{\"ok\":false,\"error\":\"invalid_media_size\"}");
            return;
        }

        final String mediaType = "gif".equals(query.get("type")) ? "gif" : "video";
        String extension = allowedMediaExtension(query.get("ext"), mediaType);
        final File mediaFile = File.createTempFile("mirror-to-tv-", "." + extension, getCacheDir());
        boolean copied;
        try (FileOutputStream fileOutput = new FileOutputStream(mediaFile)) {
            copied = copyExactly(input, fileOutput, contentLength);
        } catch (IOException error) {
            mediaFile.delete();
            throw error;
        }
        if (!copied) {
            mediaFile.delete();
            writeResponse(output, 400, "{\"ok\":false,\"error\":\"incomplete_media\"}");
            return;
        }

        final Movie movie;
        final float aspect;
        if ("gif".equals(mediaType)) {
            movie = Movie.decodeFile(mediaFile.getAbsolutePath());
            if (movie == null || movie.width() <= 0 || movie.height() <= 0) {
                mediaFile.delete();
                writeResponse(output, 415, "{\"ok\":false,\"error\":\"gif_decode_failed\"}");
                return;
            }
            aspect = movie.width() / (float) movie.height();
        } else {
            movie = null;
            int aspectPermille = clamp(safeInt(query.get("aspect"), 1778), 250, 4000);
            aspect = aspectPermille / 1000f;
        }

        final int xPermille = clamp(safeInt(query.get("x"), 0), 0, 1000);
        final int yPermille = clamp(safeInt(query.get("y"), 0), 0, 1000);
        final int widthPermille = clamp(safeInt(query.get("w"), 250), 10, 1000);
        final int opacity = clamp(safeInt(query.get("opacity"), 100), 0, 100);
        final boolean requestedVideoMuted = queryBoolean(query.get("videoMuted"));
        final boolean alphaPacked = queryBoolean(query.get("alphaPacked"));
        final String mediaId = allowedMediaId(query.get("id"));
        lastHeartbeat = SystemClock.elapsedRealtime();
        boolean displayed = runOnMainAndWait(new Runnable() {
            @Override
            public void run() {
                if ("gif".equals(mediaType)) {
                    showGifOverlay(mediaId, movie, mediaFile, xPermille, yPermille,
                            widthPermille, opacity);
                } else {
                    showVideoOverlay(mediaId, mediaFile, aspect, xPermille, yPermille,
                            widthPermille, opacity, requestedVideoMuted, alphaPacked);
                }
            }
        });
        OverlayEntry displayedEntry = overlays.get(mediaId);
        if (!displayed || displayedEntry == null || !mediaType.equals(displayedEntry.kind)) {
            writeResponse(output, 503, "{\"ok\":false,\"error\":\"display_failed\"}");
            return;
        }
        writeResponse(output, 200, "{\"ok\":true,\"visible\":true,\"kind\":\""
                + mediaType + "\",\"id\":\"" + mediaId + "\",\"mediaCount\":"
                + overlays.size() + "}");
    }

    private void handlePlayAudio(InputStream input, OutputStream output,
                                 Map<String, String> headers,
                                 Map<String, String> query) throws IOException {
        int contentLength = safeInt(headers.get("content-length"), -1);
        if (contentLength <= 0 || contentLength > Config.MAX_MEDIA_BYTES) {
            writeResponse(output, 413, "{\"ok\":false,\"error\":\"invalid_audio_size\"}");
            return;
        }

        String extension = allowedAudioExtension(query.get("ext"));
        final File audioFile = File.createTempFile("mirror-to-tv-audio-", "." + extension,
                getCacheDir());
        boolean copied;
        try (FileOutputStream fileOutput = new FileOutputStream(audioFile)) {
            copied = copyExactly(input, fileOutput, contentLength);
        } catch (IOException error) {
            audioFile.delete();
            throw error;
        }
        if (!copied) {
            audioFile.delete();
            writeResponse(output, 400, "{\"ok\":false,\"error\":\"incomplete_audio\"}");
            return;
        }

        boolean started = runOnMainAndWait(new Runnable() {
            @Override
            public void run() {
                playAudioFile(audioFile);
            }
        });
        if (!started || currentAudioFile != audioFile || audioPlayer == null) {
            audioFile.delete();
            writeResponse(output, 503, "{\"ok\":false,\"error\":\"audio_start_failed\"}");
            return;
        }
        lastHeartbeat = SystemClock.elapsedRealtime();
        writeResponse(output, 200, "{\"ok\":true,\"audioActive\":true,\"globalMuted\":"
                + globalMuted + "}");
    }

    private void playAudioFile(final File audioFile) {
        stopAudioPlayback();
        final MediaPlayer player = new MediaPlayer();
        currentAudioFile = audioFile;
        audioPlayer = player;
        audioActive = true;
        audioPlaying = false;
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(audioFile.getAbsolutePath());
            player.setLooping(false);
            applyAudioVolume();
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer prepared) {
                    if (audioPlayer != prepared) {
                        prepared.release();
                        return;
                    }
                    applyAudioVolume();
                    prepared.start();
                    audioPlaying = true;
                    lastHeartbeat = SystemClock.elapsedRealtime();
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer completed) {
                    if (audioPlayer == completed) {
                        stopAudioPlayback();
                    }
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer failed, int what, int extra) {
                    Log.e(TAG, "Audio playback error: " + what + "/" + extra);
                    if (audioPlayer == failed) {
                        stopAudioPlayback();
                    }
                    return true;
                }
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "Could not open audio", error);
            stopAudioPlayback();
        }
    }

    private void stopAudioPlayback() {
        audioActive = false;
        audioPlaying = false;
        if (audioPlayer != null) {
            try {
                audioPlayer.stop();
            } catch (RuntimeException ignored) { }
            audioPlayer.release();
            audioPlayer = null;
        }
        if (currentAudioFile != null) {
            currentAudioFile.delete();
            currentAudioFile = null;
        }
    }

    private void deleteStaleCacheFiles() {
        File[] cachedFiles = getCacheDir().listFiles();
        if (cachedFiles == null) {
            return;
        }
        for (File cachedFile : cachedFiles) {
            String name = cachedFile.getName();
            if (name.startsWith("mirror-to-tv-") || name.startsWith("lan-overlay-")) {
                cachedFile.delete();
            }
        }
    }

    private void applyAudioVolume() {
        if (audioPlayer != null) {
            float volume = globalMuted ? 0f : 1f;
            audioPlayer.setVolume(volume, volume);
        }
    }

    private void setGlobalMuted(boolean muted) {
        globalMuted = muted;
        getSharedPreferences("audio_state", MODE_PRIVATE).edit()
                .putBoolean("global_muted", muted).apply();
        applyAudioVolume();
        applyVideoVolume();
    }

    private void setVideoMuted(String mediaId, boolean muted) {
        OverlayEntry entry = overlays.get(mediaId);
        if (entry == null || !(entry.view instanceof OverlayVideoPlayer)) {
            return;
        }
        entry.videoMuted = muted;
        ((OverlayVideoPlayer) entry.view).setMuted(globalMuted || muted);
        if (mediaId.equals(currentMediaId)) {
            videoMuted = muted;
        }
    }

    private void applyVideoVolume() {
        for (OverlayEntry entry : overlays.values()) {
            if (entry.view instanceof OverlayVideoPlayer) {
                ((OverlayVideoPlayer) entry.view).setMuted(globalMuted || entry.videoMuted);
            }
        }
    }

    private int countVideoOverlays() {
        int count = 0;
        for (OverlayEntry entry : overlays.values()) {
            if (entry.view instanceof OverlayVideoPlayer) {
                count++;
            }
        }
        return count;
    }

    private int countMutedVideoOverlays() {
        int count = 0;
        for (OverlayEntry entry : overlays.values()) {
            if (entry.view instanceof OverlayVideoPlayer && entry.videoMuted) {
                count++;
            }
        }
        return count;
    }

    private boolean runOnMainAndWait(final Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
            return true;
        }
        final CountDownLatch completed = new CountDownLatch(1);
        if (!mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    action.run();
                } finally {
                    completed.countDown();
                }
            }
        })) {
            return false;
        }
        try {
            return completed.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void showOverlay(String mediaId, Bitmap bitmap, String corner, int widthDp,
                             int marginDp, int opacity,
                             boolean freePosition, int xPermille, int yPermille,
                             int widthPermille) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            bitmap.recycle();
            return;
        }
        ImageView image = new ImageView(this);
        image.setBackgroundColor(Color.TRANSPARENT);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        float aspect = bitmap.getWidth() / (float) Math.max(1, bitmap.getHeight());

        if (!freePosition) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            widthPermille = clamp(Math.round(dp(widthDp) * 1000f / metrics.widthPixels), 10, 1000);
            if ("top-left".equals(corner)) {
                xPermille = Math.round(dp(marginDp) * 1000f / metrics.widthPixels);
                yPermille = Math.round(dp(marginDp) * 1000f / metrics.heightPixels);
            } else if ("bottom-left".equals(corner)) {
                xPermille = Math.round(dp(marginDp) * 1000f / metrics.widthPixels);
                yPermille = 1000;
            } else if ("bottom-right".equals(corner)) {
                xPermille = 1000;
                yPermille = 1000;
            } else {
                xPermille = 1000;
                yPermille = Math.round(dp(marginDp) * 1000f / metrics.heightPixels);
            }
        }
        addOverlayView(mediaId, image, aspect, null, bitmap, "image", false,
                xPermille, yPermille, widthPermille, opacity);
    }

    private void showGifOverlay(String mediaId, Movie movie, File mediaFile,
                                int xPermille, int yPermille,
                                int widthPermille, int opacity) {
        AnimatedGifView gif = new AnimatedGifView(this, movie);
        addOverlayView(mediaId, gif, gif.aspectRatio(), mediaFile, null, "gif", false,
                xPermille, yPermille, widthPermille, opacity);
    }

    private void showVideoOverlay(final String mediaId, final File mediaFile, float aspect,
                                  int xPermille, int yPermille, int widthPermille, int opacity,
                                  final boolean requestedVideoMuted,
                                  boolean alphaPacked) {
        final View[] videoHolder = new View[1];
        OverlayVideoView.AspectListener listener = new OverlayVideoView.AspectListener() {
            @Override
            public void onVideoAspect(float videoAspect) {
                OverlayEntry entry = overlays.get(mediaId);
                if (videoAspect > 0f && entry != null && entry.view == videoHolder[0]) {
                    entry.aspect = videoAspect;
                    moveEntry(entry, entry.x, entry.y, entry.width, entry.opacity);
                }
            }
        };
        if (alphaPacked) {
            videoHolder[0] = new PackedAlphaVideoView(this, mediaFile.getAbsolutePath(),
                    globalMuted || requestedVideoMuted, listener);
        } else {
            videoHolder[0] = new OverlayVideoView(this, mediaFile.getAbsolutePath(),
                    globalMuted || requestedVideoMuted, listener);
        }
        addOverlayView(mediaId, videoHolder[0], aspect, mediaFile, null, "video",
                requestedVideoMuted,
                xPermille, yPermille, widthPermille, opacity);
    }

    private volatile int currentXPermille;
    private volatile int currentYPermille;
    private volatile int currentWidthPermille = 250;
    private volatile int currentOpacity = 100;

    private void addOverlayView(String mediaId, View view, float aspect, File mediaFile,
                                Bitmap bitmap, String kind, boolean requestedVideoMuted,
                                int xPermille, int yPermille, int widthPermille, int opacity) {
        removeOverlay(mediaId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (mediaFile != null) {
                mediaFile.delete();
            }
            return;
        }

        float safeAspect = Math.max(0.05f, aspect);
        WindowManager.LayoutParams params = buildFreeLayout(safeAspect,
                xPermille, yPermille, widthPermille);
        view.setAlpha(opacity / 100f);

        try {
            windowManager.addView(view, params);
            OverlayEntry entry = new OverlayEntry(mediaId, view, kind, mediaFile, bitmap,
                    safeAspect, xPermille, yPermille, widthPermille, opacity,
                    requestedVideoMuted);
            overlays.put(mediaId, entry);
            activateEntry(entry);
            visible = true;
            lastHeartbeat = SystemClock.elapsedRealtime();
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not display overlay", error);
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (mediaFile != null) {
                mediaFile.delete();
            }
        }
    }

    private WindowManager.LayoutParams buildFreeLayout(float aspect, int xPermille,
                                                       int yPermille, int widthPermille) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = Math.round(metrics.widthPixels * (widthPermille / 1000f));
        width = Math.max(1, Math.min(width, metrics.widthPixels));
        int height = Math.max(1, Math.round(width / Math.max(0.05f, aspect)));
        int maxHeight = metrics.heightPixels;
        if (height > maxHeight) {
            height = maxHeight;
            width = Math.max(1, Math.round(height * aspect));
        }

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = clamp(Math.round(metrics.widthPixels * (xPermille / 1000f)),
                0, Math.max(0, metrics.widthPixels - width));
        params.y = clamp(Math.round(metrics.heightPixels * (yPermille / 1000f)),
                0, Math.max(0, metrics.heightPixels - height));
        params.setTitle("mirror-to-tv");
        return params;
    }

    private void configureVSync(boolean enabled, int intervalMs, int transitionMs) {
        mainHandler.removeCallbacks(vSyncStep);
        vSyncIntervalMs = clamp(intervalMs, 50, 10_000);
        vSyncTransitionMs = clamp(transitionMs, 50, 5_000);
        if (!enabled && smoothTargetValid && visible) {
            moveOverlay(smoothTargetX, smoothTargetY, smoothTargetWidth, smoothTargetOpacity);
        }
        vSyncEnabled = enabled;
        motionActive = false;
        smoothingRequested = false;
        smoothTargetValid = false;
        if (enabled) {
            smoothTargetX = currentXPermille;
            smoothTargetY = currentYPermille;
            smoothTargetWidth = currentWidthPermille;
            smoothTargetOpacity = currentOpacity;
            smoothTargetValid = true;
        }
    }

    private void beginMotionSession() {
        if (!vSyncEnabled || !visible) {
            return;
        }
        motionActive = true;
        lastMotionChange = SystemClock.elapsedRealtime();
        mainHandler.removeCallbacks(vSyncStep);
        if (smoothingRequested) {
            mainHandler.post(vSyncStep);
        } else {
            mainHandler.postDelayed(vSyncStep, MOTION_IDLE_MS);
        }
    }

    private void queueOverlayMove(int xPermille, int yPermille, int widthPermille, int opacity) {
        if (!vSyncEnabled) {
            moveOverlay(xPermille, yPermille, widthPermille, opacity);
            return;
        }

        boolean changed = !smoothTargetValid
                || smoothTargetX != xPermille
                || smoothTargetY != yPermille
                || smoothTargetWidth != widthPermille
                || smoothTargetOpacity != opacity;
        if (!changed) {
            if (!motionActive) {
                beginMotionSession();
            }
            return;
        }

        long now = SystemClock.elapsedRealtime();
        smoothStartX = currentXPermille;
        smoothStartY = currentYPermille;
        smoothStartWidth = currentWidthPermille;
        smoothStartOpacity = currentOpacity;
        smoothTargetX = xPermille;
        smoothTargetY = yPermille;
        smoothTargetWidth = widthPermille;
        smoothTargetOpacity = opacity;
        smoothTargetValid = true;
        smoothSegmentStarted = now;
        lastMotionChange = now;
        motionActive = true;
        smoothingRequested = true;
        mainHandler.removeCallbacks(vSyncStep);
        mainHandler.post(vSyncStep);
    }

    private static int interpolate(int start, int end, float progress) {
        return Math.round(start + ((end - start) * progress));
    }

    private void moveOverlay(int xPermille, int yPermille, int widthPermille, int opacity) {
        OverlayEntry entry = overlays.get(currentMediaId);
        if (entry == null) {
            return;
        }
        moveEntry(entry, xPermille, yPermille, widthPermille, opacity);
    }

    private void moveEntry(OverlayEntry entry, int xPermille, int yPermille,
                           int widthPermille, int opacity) {
        if (entry == null || !overlays.containsKey(entry.id)) {
            return;
        }
        entry.x = xPermille;
        entry.y = yPermille;
        entry.width = widthPermille;
        entry.opacity = opacity;
        WindowManager.LayoutParams params = buildFreeLayout(entry.aspect,
                xPermille, yPermille, widthPermille);
        entry.view.setAlpha(opacity / 100f);
        try {
            windowManager.updateViewLayout(entry.view, params);
            if (entry.id.equals(currentMediaId)) {
                currentXPermille = xPermille;
                currentYPermille = yPermille;
                currentWidthPermille = widthPermille;
                currentOpacity = opacity;
            }
            lastHeartbeat = SystemClock.elapsedRealtime();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not move overlay", error);
        }
    }

    private boolean activateMedia(String mediaId) {
        OverlayEntry entry = overlays.get(mediaId);
        if (entry == null) {
            return false;
        }
        activateEntry(entry);
        return true;
    }

    private void activateEntry(OverlayEntry entry) {
        if (entry == null) {
            currentKind = "none";
            currentMediaId = "none";
            currentXPermille = 0;
            currentYPermille = 0;
            currentWidthPermille = 250;
            currentOpacity = 100;
            videoMuted = false;
            return;
        }
        if (!entry.id.equals(currentMediaId)) {
            mainHandler.removeCallbacks(vSyncStep);
            motionActive = false;
            smoothingRequested = false;
            smoothTargetValid = false;
        }
        currentKind = entry.kind;
        currentMediaId = entry.id;
        currentXPermille = entry.x;
        currentYPermille = entry.y;
        currentWidthPermille = entry.width;
        currentOpacity = entry.opacity;
        videoMuted = entry.videoMuted;
    }

    private void releaseEntry(OverlayEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.view instanceof OverlayVideoPlayer) {
            try {
                ((OverlayVideoPlayer) entry.view).releasePlayer();
            } catch (RuntimeException ignored) { }
        }
        try {
            windowManager.removeView(entry.view);
        } catch (RuntimeException ignored) { }
        if (entry.bitmap != null && !entry.bitmap.isRecycled()) {
            entry.bitmap.recycle();
        }
        if (entry.mediaFile != null) {
            entry.mediaFile.delete();
        }
    }

    private void removeOverlay(String mediaId) {
        OverlayEntry removed = overlays.remove(mediaId);
        if (removed == null) {
            return;
        }
        boolean removedActive = mediaId.equals(currentMediaId);
        releaseEntry(removed);
        if (removedActive) {
            mainHandler.removeCallbacks(vSyncStep);
            motionActive = false;
            smoothingRequested = false;
            smoothTargetValid = false;
            OverlayEntry next = null;
            for (OverlayEntry candidate : overlays.values()) {
                next = candidate;
                break;
            }
            activateEntry(next);
        }
        visible = !overlays.isEmpty();
    }

    private void hideOverlay() {
        visible = false;
        mainHandler.removeCallbacks(vSyncStep);
        motionActive = false;
        smoothingRequested = false;
        smoothTargetValid = false;
        for (String mediaId : new ArrayList<>(overlays.keySet())) {
            OverlayEntry entry = overlays.remove(mediaId);
            releaseEntry(entry);
        }
        activateEntry(null);
    }

    private String allowedCorner(String corner) {
        if ("top-left".equals(corner) || "bottom-left".equals(corner)
                || "bottom-right".equals(corner)) {
            return corner;
        }
        return "top-right";
    }

    private String allowedMediaId(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) {
            return "primary";
        }
        StringBuilder safe = new StringBuilder();
        int limit = Math.min(mediaId.length(), 64);
        for (int index = 0; index < limit; index++) {
            char value = mediaId.charAt(index);
            if ((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9') || value == '-' || value == '_') {
                safe.append(value);
            }
        }
        return safe.length() == 0 ? "primary" : safe.toString();
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            try {
                if (separator >= 0) {
                    result.put(URLDecoder.decode(pair.substring(0, separator), "UTF-8"),
                            URLDecoder.decode(pair.substring(separator + 1), "UTF-8"));
                }
            } catch (Exception ignored) { }
        }
        return result;
    }

    private boolean isPrivateAddress(InetAddress address) {
        return address != null && (address.isSiteLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress());
    }

    private boolean tokenMatches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                Config.TOKEN.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = Math.max(0, bytes.length - 1);
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            buffer.write(current);
            previous = current;
            if (buffer.size() > 16_384) {
                throw new IOException("Line too long");
            }
        }
        return buffer.size() == 0 ? null : buffer.toString("ISO-8859-1");
    }

    private byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read < 0) {
                return null;
            }
            offset += read;
        }
        return data;
    }

    private boolean copyExactly(InputStream input, OutputStream output, int length) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                return false;
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
        output.flush();
        return true;
    }

    private String allowedMediaExtension(String extension, String mediaType) {
        if ("gif".equals(mediaType)) {
            return "gif";
        }
        if (extension == null) {
            return "mp4";
        }
        String normalized = extension.toLowerCase(Locale.US);
        if ("mp4".equals(normalized) || "m4v".equals(normalized)
                || "webm".equals(normalized) || "mkv".equals(normalized)
                || "3gp".equals(normalized)) {
            return normalized;
        }
        return "mp4";
    }

    private String allowedAudioExtension(String extension) {
        if (extension == null) {
            return "mp3";
        }
        String normalized = extension.toLowerCase(Locale.US);
        if ("mp3".equals(normalized) || "m4a".equals(normalized)
                || "aac".equals(normalized) || "ogg".equals(normalized)
                || "wav".equals(normalized) || "flac".equals(normalized)) {
            return normalized;
        }
        return "mp3";
    }

    private boolean queryBoolean(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }

    private void writeResponse(OutputStream output, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : "Error";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private int safeInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "mirror-to-tv",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Local media overlay receiver");
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String message) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("mirror-to-tv")
                .setContentText(message)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
