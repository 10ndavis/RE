package dev.myotv.inject;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Input injector run as the shell user over the device's own ADB connection:
 *
 *   CLASSPATH=/path/to/base.apk app_process / dev.myotv.inject.InjectServer
 *
 * Shell holds INJECT_EVENTS, so it can deliver input to any app, which a normal app cannot.
 * Uses the same hidden InputManager API as scrcpy. Reads one command per line from stdin:
 *
 *   k CODE      press and release Android key code CODE
 *   d X Y       touch down at screen pixel (X, Y)
 *   m X Y       move the current touch to (X, Y)
 *   u X Y       lift the current touch at (X, Y)
 *   t X Y       tap at (X, Y)
 */
public final class InjectServer {
    private static final int INJECT_MODE_ASYNC = 0;

    private static Object inputManager;
    private static Method inject;
    private static long touchDownTime;

    public static void main(String[] args) throws Exception {
        inputManager = inputManager();
        inject = inputManager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);

        System.out.println("READY");
        System.out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            String[] parts = line.trim().split(" ");
            try {
                run(parts);
                System.out.println("OK");
            } catch (Exception e) {
                System.out.println("ERR " + line + ": " + e);
            }
            System.out.flush();
        }
    }

    private static void run(String[] parts) throws Exception {
        long now = SystemClock.uptimeMillis();
        switch (parts[0]) {
            case "k": {
                int keyCode = Integer.parseInt(parts[1]);
                injectEvent(keyEvent(now, KeyEvent.ACTION_DOWN, keyCode));
                injectEvent(keyEvent(now, KeyEvent.ACTION_UP, keyCode));
                break;
            }
            case "d":
                touchDownTime = now;
                touch(MotionEvent.ACTION_DOWN, parts, now);
                break;
            case "m":
                touch(MotionEvent.ACTION_MOVE, parts, now);
                break;
            case "u":
                touch(MotionEvent.ACTION_UP, parts, now);
                break;
            case "t":
                touchDownTime = now;
                touch(MotionEvent.ACTION_DOWN, parts, now);
                Thread.sleep(40);
                touch(MotionEvent.ACTION_UP, parts, SystemClock.uptimeMillis());
                break;
            default:
                throw new IllegalArgumentException("unknown command");
        }
    }

    private static void touch(int action, String[] parts, long time) throws Exception {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = Float.parseFloat(parts[1]);
        coords.y = Float.parseFloat(parts[2]);
        coords.pressure = action == MotionEvent.ACTION_UP ? 0f : 1f;
        coords.size = 1f;
        MotionEvent event = MotionEvent.obtain(touchDownTime, time, action, 1,
                new MotionEvent.PointerProperties[]{props}, new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        injectEvent(event);
        event.recycle();
    }

    private static void injectEvent(InputEvent event) throws Exception {
        inject.invoke(inputManager, event, INJECT_MODE_ASYNC);
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
