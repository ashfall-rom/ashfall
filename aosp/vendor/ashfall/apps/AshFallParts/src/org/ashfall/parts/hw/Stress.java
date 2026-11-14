/*
 * Copyright (C) 2026 The AshFall Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ashfall.parts.hw;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The curve-editor stress test (docs/phase5.md §6.3).
 *
 * <p>CPU load: one worker thread per (up to eight) core, each running a busy
 * loop with a duty cycle equal to the requested percentage.  GPU load: an
 * offscreen 1920x1080 EGL pbuffer that renders several full-screen passes of a
 * deliberately expensive fragment shader per submission with
 * {@code glFinish()} and the same duty cycle.
 *
 * <p>The GPU workload has to be GPU-bound, not submission-bound: a single
 * 512x512 draw finished in well under a millisecond, the GPU starved between
 * the {@code glFinish()} and the next draw (~82 % busy) and the
 * {@code msm-adreno-tz} governor stayed at 587 MHz instead of ramping to the
 * 940 MHz cap (#118).  The passes of one submission are queued back-to-back
 * (one {@code glFinish()} for the whole group), the render target matches the
 * panel and the shader runs a long dependent sin/cos chain, so the GPU, not
 * the EGL/driver submission thread, is the bottleneck.
 *
 * <p>A session always stops itself after ten minutes and is stopped by the
 * editor when it leaves the foreground.  Nothing here touches the daemons; the
 * load is ordinary app work, so the fan curves can be observed under it.
 */
public final class Stress {

    public static final long MAX_RUN_MS = 10 * 60 * 1000L;

    private static final String TAG = "AshFallParts";
    private static final int MAX_CPU_THREADS = 8;
    /** Preferred offscreen render target; clamped to the pbuffer limit. */
    private static final int GPU_TARGET_WIDTH = 1920;
    private static final int GPU_TARGET_HEIGHT = 1080;
    /** Full-screen passes queued before the single {@code glFinish()}. */
    private static final int GPU_PASSES = 4;
    /**
     * Iterations of the fragment shader's dependent sin/cos chain.  Keep in
     * step with the loop bound in {@link GpuWorker#buildProgram()}.
     */
    private static final int GPU_SHADER_ITERATIONS = 128;
    /** How often the GPU worker reports its frame load to logcat. */
    private static final long GPU_LOG_INTERVAL_NS = 5 * 1000 * 1000 * 1000L;
    private static final long CPU_WINDOW_NS = 20 * 1000 * 1000L;

    private static final Stress sInstance = new Stress();

    private final Object mLock = new Object();
    private final List<CpuWorker> mCpuWorkers = new ArrayList<>();
    private GpuWorker mGpuWorker;
    private volatile int mCpuPct;
    private volatile int mGpuPct;
    private volatile boolean mGpuFailed;
    private boolean mSession;
    private long mDeadline;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mAutoStop = this::stop;

    private Stress() {
    }

    public static Stress get() {
        return sInstance;
    }

    public int cpuPct() {
        return mCpuPct;
    }

    public int gpuPct() {
        return mGpuPct;
    }

    public boolean isRunning() {
        return mCpuPct > 0 || mGpuPct > 0;
    }

    /** False when the device could not create the offscreen GL context. */
    public boolean gpuAvailable() {
        return !mGpuFailed;
    }

    /** Milliseconds until the automatic ten-minute stop, or 0 when idle. */
    public long remainingMs() {
        if (!isRunning()) {
            return 0;
        }
        return Math.max(0L, mDeadline - SystemClock.elapsedRealtime());
    }

    /** Applies new slider levels; 0/0 stops the load but keeps the session. */
    public void setLevels(int cpuPct, int gpuPct) {
        final int cpu = clamp(cpuPct);
        final int gpu = clamp(gpuPct);
        synchronized (mLock) {
            mCpuPct = cpu;
            mGpuPct = gpu;
            if (cpu > 0 || gpu > 0) {
                if (!mSession) {
                    startSession();
                }
                if (cpu > 0) {
                    ensureCpuWorkers();
                }
                if (gpu > 0) {
                    ensureGpuWorker();
                }
            }
        }
    }

