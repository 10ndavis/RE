package dev.myotv.inject;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Key injector run as the shell user over the device's own ADB connection:
 *
 *   CLASSPATH=/path/to/base.apk app_process / dev.myotv.inject.InjectServer
 *
 * Shell holds INJECT_EVENTS, so it can deliver keys to any app, which a normal app cannot.
 * Reads one Android key code per line from stdin and injects a press and release for it.
 * Uses the same hidden InputManager API as scrcpy.
 */
public final class InjectServer {
    private static final int INJECT_MODE_ASYNC = 0;

    public static void main(String[] args) throws Exception {
        Object inputManager = inputManager();
        Method inject = inputManager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);

        System.out.println("READY");
        System.out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int keyCode;
            try {
                keyCode = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("ERR bad key code: " + line);
                System.out.flush();
                continue;
            }
            long now = SystemClock.uptimeMillis();
            inject.invoke(inputManager, keyEvent(now, KeyEvent.ACTION_DOWN, keyCode), INJECT_MODE_ASYNC);
            inject.invoke(inputManager, keyEvent(now, KeyEvent.ACTION_UP, keyCode), INJECT_MODE_ASYNC);
            System.out.println("OK " + keyCode);
            System.out.flush();
        }
    }

    private static KeyEvent keyEvent(long time, int action, int keyCode) {
        return new KeyEvent(time, time, action, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
    }

    private static Object inputManager() throws Exception {
        try {
            // Android 14+ moved the injection entry point here.
            Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
            return global.getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException e) {
            Class<?> legacy = Class.forName("android.hardware.input.InputManager");
            return legacy.getMethod("getInstance").invoke(null);
        }
    }
}
