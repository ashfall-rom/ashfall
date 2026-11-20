/*
 * ashfall-bench — small, self-contained benchmark suite for the RP5.
 *
 * Copyright (C) 2026 The AshFall Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Why this exists (issue #130): the tuning decisions need a reproducible
 * number, not a vendor app score.  Every test is time-bounded (CPU/RAM) or
 * frame-bounded (GPU), pinned where that matters, and prints one line per
 * result so a run can be diffed between builds.
 *
 * Build (NDK):
 *   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang \
 *       -O2 -fno-fast-math -o ashfall-bench ashfall-bench.c \
 *       -lEGL -lGLESv3 -landroid -llog
 *
 * Run:
 *   adb push ashfall-bench /data/local/tmp/ && adb shell /data/local/tmp/ashfall-bench all
 *
 * Tests:
 *   cpu   integer (xorshift) and double FMA throughput, 1 thread on the
 *         prime core and 8 threads pinned one per core.
 *   mem   read / write / copy bandwidth and pointer-chase latency.
 *   gpu   EGL pbuffer: ALU-heavy fragment fill, blended fill (ROP), and a
 *         vertex-throughput pass.
 *
 * The numbers are throughput while the workload runs; they are only
 * comparable with the same binary, the same thermal starting point and the
 * same clock/thermal configuration.  See docs/perf-tuning.md.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <math.h>
#include <pthread.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <EGL/egl.h>
#include <GLES3/gl3.h>

#define CPU_SECONDS_DEFAULT 2.0
#define MT_SECONDS_DEFAULT 3.0
#define MEM_MB_DEFAULT 128
#define GPU_PIX 1024
#define GPU_FRAMES 60
#define GPU_WARMUP 10
static int g_frames = GPU_FRAMES;
static int g_warmup = GPU_WARMUP;
#define GPU_ALU_ITERS 1024

/* ------------------------------------------------------------------ */
/* helpers                                                            */
/* ------------------------------------------------------------------ */