    /** Stops all load and releases the EGL context. */
    public void stop() {
        final List<CpuWorker> cpuWorkers;
        final GpuWorker gpuWorker;
        synchronized (mLock) {
            mCpuPct = 0;
            mGpuPct = 0;
            mSession = false;
            mDeadline = 0;
            mGpuFailed = false;
            cpuWorkers = new ArrayList<>(mCpuWorkers);
            mCpuWorkers.clear();
            gpuWorker = mGpuWorker;
            mGpuWorker = null;
        }
        mHandler.removeCallbacks(mAutoStop);
        for (CpuWorker worker : cpuWorkers) {
            worker.shutdown();
        }
        if (gpuWorker != null) {
            gpuWorker.shutdown();
        }
    }

    private void startSession() {
        mSession = true;
        mDeadline = SystemClock.elapsedRealtime() + MAX_RUN_MS;
        mHandler.removeCallbacks(mAutoStop);
        mHandler.postDelayed(mAutoStop, MAX_RUN_MS);
    }

    private void ensureCpuWorkers() {
        if (!mCpuWorkers.isEmpty()) {
            return;
        }
        final int cores = Math.min(MAX_CPU_THREADS,
                Math.max(1, Runtime.getRuntime().availableProcessors()));
        for (int i = 0; i < cores; i++) {
            final CpuWorker worker = new CpuWorker(this);
            worker.start();
            mCpuWorkers.add(worker);
        }
    }

    private void ensureGpuWorker() {
        if (mGpuFailed) {
            return;
        }
        if (mGpuWorker != null && mGpuWorker.isAlive()) {
            // A previous worker may still be tearing its EGL context down;
            // never run two contexts at once.
            return;
        }
        final GpuWorker worker = new GpuWorker(this);
        worker.start();
        mGpuWorker = worker;
    }

