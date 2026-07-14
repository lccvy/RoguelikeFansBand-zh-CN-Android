package org.roguelikefansband.android;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/** Adds optional hold-to-repeat while preserving normal Button click/accessibility behavior. */
public final class PressRepeater {
    public static final long START_DELAY_MS = 300L;
    public static final long INTERVAL_MS = 82L;

    private PressRepeater() {
    }

    public static void bind(TouchFeedbackButton button, boolean repeat, Runnable action) {
        if (!repeat) {
            button.setOnTouchListener(null);
            button.setOnClickListener(v -> action.run());
            return;
        }
        RepeatState state = new RepeatState(button, action);
        button.setLongClickable(false);
        button.setOnTouchListener(state);
        button.setOnClickListener(v -> {
            if (state.repeated) {
                state.repeated = false;
            } else {
                action.run();
            }
        });
        button.addOnAttachStateChangeListener(state);
    }

    private static final class RepeatState implements View.OnTouchListener,
            View.OnAttachStateChangeListener, Runnable {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final TouchFeedbackButton button;
        private final Runnable action;
        private boolean active;
        private boolean repeated;

        RepeatState(TouchFeedbackButton button, Runnable action) {
            this.button = button;
            this.action = action;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancel();
                    active = true;
                    repeated = false;
                    handler.postDelayed(this, START_DELAY_MS);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    active = false;
                    handler.removeCallbacks(this);
                    break;
                default:
                    break;
            }
            return false;
        }

        @Override
        public void run() {
            if (!active || !button.isAttachedToWindow() || !button.isPressed()) return;
            action.run();
            repeated = true;
            handler.postDelayed(this, INTERVAL_MS);
        }

        private void cancel() {
            active = false;
            handler.removeCallbacks(this);
        }

        @Override public void onViewAttachedToWindow(View view) { }

        @Override
        public void onViewDetachedFromWindow(View view) {
            cancel();
        }
    }
}