static double now_s(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

static void pin_cpu(int cpu)
{
    cpu_set_t set;
    CPU_ZERO(&set);
    CPU_SET(cpu, &set);
    if (sched_setaffinity(0, sizeof(set), &set) != 0)
        fprintf(stderr, "warn: sched_setaffinity(%d): %s\n", cpu, strerror(errno));
}

static int ncpu(void)
{
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    return n > 0 ? (int)n : 1;
}

static void *xalloc(size_t bytes)
{
    void *p = NULL;
    if (posix_memalign(&p, 64, bytes) != 0 || p == NULL) {
        fprintf(stderr, "error: cannot allocate %zu bytes\n", bytes);
        exit(2);
    }
    memset(p, 0x5a, bytes);
    return p;
}

static volatile uint64_t g_sink;

/* ------------------------------------------------------------------ */
/* CPU                                                                */
/* ------------------------------------------------------------------ */

struct cpu_job {
    int cpu;
    double seconds;
    int fp;
    double ops;      /* out */
};

static void *cpu_worker(void *arg)
{
    struct cpu_job *j = arg;
    uint64_t s = 0x243f6a8885a308d3ULL ^ (uint64_t)(j->cpu + 1) * 0x9e3779b97f4a7c15ULL;
    double a0 = 1.0, a1 = 1.000001, a2 = 1.000002, a3 = 1.000003;
    const double m = 1.0000000000000002;
    const double c = 1e-17;
    uint64_t ops = 0;
    const double deadline = now_s() + j->seconds;

    if (j->cpu >= 0)
        pin_cpu(j->cpu);

    while (now_s() < deadline) {
        if (j->fp) {
            for (int i = 0; i < 200000; i++) {
                a0 = a0 * m + c;
                a1 = a1 * m - c;
                a2 = a2 * m + c;
                a3 = a3 * m - c;
            }
            ops += 200000ULL * 4;
        } else {
            for (int i = 0; i < 500000; i++) {
                s ^= s << 13;
                s ^= s >> 7;
                s ^= s << 17;
                s += 0x9e3779b97f4a7c15ULL;
            }
            ops += 500000ULL * 2; /* one xorshift round + one add */
        }
    }
    if (j->fp)
        g_sink ^= (uint64_t)(a0 + a1 + a2 + a3);
    else
        g_sink ^= s;
    j->ops = (double)ops / j->seconds / 1e6;
    return NULL;
}

static double run_cpu(int threads, int fp, double seconds)
{
    struct cpu_job *jobs = calloc((size_t)threads, sizeof(*jobs));
    pthread_t *tids = calloc((size_t)threads, sizeof(*tids));
    if (jobs == NULL || tids == NULL) {
        fprintf(stderr, "error: out of memory\n");
        exit(2);
    }
    for (int i = 0; i < threads; i++) {
        jobs[i].cpu = threads == 1 ? ncpu() - 1 : i;
        jobs[i].seconds = seconds;
        jobs[i].fp = fp;
        jobs[i].ops = 0;
        pthread_create(&tids[i], NULL, cpu_worker, &jobs[i]);
    }
    double total = 0;
    for (int i = 0; i < threads; i++) {
        pthread_join(tids[i], NULL);
        total += jobs[i].ops;
    }
    free(jobs);
    free(tids);
    return total; /* million ops/s */
}

/* ------------------------------------------------------------------ */
/* Memory                                                             */
/* ------------------------------------------------------------------ */

struct mem_job {
    uint8_t *buf;
    size_t bytes;
    double seconds;
    int mode; /* 0 read, 1 write, 2 copy */
    uint8_t *dst;
    double gbs;
};

static void *mem_worker(void *arg)
{
    struct mem_job *j = arg;
    const double deadline = now_s() + j->seconds;
    uint64_t sum = 0;
    size_t n = 0;

    while (now_s() < deadline) {
        if (j->mode == 0) {
            const uint64_t *p = (const uint64_t *)j->buf;
            size_t words = j->bytes / sizeof(uint64_t);
            for (size_t i = 0; i < words; i++)
                sum += p[i];
        } else if (j->mode == 1) {
            uint64_t *p = (uint64_t *)j->buf;
            size_t words = j->bytes / sizeof(uint64_t);
            for (size_t i = 0; i < words; i++)
                p[i] = (uint64_t)i * 0x9e3779b97f4a7c15ULL;
        } else {
            memcpy(j->dst, j->buf, j->bytes);
        }
        n += j->bytes;
    }
    g_sink ^= sum;
    j->gbs = (double)n / j->seconds / 1e9;
    return NULL;
}

static double run_mem(int mode, size_t bytes, double seconds)
{
    struct mem_job j = {0};
    pthread_t tid;
    j.buf = xalloc(bytes);
    j.bytes = bytes;
    j.seconds = seconds;
    j.mode = mode;
    if (mode == 2)
        j.dst = xalloc(bytes);
    pin_cpu(ncpu() - 1);
    pthread_create(&tid, NULL, mem_worker, &j);
    pthread_join(tid, NULL);
    double gbs = j.gbs;
    free(j.buf);
    free(j.dst);
    return gbs;
}

/* Round-trip dependent loads: latency, not bandwidth. 64-byte stride so every
 * access is its own cache line, shuffled into one big cycle. */
static double run_latency(size_t bytes)
{
    const size_t lines = bytes / 64;
    uint64_t *idx = xalloc(lines * sizeof(uint64_t));
    for (size_t i = 0; i < lines; i++)
        idx[i] = i;
    /* Fisher-Yates with a fixed seed: reproducible permutation. */
    uint64_t s = 0x853c49e6748fea9bULL;
    for (size_t i = lines - 1; i > 0; i--) {
        s ^= s << 13; s ^= s >> 7; s ^= s << 17;
        size_t j = (size_t)(s % (i + 1));
        uint64_t t = idx[i]; idx[i] = idx[j]; idx[j] = t;
    }
    uint8_t *buf = xalloc(bytes);
    for (size_t i = 0; i < lines; i++)
        *(uint64_t *)(buf + i * 64) = idx[(i + 1) % lines];

    pin_cpu(ncpu() - 1);
    uint64_t cur = 0;
    uint64_t n = 0;
    const double deadline = now_s() + 1.5;
    while (now_s() < deadline) {
        for (int i = 0; i < 100000; i++) {
            cur = *(uint64_t *)(buf + cur * 64);
            n++;
        }
    }
    g_sink ^= cur;
    free(idx);
    free(buf);
    return (double)n / 1.5; /* accesses/s */
}

/* ------------------------------------------------------------------ */
/* GPU                                                                */
/* ------------------------------------------------------------------ */

static const char *VS_QUAD =
    "#version 300 es\n"
    "out vec2 v_uv;\n"
    "void main(){\n"
    "  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n"
    "  v_uv = p;\n"
    "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
    "}\n";

static const char *FS_ALU =
    "#version 300 es\n"
    "precision highp float;\n"
    "in vec2 v_uv;\n"
    "out vec4 o;\n"
    "void main(){\n"
    "  vec4 v = vec4(v_uv, 0.5, 1.0);\n"
    "  for (int i = 0; i < %d; i++) {\n"
    "    v = v * 1.0000001 + vec4(0.0000001);\n"
    "    v = normalize(v);\n"
    "  }\n"
    "  o = v;\n"
    "}\n";

static const char *FS_BLEND =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec2 v_uv;\n"
    "out vec4 o;\n"
    "void main(){ o = vec4(v_uv, 0.25, 0.02); }\n";

static const char *VS_VERT =
    "#version 300 es\n"
    "void main(){ gl_Position = vec4(float(gl_VertexID & 1023) / 512.0 - 1.0,\n"
    "                                float((gl_VertexID >> 10) & 1023) / 512.0 - 1.0,\n"
    "                                0.0, 1.0);\n"
    "  gl_PointSize = 1.0;\n"
    "}\n";

static const char *FS_VERT =
    "#version 300 es\n"
    "precision mediump float;\n"
    "out vec4 o;\n"
    "void main(){ o = vec4(1.0); }\n";

static GLuint compile(GLenum type, const char *src)
{
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        fprintf(stderr, "error: shader: %s\n", log);
        return 0;
    }
    return s;
}

