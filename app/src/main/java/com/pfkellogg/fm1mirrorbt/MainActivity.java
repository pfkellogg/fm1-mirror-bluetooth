package com.pfkellogg.fm1mirrorbt;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.Closeable;
import java.io.IOException;

/**
 * FM-1 Mirror Bluetooth: the FM-1's screen, big, over Bluetooth MIDI and nothing else. The FM-1
 * needs the Big Screen firmware that answers over Bluetooth (FM-1 Mirror + Spyglass 1.3 installs
 * it, over USB, once). Its USB stays free: another app can play the FM-1 over USB meanwhile.
 */
public class MainActivity extends Activity {
    static final String FULL_APP = "https://github.com/pfkellogg/fm1-mirror-spyglass-android";
    private static final long HELLO_EVERY = 900, QUIET = 5000, RETRY = 8000;
    private static final int TEXT = Color.WHITE, DIM = 0xffb0b0b0, BG = Color.BLACK, YELLOW = 0xffffd60a;

    private static Handler callbacks;

    /** Android's MIDI callbacks, on a thread of their own. */
    static synchronized Handler callbacks() {
        if (callbacks == null) {
            HandlerThread t = new HandlerThread("FM-1 MIDI");
            t.start();
            callbacks = new Handler(t.getLooper());
        }
        return callbacks;
    }

    private MidiManager mm;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Shared with the MIDI threads; guarded by `this`.
    private final int[] pixels = new int[Codec.SIZE * Codec.SIZE];
    private boolean changed;
    private long lastScreen;

    /** The FM-1 over Bluetooth, open both ways. */
    private static final class Link {
        final MidiDevice device;
        final MidiInputPort in;
        final MidiOutputPort out;
        final long opened = System.currentTimeMillis();

        Link(MidiDevice device, MidiInputPort in, MidiOutputPort out) {
            this.device = device;
            this.in = in;
            this.out = out;
        }

        void close() {
            closeQuietly(out);
            closeQuietly(in);
            closeQuietly(device);
        }
    }

    private volatile Link link;
    private volatile boolean running, lost, opening, scanning;
    private volatile long triedAt;

    private Codec.Look look = Codec.Look.COLOR;
    private boolean full;
    private String shown = "";

