# 005 — BeautyVideoProcessor: Per-Generation GL State, Save+Restore EGL

**Status:** Accepted (2026-07-10, commit e4a492d)
**Code:** `BeautyVideoProcessor.Gl`, `currentGlOrInit`, `scheduleRelease`, `initGl`/`onFrameCaptured`/`releaseGl` EGL handling

## Problem

The beauty filter is a `VideoProcessor` with its own EGL context, program, FBO, and texture pool — all of which are only valid on the thread (and in the share group) where they were created: the camera's `SurfaceTextureHelper` render thread.

But the camera pipeline gets **rebuilt** during a call: `resumeVideo()` falls back to a full teardown+recreate when the capturer can't restart, and each rebuild creates a *new* SurfaceTextureHelper thread. The processor instance itself is shared across rebuilds (it's a constructor-time field of `NativeWebRtcClient`, and its `enabled`/`intensity`/`flipHorizontally` settings must survive). If the GL objects live as fields of the processor instance, a rebuild races: the old thread still needs the objects to destroy them, while the first frame on the new thread tries to reinitialize the same fields. Result: crashes in `eglMakeCurrent`, leaked contexts, or frames rendered with a context from a dead thread.

A second, subtler bug lived in EGL binding management: after init or a render pass, the code used to *unbind* the thread's context (`eglMakeCurrent(..., EGL_NO_CONTEXT)`). But the processor runs **on the SurfaceTextureHelper's own thread**, which needs *its own* context current for `SurfaceTexture.updateTexImage()` to work. Unbinding corrupted the helper's state — camera frames stopped flowing or produced garbage.

## Decision

1. **All GL/EGL objects live in a per-generation `Gl` object**, created lazily on first frame of a given render thread and carrying a `Handler` bound to that thread. `setSink(null)` (fired by `VideoSource.dispose()` during pipeline teardown) detaches the current generation and posts its destruction **to its own thread's handler** — serialized behind any in-flight frame. The processor's `gl` field is nulled immediately, so the next frame on the *new* thread lazily creates a fresh generation. Old and new generations share no fields, so they cannot race. Late texture-recycle posts check `Gl.released` and no-op against a destroyed context.
2. **Every `eglMakeCurrent` saves the thread's prior binding (display/context/draw/read surfaces) and restores it afterwards** — in `initGl`, in the per-frame pass, and in `releaseGl`. Never unbind; the prior binding belongs to the SurfaceTextureHelper and must come back exactly as it was.

## Alternatives considered

- **Recreate the processor instance on every pipeline rebuild.** Rejected: user-facing settings live on it, `VideoSource.setVideoProcessor` is set at source creation, and the teardown/creation ordering across two threads is exactly the race we're avoiding — a generation object is the same idea with a smaller blast radius.
- **Synchronize init/release with locks.** Rejected: destruction *must* run on the old thread (GL is thread-affine); a lock can't move work between threads, only block them.

## Trade-offs and failure modes

- If generation init fails (EGL errors), frames pass through unfiltered — graceful, but silent (only a null return; worth a log if it recurs).
- A brief window after rebuild renders 1–2 frames unfiltered while the new generation initializes.

## Revisit if

- WebRTC's Android API ever offers a processor lifecycle callback tied to the render thread — then lazy-init-on-first-frame could become explicit.