    private static int clamp(int pct) {
        return Math.max(0, Math.min(100, pct));
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepNanosQuietly(long nanos) {
        if (nanos <= 0) {
            return;
        }
        final long millis = nanos / 1_000_000L;
        final int rest = (int) (nanos % 1_000_000L);
        try {
            Thread.sleep(millis, rest);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class CpuWorker extends Thread {
        private final Stress mStress;
        private volatile boolean mRunning = true;

        CpuWorker(Stress stress) {
            super("ashfall-stress-cpu");
            mStress = stress;
            setDaemon(true);
        }

        void shutdown() {
            mRunning = false;
            interrupt();
        }

        @Override
        public void run() {
            long acc = 0x9E3779B97F4A7C15L;
            while (mRunning) {
                final int pct = mStress.mCpuPct;
                if (pct <= 0) {
                    sleepQuietly(50);
                    continue;
                }
                final long start = System.nanoTime();
                final long busyUntil = start + CPU_WINDOW_NS;
                while (System.nanoTime() < busyUntil && mRunning) {
                    for (int i = 0; i < 4096; i++) {
                        acc ^= acc << 13;
                        acc ^= acc >>> 7;
                        acc ^= acc << 17;
                        acc += i;
                    }
                }
                // Keep the accumulator live so the loop cannot be optimised away.
                mSink = acc;
                if (pct >= 100) {
                    continue;
                }
                final long busy = System.nanoTime() - start;
                sleepNanosQuietly(busy * (100L - pct) / pct);
            }
        }
    }

    private static final class GpuWorker extends Thread {
        private final Stress mStress;
        private volatile boolean mRunning = true;

        GpuWorker(Stress stress) {
            super("ashfall-stress-gpu");
            mStress = stress;
            setDaemon(true);
        }

        void shutdown() {
            mRunning = false;
            interrupt();
        }

        @Override
        public void run() {
            EGLDisplay display = EGL14.EGL_NO_DISPLAY;
            EGLContext context = EGL14.EGL_NO_CONTEXT;
            EGLSurface surface = EGL14.EGL_NO_SURFACE;
            boolean ready = false;
            try {
                // The CPU workers stay at the default app priority.  Raise the
                // GL submission thread above them so a 100 % CPU slider cannot
                // leave the GPU waiting for the next draw; display priority is
                // still a normal (preemptible) app-render priority.
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                } catch (SecurityException | IllegalArgumentException e) {
                    Log.w(TAG, "GPU stress: could not raise thread priority", e);
                }
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                if (display == EGL14.EGL_NO_DISPLAY) {
                    return;
                }
                final int[] version = new int[2];
                if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                    display = EGL14.EGL_NO_DISPLAY;
                    return;
                }
                final int[] configAttribs = {
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_NONE,
                };
                final EGLConfig[] configs = new EGLConfig[1];
                final int[] count = new int[1];
                if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0)
                        || count[0] < 1) {
                    return;
                }
                // Drivers cap the pbuffer size; stay inside the config limit.
                int width = limit(GPU_TARGET_WIDTH,
                        configAttrib(display, configs[0], EGL14.EGL_MAX_PBUFFER_WIDTH));
                int height = limit(GPU_TARGET_HEIGHT,
                        configAttrib(display, configs[0], EGL14.EGL_MAX_PBUFFER_HEIGHT));
                surface = createPbuffer(display, configs[0], width, height);
                if (surface == EGL14.EGL_NO_SURFACE) {
                    // Memory pressure on a small pbuffer heap: retry smaller
                    // once before giving up on GPU load.
                    width = Math.max(640, width / 2);
                    height = Math.max(360, height / 2);
                    Log.w(TAG, "GPU stress: pbuffer retry at " + width + "x" + height);
                    surface = createPbuffer(display, configs[0], width, height);
                }
                if (surface == EGL14.EGL_NO_SURFACE) {
                    Log.w(TAG, "GPU stress: no pbuffer surface");
                    return;
                }
                final int[] contextAttribs = {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                        EGL14.EGL_NONE,
                };
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                        contextAttribs, 0);
                if (context == EGL14.EGL_NO_CONTEXT
                        || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    return;
                }
                final int program = buildProgram();
                if (program == 0) {
                    return;
                }
                final FloatBuffer vertices = ByteBuffer.allocateDirect(6 * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                vertices.put(new float[] {-1f, -1f, 3f, -1f, -1f, 3f}).position(0);
                final int position = GLES20.glGetAttribLocation(program, "aPosition");
                final int time = GLES20.glGetUniformLocation(program, "uTime");
                final int pass = GLES20.glGetUniformLocation(program, "uPass");
                GLES20.glUseProgram(program);
                GLES20.glEnableVertexAttribArray(position);
                GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
                GLES20.glViewport(0, 0, width, height);
                ready = true;
                // Diagnostics for the device test: the render size, the number
                // of queued passes and the renderer are what confirm that the
                // load is GPU-bound rather than submission-bound.
                Log.i(TAG, "GPU stress: " + width + "x" + height + " pbuffer, "
                        + GPU_PASSES + " passes, " + GPU_SHADER_ITERATIONS
                        + " shader iterations, renderer "
                        + GLES20.glGetString(GLES20.GL_RENDERER));

                long logFrames = 0;
                long logBusyNs = 0;
                long logSubmitNs = 0;
                long logSince = System.nanoTime();
                int logPct = -1;
                while (mRunning) {
                    final int pct = mStress.mGpuPct;
                    if (pct <= 0) {
                        logFrameLoad(logPct, logFrames, logBusyNs, logSubmitNs);
                        logFrames = 0;
                        logBusyNs = 0;
                        logSubmitNs = 0;
                        sleepQuietly(50);
                        continue;
                    }
                    final long start = System.nanoTime();
                    GLES20.glUniform1f(time, (start % 1_000_000_000L) / 1_000_000f);
                    // Queue the whole group before waiting: the GPU stays busy
                    // across the passes instead of idling between submissions.
                    for (int i = 0; i < GPU_PASSES && mRunning; i++) {
                        GLES20.glUniform1f(pass, (float) i);
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
                    }
                    final long submitted = System.nanoTime();
                    GLES20.glFinish();
                    final long end = System.nanoTime();
                    final long busy = end - start;
                    logFrames++;
                    logBusyNs += busy;
                    logSubmitNs += submitted - start;
                    logPct = pct;
                    if (end - logSince >= GPU_LOG_INTERVAL_NS) {
                        logFrameLoad(logPct, logFrames, logBusyNs, logSubmitNs);
                        logFrames = 0;
                        logBusyNs = 0;
                        logSubmitNs = 0;
                        logSince = end;
                    }
                    if (pct < 100) {
                        sleepNanosQuietly(busy * (100L - pct) / pct);
                    }
                }
                logFrameLoad(logPct, logFrames, logBusyNs, logSubmitNs);
                GLES20.glDeleteProgram(program);
            } catch (Throwable t) {
                Log.w(TAG, "GPU stress unavailable", t);
            } finally {
                // A failed initialisation means the device cannot provide the
                // offscreen GL context; do not retry it on every slider tick.
                if (!ready && mRunning) {
                    mStress.mGpuFailed = true;
                }
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT);
                    if (context != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(display, context);
                    }
                    if (surface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(display, surface);
                    }
                    EGL14.eglTerminate(display);
                }
            }
        }

        /** Zero means "no advertised limit"; otherwise never exceed it. */
        private static int limit(int target, int max) {
            return max > 0 ? Math.min(target, max) : target;
        }

        private static int configAttrib(EGLDisplay display, EGLConfig config, int attrib) {
            final int[] value = new int[1];
            if (!EGL14.eglGetConfigAttrib(display, config, attrib, value, 0)) {
                return 0;
            }
            return value[0];
        }

        private static EGLSurface createPbuffer(EGLDisplay display, EGLConfig config,
                int width, int height) {
            final int[] surfaceAttribs = {
                    EGL14.EGL_WIDTH, width,
                    EGL14.EGL_HEIGHT, height,
                    EGL14.EGL_NONE,
            };
            return EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0);
        }

        private static void logFrameLoad(int pct, long frames, long busyNs, long submitNs) {
            if (frames <= 0) {
                return;
            }
            final double frameMs = busyNs / (double) frames / 1_000_000.0;
            final double submitMs = submitNs / (double) frames / 1_000_000.0;
            Log.i(TAG, String.format(Locale.US,
                    "GPU stress: %d%% %.1f ms/frame (submit %.2f ms, gpu %.1f ms)",
                    pct, frameMs, submitMs, Math.max(0.0, frameMs - submitMs)));
        }

        private static int buildProgram() {
            final int vertexShader = compile(GLES20.GL_VERTEX_SHADER,
                    "attribute vec2 aPosition;\n"
                            + "void main() {\n"
                            + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
                            + "}\n");
            // The chain is deliberately serial (d feeds the next sin argument):
            // it keeps the fragment ALU busy for a long, predictable time per
            // pixel and discourages the driver from collapsing the loop.
            final int fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float;\n"
                            + "uniform float uTime;\n"
                            + "uniform float uPass;\n"
                            + "void main() {\n"
                            + "  vec2 p = (gl_FragCoord.xy + vec2(uPass * 37.0)) * 0.002 - 1.0;\n"
                            + "  float v = 0.0;\n"
                            + "  float d = 0.0;\n"
                            + "  for (int i = 0; i < " + GPU_SHADER_ITERATIONS + "; i++) {\n"
                            + "    float f = float(i) + 1.0;\n"
                            + "    v += sin(p.x * f + uTime + d) * cos(p.y * f - uTime) / f;\n"
                            + "    d += v * 0.01;\n"
                            + "  }\n"
                            + "  gl_FragColor = vec4(abs(v) * 0.5, abs(v), d * 0.25 + 0.25, 1.0);\n"
                            + "}\n");
            if (vertexShader == 0 || fragmentShader == 0) {
                return 0;
            }
            final int program = GLES20.glCreateProgram();
            if (program == 0) {
                return 0;
            }
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            final int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            if (linked[0] != GLES20.GL_TRUE) {
                Log.w(TAG, "stress shader link failed: "
                        + GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                return 0;
            }
            return program;
        }

        private static int compile(int type, String source) {
            final int shader = GLES20.glCreateShader(type);
            if (shader == 0) {
                return 0;
            }
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            final int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] != GLES20.GL_TRUE) {
                Log.w(TAG, "stress shader compile failed: "
                        + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }
    }

    /** Written by the workers so the JIT cannot discard the busy loop. */
    private static volatile long mSink;
}
