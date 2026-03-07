package com.kitchenboard;

import android.app.Application;
import android.util.Log;

import com.kitchenboard.update.UpdateLogger;

/**
 * Custom Application class.
 *
 * <p>Registers a global {@link Thread.UncaughtExceptionHandler} that persists the full crash
 * stack-trace to the {@link UpdateLogger} before delegating to the previous (system) handler.
 * This ensures that crash details are available in the update-log viewer even when no debugger
 * is attached.
 */
public class KitchenBoardApp extends Application {

    private static final String TAG = "KitchenBoardApp";

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashHandler();
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String message = "CRASH on thread \"" + thread.getName() + "\"";
                UpdateLogger.logError(KitchenBoardApp.this, message, throwable);
                Log.e(TAG, message, throwable);
            } catch (Exception ignored) {
                // Never let logging prevent the default crash handler from running.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }
}