    private ScreenView screen;
    private LinearLayout bar, panel;
    private ScrollView panelScroll;
    private Button lookButton;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        mm = (MidiManager) getSystemService(Context.MIDI_SERVICE);
        prefs = getSharedPreferences("mirror", MODE_PRIVATE);
        try {
            look = Codec.Look.valueOf(prefs.getString("look", "COLOR"));
        } catch (IllegalArgumentException ignored) {
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView title = text("FM-1 Mirror Bluetooth", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        lookButton = button(look.label, v -> nextLook());
        bar.addView(lookButton);
        bar.addView(button("Full screen", v -> setFull(true)));
        root.addView(bar);

        FrameLayout stage = new FrameLayout(this);
        screen = new ScreenView(this);
        screen.setOnClickListener(v -> setFull(!full));
        stage.addView(screen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panelScroll = new ScrollView(this);
        panelScroll.setBackgroundColor(BG);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(16), dp(24), dp(24));
        panelScroll.addView(panel);
        stage.addView(panelScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        mm.registerDeviceCallback(new MidiManager.DeviceCallback() {
            @Override
            public void onDeviceRemoved(MidiDeviceInfo info) {
                Link l = link;
                if (l != null && l.device.getInfo().getId() == info.getId()) lost = true;
            }
        }, callbacks());
    }

    @Override
    protected void onStart() {
        super.onStart();
        running = true;
        triedAt = 0;
        Thread t = new Thread(this::keepLinked, "FM-1 link");
        t.setDaemon(true);
        t.start();
        main.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        running = false; // the link thread lets go of the FM-1
        main.removeCallbacks(tick);
    }

    // ---- the link: connect over Bluetooth, keep saying hello

    /** On a thread of its own: the FM-1 stops sending 3 s after the last hello. */
    private void keepLinked() {
        long helloAt = 0;
        boolean first = true;
        while (running) {
            Link l = link;
            if (l != null && lost) {
                link = null;
                l.close();
                l = null;
            }
            long now = System.currentTimeMillis();
            String address = prefs.getString("address", "");
            if (l == null && !opening && !address.isEmpty() && Ble.allowed(this) && now - triedAt >= RETRY) {
                triedAt = now;
                opening = true;
                Ble.open(this, mm, address, this::opened);
            }
            if (l != null && now - helloAt >= HELLO_EVERY) {
                helloAt = now;
                try {
                    // The whole screen the first time: someone else may be keeping the mirror on.
                    byte[] h = first ? Codec.HELLO_ALL : Codec.HELLO;
                    l.in.send(h, 0, h.length);
                    first = false;
                } catch (IOException e) {
                    lost = true;
                }
            }
            if (l == null) first = true;
            sleep(100);
        }
        Link l = link;
        link = null;
        if (l != null) l.close();
    }

    /** Android has opened the FM-1 over Bluetooth (or not: null). */
    private void opened(MidiDevice d) {
        opening = false;
        if (d == null) return;
        if (!running) {
            closeQuietly(d);
            return;
        }
        MidiInputPort in = d.openInputPort(0);
        MidiOutputPort out = d.openOutputPort(0);
        if (in == null || out == null) {
            closeQuietly(in);
            closeQuietly(out);
            closeQuietly(d);
            return;
        }
        Codec.Sysex sysex = new Codec.Sysex(this::onMessage);
        out.connect(new MidiReceiver() {
            @Override
            public void onSend(byte[] data, int offset, int count, long timestamp) {
                sysex.feed(data, offset, count);
            }
        });
        synchronized (this) {
            lastScreen = 0;
        }
        lost = false;
        link = new Link(d, in, out);
    }

    private void onMessage(byte[] m) {
        synchronized (this) {
            if (Codec.apply(pixels, m)) {
                lastScreen = System.currentTimeMillis();
                if (!changed) {
                    changed = true;
                    screen.postInvalidateOnAnimation();
                }
            }
        }
    }

    // ---- what to show

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            main.postDelayed(this, 300);
        }
    };

    private synchronized boolean screenSince(long t) { return lastScreen != 0 && lastScreen >= t; }

    /** Why there's no picture, if there isn't one. */
    private String need() {
        if (scanning) return "scanning";
        Link l = link;
        if (l == null) return prefs.getString("address", "").isEmpty() ? "find" : "looking";
        // The FM-1 only sends what changes, so a still screen is quiet: once it has come, keep it.
        if (screenSince(l.opened)) return null;
        return System.currentTimeMillis() - l.opened < QUIET ? "connecting" : "noscreen";
    }