static GLuint program(const char *vs_src, const char *fs_src)
{
    GLuint vs = compile(GL_VERTEX_SHADER, vs_src);
    GLuint fs = compile(GL_FRAGMENT_SHADER, fs_src);
    if (!vs || !fs)
        return 0;
    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetProgramInfoLog(p, sizeof(log), NULL, log);
        fprintf(stderr, "error: link: %s\n", log);
        return 0;
    }
    return p;
}

struct gpu_result {
    int ok;
    char renderer[128];
    double alu_mpix;
    double blend_mpix;
    double vert_mps;
};

static int gpu_init(EGLDisplay *dpy, EGLSurface *surf, EGLContext *ctx)
{
    EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (d == EGL_NO_DISPLAY) {
        fprintf(stderr, "gpu: no EGL display\n");
        return 0;
    }
    if (!eglInitialize(d, NULL, NULL)) {
        fprintf(stderr, "gpu: eglInitialize failed 0x%x\n", eglGetError());
        return 0;
    }
    eglBindAPI(EGL_OPENGL_ES_API);

    const EGLint cfg_attr[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0, EGL_STENCIL_SIZE, 0,
        EGL_NONE
    };
    EGLConfig cfg;
    EGLint n = 0;
    if (!eglChooseConfig(d, cfg_attr, &cfg, 1, &n) || n < 1) {
        fprintf(stderr, "gpu: no ES3 config\n");
        return 0;
    }
    const EGLint pb_attr[] = { EGL_WIDTH, GPU_PIX, EGL_HEIGHT, GPU_PIX, EGL_NONE };
    EGLSurface s = eglCreatePbufferSurface(d, cfg, pb_attr);
    if (s == EGL_NO_SURFACE) {
        fprintf(stderr, "gpu: pbuffer failed 0x%x\n", eglGetError());
        return 0;
    }
    const EGLint ctx_attr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, ctx_attr);
    if (c == EGL_NO_CONTEXT) {
        fprintf(stderr, "gpu: context failed 0x%x\n", eglGetError());
        return 0;
    }
    if (!eglMakeCurrent(d, s, s, c)) {
        fprintf(stderr, "gpu: makeCurrent failed 0x%x\n", eglGetError());
        return 0;
    }
    *dpy = d;
    *surf = s;
    *ctx = c;
    return 1;
}

