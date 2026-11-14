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
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The curve-editor stress test (docs/phase5.md §6.3).
 *
 * <p>CPU load: one worker thread per (up to eight) core, each running a busy
 * loop with a duty cycle equal to the requested percentage.  GPU load: an
 * offscreen EGL pbuffer that renders a deliberately expensive fragment shader
 * every frame with {@code glFinish()} and the same duty cycle.
 *
 * <p>A session always stops itself after ten minutes and is stopped by the
 * editor when it leaves the foreground.  Nothing here touches the daemons; the
 * load is ordinary app work, so the fan curves can be observed under it.
 */
public final class Stress {

    public static final long MAX_RUN_MS = 10 * 60 * 1000L;

    private static final String TAG = "AshFallParts";
    private static final int MAX_CPU_THREADS = 8;
    private static final int GPU_SIZE = 512;
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
                final int[] surfaceAttribs = {
                        EGL14.EGL_WIDTH, GPU_SIZE,
                        EGL14.EGL_HEIGHT, GPU_SIZE,
                        EGL14.EGL_NONE,
                };
                surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0);
                if (surface == EGL14.EGL_NO_SURFACE) {
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
                GLES20.glUseProgram(program);
                GLES20.glEnableVertexAttribArray(position);
                GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
                GLES20.glViewport(0, 0, GPU_SIZE, GPU_SIZE);
                ready = true;

                while (mRunning) {
                    final int pct = mStress.mGpuPct;
                    if (pct <= 0) {
                        sleepQuietly(50);
                        continue;
                    }
                    final long start = System.nanoTime();
                    GLES20.glUniform1f(time, (start % 1_000_000_000L) / 1_000_000f);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
                    GLES20.glFinish();
                    final long busy = System.nanoTime() - start;
                    if (pct < 100) {
                        sleepNanosQuietly(busy * (100L - pct) / pct);
                    }
                }
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

        private static int buildProgram() {
            final int vertexShader = compile(GLES20.GL_VERTEX_SHADER,
                    "attribute vec2 aPosition;\n"
                            + "void main() {\n"
                            + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
                            + "}\n");
            final int fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float;\n"
                            + "uniform float uTime;\n"
                            + "void main() {\n"
                            + "  vec2 p = gl_FragCoord.xy * 0.01;\n"
                            + "  float v = 0.0;\n"
                            + "  for (int i = 0; i < 64; i++) {\n"
                            + "    float f = float(i) + 1.0;\n"
                            + "    v += sin(p.x * f + uTime) * cos(p.y * f - uTime) / f;\n"
                            + "  }\n"
                            + "  gl_FragColor = vec4(abs(v), abs(v) * 0.5, 0.25, 1.0);\n"
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