    /** The screen, or (when there's no picture) what to do, in big type. */
    private void update() {
        String need = need();
        boolean picture = need == null;
        screen.setVisibility(picture ? View.VISIBLE : View.GONE);
        panelScroll.setVisibility(picture ? View.GONE : View.VISIBLE);
        if (picture) {
            shown = "";
            return;
        }
        if (full) setFull(false);
        if (need.equals(shown)) return;
        shown = need;
        panel.removeAllViews();
        switch (need) {
            case "find":
                heading("Find your FM-1");
                turnOn();
                row(button("Find the FM-1", v -> find()));
                break;
            case "scanning":
                heading("Looking for the FM-1…");
                body("Keep it on and close by. This takes up to 15 seconds.");
                break;
            case "looking":
                heading("Connecting to the FM-1 over Bluetooth…");
                body("It connects by itself when the FM-1 is on with its Bluetooth on. Not connecting?");
                turnOn();
                body("The FM-1 takes one Bluetooth connection at a time: close FM-1 Mirror + Spyglass, or FM-1 Mirror on a computer, if it's connected there.");
                row(button("Find the FM-1 again", v -> find()));
                break;
            case "connecting":
                heading("Connected. Waiting for the screen…");
                break;
            case "noscreen":
                heading("Connected, but no screen");
                body("The FM-1 sends its screen over Bluetooth with the Big Screen firmware from FM-1 Mirror + Spyglass 1.3 (or FM-1 Mirror 1.2) or later. Install it once, over USB, with that app: it offers \"Update FM-1\", or walks you through it if the FM-1 has M-VAVE's own firmware.");
                row(button("Get FM-1 Mirror + Spyglass", v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(FULL_APP)))));
                break;
        }
    }

    private void turnOn() {
        body("1 · On the FM-1, hold HOME and press GLO: its BLUETOOTH page. Turn K1 BLUETOOTH to ON and K3 BLE ROLE to DEVICE. It says WAITING FOR DEVICE.");
        body("2 · Turn on Bluetooth on this tablet or phone.");
    }

    // ---- finding the FM-1

    private void find() {
        if (!Ble.allowed(this)) {
            requestPermissions(Ble.permissions(), 7);
            return;
        }
        if (Ble.adapter(this) == null) {
            Toast.makeText(this, "Turn on Bluetooth on this tablet first.", Toast.LENGTH_LONG).show();
            return;
        }
        scanning = true;
        shown = "";
        Ble.scan(this, 15000, d -> {
            scanning = false;
            shown = "";
            if (d == null) {
                Toast.makeText(this, "No FM-1 found. Is its Bluetooth on, and is it close by?", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString("address", d.getAddress()).apply();
            Link l = link;
            if (l != null) lost = true; // a different one: move over
            triedAt = 0; // connect now
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 7 && Ble.allowed(this)) find();
    }

    // ---- looks and full screen

    private void nextLook() {
        Codec.Look[] all = Codec.Look.values();
        look = all[(look.ordinal() + 1) % all.length];
        lookButton.setText(look.label);
        prefs.edit().putString("look", look.name()).apply();
        synchronized (this) {
            changed = true;
        }
        screen.invalidate();
    }

    /** Full screen: only the FM-1's screen, no bar, no system bars. Tap the screen to come back. */
    private void setFull(boolean on) {
        full = on;
        bar.setVisibility(on ? View.GONE : View.VISIBLE);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            if (on) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        }
    }

    // ---- views (sizes in dp, so a big system font size can't overflow them)

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private void heading(String s) {
        TextView t = text(s, 30, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(12), 0, dp(12));
        panel.addView(t);
    }

    private void body(String s) {
        TextView t = text(s, 22, TEXT);
        t.setPadding(0, dp(6), 0, dp(6));
        panel.addView(t);
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        b.setTextColor(Color.BLACK);
        b.setBackgroundColor(YELLOW);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private void row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) r.addView(v);
        panel.addView(r);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }

    /** The FM-1's 240x240 screen, the biggest square that fits; nearest scaling keeps its letters crisp blocks. */
    private final class ScreenView extends View {
        private final Bitmap bitmap = Bitmap.createBitmap(Codec.SIZE, Codec.SIZE, Bitmap.Config.ARGB_8888);
        private final int[] argb = new int[Codec.SIZE * Codec.SIZE];
        private final Paint paint = new Paint();
        private final Rect src = new Rect(0, 0, Codec.SIZE, Codec.SIZE), dst = new Rect();

        ScreenView(Context c) {
            super(c);
            paint.setFilterBitmap(false);
            paint.setAntiAlias(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            synchronized (MainActivity.this) {
                if (changed) {
                    changed = false;
                    for (int i = 0; i < argb.length; i++) argb[i] = look.paint(pixels[i]);
                    bitmap.setPixels(argb, 0, Codec.SIZE, 0, 0, Codec.SIZE, Codec.SIZE);
                }
            }
            canvas.drawColor(BG);
            int side = Math.min(getWidth(), getHeight());
            int x = (getWidth() - side) / 2, y = (getHeight() - side) / 2;
            dst.set(x, y, x + side, y + side);
            canvas.drawBitmap(bitmap, src, dst, paint);
        }
    }
}
