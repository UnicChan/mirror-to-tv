package local.lanoverlay.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.os.SystemClock;
import android.view.View;

/** Lightweight animated GIF renderer that does not require external libraries. */
@SuppressWarnings("deprecation")
final class AnimatedGifView extends View {
    private final Movie movie;
    private long animationStart;

    AnimatedGifView(Context context, Movie movie) {
        super(context);
        this.movie = movie;
        this.animationStart = SystemClock.uptimeMillis();
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    float aspectRatio() {
        int height = Math.max(1, movie.height());
        return movie.width() / (float) height;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int duration = movie.duration();
        if (duration <= 0) {
            duration = 1000;
        }
        int time = (int) ((SystemClock.uptimeMillis() - animationStart) % duration);
        movie.setTime(time);

        float sourceWidth = Math.max(1, movie.width());
        float sourceHeight = Math.max(1, movie.height());
        float scale = Math.min(getWidth() / sourceWidth, getHeight() / sourceHeight);
        float drawWidth = sourceWidth * scale;
        float drawHeight = sourceHeight * scale;
        float left = (getWidth() - drawWidth) / 2f;
        float top = (getHeight() - drawHeight) / 2f;

        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        movie.draw(canvas, 0, 0);
        canvas.restore();
        postInvalidateOnAnimation();
    }
}
