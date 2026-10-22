package com.cn.ipc.benchmark.andlinker;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import com.codezjx.andlinker.AndLinker;
import com.codezjx.andlinker.Call;
import com.codezjx.andlinker.Callback;
import com.codezjx.andlinker.adapter.OriginalCallAdapterFactory;
import com.codezjx.andlinker.annotation.In;
import com.codezjx.andlinker.annotation.RemoteInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit Debug-only Echo workload; all library sources retain upstream bytes. */
public final class MainActivity extends Activity implements AndLinker.BindCallback {
    private static final String TAG = "IpcEchoBench";
    private static final int COUNT = 1000;
    private static final int WARMUP = 50;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> foreground = new ArrayList<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private final AtomicInteger uniqueCallbacks = new AtomicInteger();
    private final AtomicInteger duplicateCallbacks = new AtomicInteger();
    private AndLinker linker;
    private String runId;
    private String lifecycle = "CREATED";
    private PowerManager power;
    private KeyguardManager keyguard;
    private boolean started, completed, ended;
    private boolean launched;
    private int invalidObservations, consecutiveReady;
    private String firstInvalidEvent = "null";
    private Thread worker;
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (ended) return;
            observe(started ? "SAMPLE" : "WAIT_READY", false);
            if (!started) {
                consecutiveReady = valid() ? consecutiveReady + 1 : 0;
                if (consecutiveReady >= 3) {
                    started = true;
                    observe("BEGIN", false);
                    ready.countDown();
                }
            }
            main.postDelayed(this, started ? 500 : 100);
        }
    };

    // AndLinker's wire name uses the simple interface name. The server's
    // top-level IEchoAsync returns String; the client Call adapter enqueues it.
    @RemoteInterface
    public interface IEchoAsync {
        Call<String> echo(@In String payload);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("AndLinker Debug Echo benchmark\nExplicit ipc_benchmark extra required.");
        setContentView(label);
        runId = getIntent().getStringExtra("ipc_benchmark");
        if (runId == null) return;
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0 ||
                !runId.matches("[A-Za-z0-9_.-]{1,80}")) {
            throw new IllegalArgumentException("A safe run ID and Debug APK are required");
        }
        power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        foreground.add("elapsedRealtimeMs,uptimeMs,event,lifecycle,focused,interactive,keyguardLocked,deviceLocked,valid");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AndLinker.enableLogger(false);
        linker = new AndLinker.Builder(this).packageName(getPackageName())
                .className(EchoService.class.getName())
                .addCallAdapterFactory(OriginalCallAdapterFactory.create()).build();
        linker.setBindCallback(this);
        main.post(monitor);
        linker.bind();
    }

    @Override public void onBind() {
        if (launched) return;
        launched = true;
        final IEchoSync sync = linker.create(IEchoSync.class);
        final IEchoAsync async = linker.create(IEchoAsync.class);
        final int round = getIntent().getIntExtra("ipc_round", 1);
        worker = new Thread(() -> {
            boolean success = false;
            try {
                if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Foreground readiness timeout");
                Log.i(TAG, "PASS run=" + runId + " libraryLoggerDisabled=true upstreamUnmodified=true callbackExecutor=main");
                // Match the frozen Modern baseline's payload order and run-ID parity.
                int[] sizes = new int[] {16, 1024};
                boolean[] modes = round % 2 == 1
                        ? new boolean[] {true, false} : new boolean[] {false, true};
                for (int size : sizes) {
                    char[] chars = new char[size];
                    Arrays.fill(chars, 'x');
                    String payload = new String(chars);
                    for (boolean enqueue : modes) measure(sync, async, payload, enqueue);
                }
                if (uniqueCallbacks.get() != 2 * (WARMUP + COUNT) || duplicateCallbacks.get() != 0) {
                    throw new IllegalStateException("Callback count mismatch/duplicate callback");
                }
                Log.i(TAG, "PASS run=" + runId + " uniqueCallbacks=" + uniqueCallbacks.get() + " duplicateCallbacks=0");
                success = true;
                Log.i(TAG, "DONE run=" + runId + " passed=true count=4000");
            } catch (Exception error) {
                Log.e(TAG, "FAILED run=" + runId, error);
            } finally {
                final boolean outcome = success;
                main.post(() -> end(outcome));
            }
        }, "AndLinkerEchoBench");
        worker.start();
    }

    private void measure(IEchoSync sync, IEchoAsync async, String payload, boolean enqueue) throws Exception {
        for (int i = 0; i < WARMUP; i++) check(payload, enqueue ? callAsync(async, payload) : sync.echo(payload));
        long[] samples = new long[COUNT];
        long blockStart = SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < COUNT; i++) {
            long start = SystemClock.elapsedRealtimeNanos();
            check(payload, enqueue ? callAsync(async, payload) : sync.echo(payload));
            samples[i] = SystemClock.elapsedRealtimeNanos() - start;
        }
        long blockNs = SystemClock.elapsedRealtimeNanos() - blockStart;
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        long sum = 0;
        StringBuilder csv = new StringBuilder();
        for (long sample : samples) { csv.append(sample).append('\n'); sum += sample; }
        String name = (enqueue ? "andlinker-async-" : "andlinker-") + runId + "-" + payload.length() + ".csv";
        write(name, csv.toString());
        Log.i(TAG, "RESULT mode=" + (enqueue ? "async" : "sync") + " run=" + runId +
                " size=" + payload.length() + " count=" + COUNT + " warmup=" + WARMUP +
                " failures=0 blockNs=" + blockNs + " meanMs=" + (sum / (double) COUNT / 1e6) +
                " p50Ms=" + sorted[499] / 1e6 + " p90Ms=" + sorted[899] / 1e6 +
                " p99Ms=" + sorted[989] / 1e6 + " file=" + name);
    }

    private static void check(String expected, String result) {
        if (!expected.equals(result)) throw new IllegalStateException("Echo payload mismatch");
    }

    private String callAsync(IEchoAsync service, String payload) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<String> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        service.echo(payload).enqueue(new Callback<String>() {
            private boolean first() {
                if (callbacks.incrementAndGet() != 1) { duplicateCallbacks.incrementAndGet(); return false; }
                uniqueCallbacks.incrementAndGet();
                return true;
            }
            @Override public void onResponse(Call<String> call, String response) {
                if (first()) { value.set(response); done.countDown(); }
            }
            @Override public void onFailure(Call<String> call, Throwable error) {
                if (first()) { failure.set(error); done.countDown(); }
            }
        });
        if (!done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Async echo timeout");
        if (failure.get() != null) throw new IllegalStateException("Async echo failed", failure.get());
        return value.get();
    }

    private boolean valid() {
        return lifecycle.equals("RESUMED") && hasWindowFocus() && power.isInteractive()
                && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
    }

    private void observe(String event, boolean forceInvalid) {
        if (ended || power == null) return;
        boolean valid = valid() && !forceInvalid;
        if (started && !valid) {
            invalidObservations++;
            if (firstInvalidEvent.equals("null")) firstInvalidEvent = event;
        }
        foreground.add(SystemClock.elapsedRealtime() + "," + SystemClock.uptimeMillis() + "," + event + "," + lifecycle +
                "," + hasWindowFocus() + "," + power.isInteractive() + "," + keyguard.isKeyguardLocked() +
                "," + keyguard.isDeviceLocked() + "," + valid);
    }

    private void end(boolean success) {
        if (ended) return;
        if (duplicateCallbacks.get() != 0) {
            success = false;
            Log.e(TAG, "FAILED run=" + runId + " duplicateCallbacks=" + duplicateCallbacks.get());
        }
        completed = success;
        observe("END", false);
        ended = true;
        main.removeCallbacks(monitor);
        StringBuilder rows = new StringBuilder();
        for (String row : foreground) rows.append(row).append('\n');
        try { write("foreground-" + runId + ".csv", rows.toString()); }
        catch (Exception error) { Log.e(TAG, "FAILED run=" + runId + " foregroundWrite=true", error); }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Log.i("IpcForeground", "END run=" + runId + " started=" + started + " completed=" + completed +
                " valid=" + (started && completed && invalidObservations == 0) +
                " invalidObservations=" + invalidObservations + " firstInvalidEvent=" + firstInvalidEvent +
                " file=foreground-" + runId + ".csv scope=activity_events_and_500ms_app_samples");
    }

    private void write(String name, String contents) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), name))) {
            output.write(contents.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override public void onUnBind() { if (runId != null && !ended) Log.e(TAG, "FAILED run=" + runId + " disconnected=true"); }
    @Override protected void onResume() { super.onResume(); lifecycle = "RESUMED"; observe("ON_RESUME", false); }
    @Override protected void onPause() { lifecycle = "STARTED"; observe("ON_PAUSE", true); super.onPause(); }
    @Override protected void onStop() { lifecycle = "CREATED"; observe("ON_STOP", true); super.onStop(); }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus); observe(focus ? "FOCUS_GAIN" : "FOCUS_LOSS", !focus);
    }
    @Override protected void onDestroy() {
        if (runId != null && !ended) end(false);
        main.removeCallbacks(monitor);
        if (worker != null) worker.interrupt();
        if (linker != null) linker.unbind();
        super.onDestroy();
    }
}
