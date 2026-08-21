package local.lanoverlay.tv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.io.IOException;

/** Video renderer backed by TextureView so it behaves like a normal movable overlay layer. */
final class OverlayVideoView extends TextureView implements TextureView.SurfaceTextureListener {
    interface AspectListener {
        void onVideoAspect(float aspect);
    }

    private static final String TAG = "MirrorToTvVideo";
    private final String mediaPath;
    private final AspectListener aspectListener;
    private MediaPlayer player;
    private Surface surface;
    private boolean playWhenReady = true;
    private boolean muted;

    OverlayVideoView(Context context, String mediaPath, boolean muted,
                     AspectListener aspectListener) {
        super(context);
        this.mediaPath = mediaPath;
        this.muted = muted;
        this.aspectListener = aspectListener;
        setOpaque(true);
        setSurfaceTextureListener(this);
    }

    void startPlayback() {
        playWhenReady = true;
        if (player != null) {
            player.start();
        }
    }

    void pausePlayback() {
        playWhenReady = false;
        if (player != null && player.isPlaying()) {
            player.pause();
        }
    }

    void setMuted(boolean muted) {
        this.muted = muted;
        if (player != null) {
            float volume = muted ? 0f : 1f;
            player.setVolume(volume, volume);
        }
    }

    void releasePlayer() {
        if (player != null) {
            try {
                player.stop();
            } catch (RuntimeException ignored) { }
            player.release();
            player = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    private void openVideo(SurfaceTexture texture) {
        releasePlayer();
        surface = new Surface(texture);
        final MediaPlayer mediaPlayer = new MediaPlayer();
        player = mediaPlayer;
        try {
            mediaPlayer.setDataSource(mediaPath);
            mediaPlayer.setSurface(surface);
            mediaPlayer.setLooping(true);
            float volume = muted ? 0f : 1f;
            mediaPlayer.setVolume(volume, volume);
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer prepared) {
                    int width = prepared.getVideoWidth();
                    int height = prepared.getVideoHeight();
                    if (width > 0 && height > 0 && aspectListener != null) {
                        aspectListener.onVideoAspect(width / (float) height);
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
            Log.e(TAG, "Could not open video", error);
            releasePlayer();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        openVideo(surfaceTexture);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) { }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        releasePlayer();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) { }
}