static void gpu_shutdown(EGLDisplay d, EGLSurface s, EGLContext c)
{
    eglMakeCurrent(d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (c != EGL_NO_CONTEXT) eglDestroyContext(d, c);
    if (s != EGL_NO_SURFACE) eglDestroySurface(d, s);
    eglTerminate(d);
}

static void gpu_sync(const char *tag)
{
    glFinish();
    unsigned char px[4] = {0};
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    GLenum e = glGetError();
    if (e != GL_NO_ERROR)
        fprintf(stderr, "gpu: %s gl error 0x%x\n", tag, e);
}

static struct gpu_result run_gpu(void)
{
    struct gpu_result r = {0};
    EGLDisplay d = EGL_NO_DISPLAY;
    EGLSurface s = EGL_NO_SURFACE;
    EGLContext c = EGL_NO_CONTEXT;

    if (!gpu_init(&d, &s, &c))
        return r;

    const GLubyte *rend = glGetString(GL_RENDERER);
    snprintf(r.renderer, sizeof(r.renderer), "%s", rend ? (const char *)rend : "?");
    glViewport(0, 0, GPU_PIX, GPU_PIX);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);

    char fs_alu[1024];
    snprintf(fs_alu, sizeof(fs_alu), FS_ALU, GPU_ALU_ITERS);
    GLuint p_alu = program(VS_QUAD, fs_alu);
    GLuint p_blend = program(VS_QUAD, FS_BLEND);
    GLuint p_vert = program(VS_VERT, FS_VERT);

    GLuint vao;
    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);

    if (p_alu) {
        glUseProgram(p_alu);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glDisable(GL_BLEND);
        for (int i = 0; i < g_warmup; i++)
            glDrawArrays(GL_TRIANGLES, 0, 3);
        gpu_sync("alu-warmup");
        double t0 = now_s();
        for (int i = 0; i < g_frames; i++)
            glDrawArrays(GL_TRIANGLES, 0, 3);
        gpu_sync("alu");
        double dt = now_s() - t0;
        r.alu_mpix = (double)g_frames * (GPU_PIX * (double)GPU_PIX) / dt / 1e6;
    }

    if (p_blend) {
        glUseProgram(p_blend);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        for (int i = 0; i < g_warmup; i++)
            glDrawArrays(GL_TRIANGLES, 0, 3);
        gpu_sync("blend-warmup");
        double t0 = now_s();
        /* 8 additive layers per "frame": ROP bandwidth, not shader ALU. */
        for (int i = 0; i < GPU_FRAMES; i++) {
            for (int l = 0; l < 8; l++)
                glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        gpu_sync("blend");
        double dt = now_s() - t0;
        r.blend_mpix = (double)GPU_FRAMES * 8 * (GPU_PIX * (double)GPU_PIX) / dt / 1e6;
        glDisable(GL_BLEND);
    }

    if (p_vert) {
        glUseProgram(p_vert);
        const int verts = 1 << 20;
        for (int i = 0; i < GPU_WARMUP; i++)
            glDrawArrays(GL_POINTS, 0, verts);
        gpu_sync("vert-warmup");
        double t0 = now_s();
        for (int i = 0; i < 20; i++)
            glDrawArrays(GL_POINTS, 0, verts);
        gpu_sync("vert");
        double dt = now_s() - t0;
        r.vert_mps = (double)20 * verts / dt / 1e6;
    }

    glDeleteVertexArrays(1, &vao);
    if (p_alu) glDeleteProgram(p_alu);
    if (p_blend) glDeleteProgram(p_blend);
    if (p_vert) glDeleteProgram(p_vert);
    gpu_shutdown(d, s, c);
    r.ok = 1;
    return r;
}

/* ------------------------------------------------------------------ */
/* main                                                               */
/* ------------------------------------------------------------------ */

static void usage(const char *argv0)
{
    fprintf(stderr,
            "usage: %s [all|cpu|mem|gpu] [--seconds S] [--mem-mb MB] [--frames N] [--cold] [--json]\n",
            argv0);
}

