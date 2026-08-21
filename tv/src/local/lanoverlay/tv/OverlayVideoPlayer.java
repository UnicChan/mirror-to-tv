package local.lanoverlay.tv;

/** Common playback controls for regular and alpha-packed video renderers. */
interface OverlayVideoPlayer {
    void startPlayback();
    void pausePlayback();
    void setMuted(boolean muted);
    void releasePlayer();
}