int main(int argc, char **argv)
{
    const char *what = "all";
    double cpu_seconds = CPU_SECONDS_DEFAULT;
    double mt_seconds = MT_SECONDS_DEFAULT;
    size_t mem_mb = MEM_MB_DEFAULT;
    int json = 0;

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--seconds") && i + 1 < argc)
            cpu_seconds = atof(argv[++i]);
        else if (!strcmp(argv[i], "--mem-mb") && i + 1 < argc)
            mem_mb = (size_t)atoi(argv[++i]);
        else if (!strcmp(argv[i], "--json"))
            json = 1;
        else if (!strcmp(argv[i], "--frames") && i + 1 < argc)
            g_frames = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--cold"))
            g_warmup = 0;
        else if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help")) {
            usage(argv[0]);
            return 0;
        } else
            what = argv[i];
    }
    if (cpu_seconds < 0.2) cpu_seconds = 0.2;
    if (mt_seconds < 0.2) mt_seconds = 0.2;

    const int cpus = ncpu();
    printf("bench  cpus=%d  cpu_s=%.1f  mem_mb=%zu  build=%s %s\n",
           cpus, cpu_seconds, mem_mb, __DATE__, __TIME__);

    double int_st = 0, int_mt = 0, fp_st = 0, fp_mt = 0;
    double mem_read = 0, mem_write = 0, mem_copy = 0, lat = 0;
    struct gpu_result g = {0};

    if (!strcmp(what, "cpu") || !strcmp(what, "all")) {
        int_st = run_cpu(1, 0, cpu_seconds);
        int_mt = run_cpu(cpus, 0, mt_seconds);
        fp_st = run_cpu(1, 1, cpu_seconds);
        fp_mt = run_cpu(cpus, 1, mt_seconds);
        printf("cpu.int.st  %.1f Mops/s\n", int_st);
        printf("cpu.int.mt  %.1f Mops/s\n", int_mt);
        printf("cpu.fp.st   %.1f Mops/s\n", fp_st);
        printf("cpu.fp.mt   %.1f Mops/s\n", fp_mt);
    }

    if (!strcmp(what, "mem") || !strcmp(what, "all")) {
        size_t bytes = mem_mb * 1024 * 1024;
        mem_read = run_mem(0, bytes, 1.5);
        mem_write = run_mem(1, bytes, 1.5);
        mem_copy = run_mem(2, bytes, 1.5);
        lat = run_latency(64u << 20);
        printf("mem.read    %.2f GB/s\n", mem_read);
        printf("mem.write   %.2f GB/s\n", mem_write);
        printf("mem.copy    %.2f GB/s\n", mem_copy);
        printf("mem.lat     %.1f Macc/s (%.1f ns)\n", lat / 1e6, 1e9 / lat);
    }

    if (!strcmp(what, "gpu") || !strcmp(what, "all")) {
        g = run_gpu();
        if (g.ok) {
            printf("gpu.renderer %s\n", g.renderer);
            printf("gpu.alu     %.1f Mpix/s\n", g.alu_mpix);
            printf("gpu.blend   %.1f Mpix/s\n", g.blend_mpix);
            printf("gpu.vert    %.1f Mvert/s\n", g.vert_mps);
        } else {
            printf("gpu.error   EGL/GLES3 unavailable\n");
        }
    }

    if (json) {
        printf("JSON {\"cpu_int_st\":%.1f,\"cpu_int_mt\":%.1f,\"cpu_fp_st\":%.1f,"
               "\"cpu_fp_mt\":%.1f,\"mem_read\":%.3f,\"mem_write\":%.3f,"
               "\"mem_copy\":%.3f,\"mem_lat_ns\":%.2f,\"gpu_alu\":%.1f,"
               "\"gpu_blend\":%.1f,\"gpu_vert\":%.1f,\"cpus\":%d}\n",
               int_st, int_mt, fp_st, fp_mt, mem_read, mem_write, mem_copy,
               lat > 0 ? 1e9 / lat : 0.0, g.alu_mpix, g.blend_mpix, g.vert_mps, cpus);
    }
    return 0;
}
