package org.raycc.vidly.native

import android.graphics.Matrix
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import org.webrtc.YuvConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU-based beauty filter implemented as an OpenGL ES 2.0 shader pipeline.
 *
 * The processor receives camera frames as OES external textures (via the
 * SurfaceTextureHelper pipeline used by [org.webrtc.VideoSource]). For each
 * frame it:
 *   1. Makes its own EGL context (sharing resources with the camera context)
 *      current on the render thread.
 *   2. Renders the OES texture through a single-pass fragment shader that
 *      performs skin smoothing (9-tap box blur on luma blended with the
 *      original), brightness lift, contrast, and saturation.
 *   3. Captures the result into a fresh GL_TEXTURE_2D RGB texture and wraps
 *      it as a [TextureBufferImpl] which is delivered to the downstream sink.
 *
 * EGL/GL state is created lazily on the first frame so that we can share
 * resources with the camera thread's already-current context, and is released
 * via [setSink] (with null) which is the only cleanup hook the surrounding
 * client invokes.
 */
class BeautyVideoProcessor : VideoProcessor {

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var intensity: Float = 0.45f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    // Whether to horizontally flip the SENDER side of the frame. The front
    // camera's sensor produces a mirrored frame; the local preview re-mirrors
    // it via SurfaceViewRenderer.setMirror(true) to look natural, but the
    // frames handed to the encoder/stream are still mirrored. Flipping here
    // (before the sink) corrects the encoded/remote orientation, while the
    // local renderer's setMirror undoes it again for display. Toggled per
    // front/back camera by NativeWebRtcClient.
    @Volatile
    var flipHorizontally: Boolean = false

    // Reused scratch matrix for the horizontal flip. Lives in normalized [0,1]
    // texture-coordinate space as expected by TextureBuffer.applyTransformMatrix.
    // Reused per-frame; only reset+populated on the camera thread.
    private val flipMatrix = Matrix()
    // Reused scratch matrix that reflects across the texture-space vertical axis
    // (flip t). Needed when the frame's rotation is 90/270 because the flip is
    // applied BEFORE rotation, and a 90/270 rotation swaps the axes — so to get
    // a horizontal mirror in display space we must flip the perpendicular axis
    // in texture space.
    private val flipMatrixRotated = Matrix()

    init {
        // Pre-build the two flip variants once. applyTransformMatrix concats
        // our matrix with the buffer's existing transform, so each just needs
        // to express "mirror" in normalized [0,1] coords; we pick which to use
        // per-frame based on frame.rotation.
        flipMatrix.reset()
        flipMatrix.preScale(-1f, 1f)      // mirror across vertical axis (s)
        flipMatrix.postTranslate(1f, 0f)  // remap back into [0,1]
        flipMatrixRotated.reset()
        flipMatrixRotated.preScale(1f, -1f)     // mirror across horizontal axis (t)
        flipMatrixRotated.postTranslate(0f, 1f) // remap back into [0,1]
    }

    private var sink: VideoSink? = null

    // GL / EGL state. All of these are touched only on the render thread
    // (the SurfaceTextureHelper thread that delivers frames to us).
    private var renderHandler: Handler? = null
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTexMatrixLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uTexelSizeLoc: Int = -1
    private var uIntensityLoc: Int = -1
    private var uBrightnessLoc: Int = -1
    private var uContrastLoc: Int = -1
    private var uSaturationLoc: Int = -1

    private var fbo: Int = 0
    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private val texMatrix4 = FloatArray(16)
    private val androidMatrix3 = FloatArray(9)

    private var yuvConverter: YuvConverter? = null
    private var initialized = false
    private var disposed = false

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
        if (sink == null) {
            scheduleRelease()
        }
    }

    override fun onCapturerStarted(success: Boolean) = Unit
    override fun onCapturerStopped() = Unit

    override fun onFrameCaptured(frame: VideoFrame) {
        val out = sink ?: return

        // Sender-side de-mirror for the front camera.
        //
        // WHY: the front camera sensor produces a horizontally-mirrored frame,
        // and that mirroring survives through encoding unchanged (rotation does
        // not change handedness). So the remote peer decodes a mirrored frame.
        // To make the remote see the correct orientation we must un-mirror the
        // frame BEFORE it reaches the encoder.
        //
        // WHICH AXIS: applyTransformMatrix operates in normalized [0,1] texture
        // space, and the flip is applied BEFORE the capturer's rotation. When
        // rotation is 90° or 270°, the texture-space axes are swapped relative
        // to display space, so flipping the texture-space V axis (t) produces a
        // HORIZONTAL flip in the final displayed frame. For 0°/180° the axes
        // are not swapped, so we flip the U axis (s). (Empirically verified:
        // flipping only the S axis on a 270°-rotated front-cam frame produced a
        // vertical flip on the receiver.)
        //
        // OWNERSHIP: applyTransformMatrix retains the source buffer and returns
        // a fresh self-owned buffer. We wrap it in a VideoFrame and release it
        // after delivery (the sink retains synchronously, matching the beauty
        // pass contract below). The incoming `frame` is loaned by VideoSource
        // and is NOT released here.
        val flipped = if (!flipHorizontally || frame.buffer !is VideoFrame.TextureBuffer) {
            null
        } else {
            // Pick the flip variant by rotation. Front cams report 270° and
            // back cams 90° (both fall into the rotated branch); a screen
            // share at 0°/180° would take the s-axis branch — but the processor
            // only runs on the camera path, so this is mostly belt-and-suspenders.
            val matrix = when (((frame.rotation % 360) + 360) % 360) {
                90, 270 -> flipMatrixRotated
                else -> flipMatrix
            }
            val tb = frame.buffer as VideoFrame.TextureBuffer
            val newBuf = tb.applyTransformMatrix(matrix, tb.width, tb.height)
            VideoFrame(newBuf, frame.rotation, frame.timestampNs)
        }
        val workingFrame = flipped ?: frame
        val buffer = workingFrame.buffer

        if (disposed ||
            !enabled ||
            intensity <= 0.01f ||
            buffer !is VideoFrame.TextureBuffer ||
            buffer.type != VideoFrame.TextureBuffer.Type.OES
        ) {
            out.onFrame(workingFrame)
            flipped?.release()
            return
        }

        try {
            ensureInitialized()
        } catch (t: Throwable) {
            out.onFrame(workingFrame)
            flipped?.release()
            return
        }

        // Save whatever the camera thread had current so we can restore it.
        val priorDisplay = EGL14.eglGetCurrentDisplay()
        val priorContext = EGL14.eglGetCurrentContext()
        val priorDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val priorReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            // Couldn't activate our context -- pass the frame through.
            out.onFrame(workingFrame)
            flipped?.release()
            return
        }

        var outputTexture = 0
        var delivered = false
        try {
            val width = buffer.width
            val height = buffer.height
            outputTexture = createRgbaTexture(width, height)

            renderBeautyPass(buffer, outputTexture, width, height)

            // Force the GPU to finish writing before the encoder/renderer can sample it.
            GLES20.glFinish()

            val capturedTexture = outputTexture
            val outBuffer = TextureBufferImpl(
                width,
                height,
                VideoFrame.TextureBuffer.Type.RGB,
                capturedTexture,
                Matrix(), // identity -- transform was baked in during the shader pass
                renderHandler!!,
                yuvConverter!!,
                Runnable { deleteTextureOnRenderThread(capturedTexture) }
            )

            val processed = VideoFrame(outBuffer, workingFrame.rotation, workingFrame.timestampNs)
            out.onFrame(processed)
            processed.release()
            delivered = true
            outputTexture = 0
        } catch (t: Throwable) {
            // Don't drop the frame on a transient GL error.
            if (!delivered) out.onFrame(workingFrame)
        } finally {
            if (outputTexture != 0) {
                val tex = intArrayOf(outputTexture)
                GLES20.glDeleteTextures(1, tex, 0)
            }
            // Restore the camera thread's prior EGL state.
            if (priorDisplay != EGL14.EGL_NO_DISPLAY && priorContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(priorDisplay, priorDrawSurface, priorReadSurface, priorContext)
            } else {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
            // We held an extra ref on the (flipped) buffer for the duration of
            // this pass; balance the applyTransformMatrix retain now.
            flipped?.release()
        }
    }

    // ---- Initialization & teardown -----------------------------------------

    private fun ensureInitialized() {
        if (initialized) return

        val looper = Looper.myLooper() ?: Looper.getMainLooper()
        renderHandler = Handler(looper)

        // Use the camera thread's currently-bound context as the share group
        // so we can sample the OES texture it produced.
        val sharedContext = EGL14.eglGetCurrentContext()
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) error("eglGetDisplay failed")
        val versions = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
            error("eglInitialize failed")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0
        ) {
            error("eglChooseConfig failed")
        }
        val config = configs[0] ?: error("No EGL config")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, sharedContext, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) error("eglCreateContext failed")

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1280,
            EGL14.EGL_HEIGHT, 720,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) error("eglCreatePbufferSurface failed")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            error("eglMakeCurrent failed during init")
        }

        try {
            program = buildProgram()
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
            uTexelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")
            uIntensityLoc = GLES20.glGetUniformLocation(program, "uIntensity")
            uBrightnessLoc = GLES20.glGetUniformLocation(program, "uBrightness")
            uContrastLoc = GLES20.glGetUniformLocation(program, "uContrast")
            uSaturationLoc = GLES20.glGetUniformLocation(program, "uSaturation")

            vertexBuffer = floatBufferOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
            )
            texCoordBuffer = floatBufferOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f
            )

            val fbos = IntArray(1)
            GLES20.glGenFramebuffers(1, fbos, 0)
            fbo = fbos[0]

            yuvConverter = YuvConverter()
        } finally {
            // Release current; per-frame code re-binds.
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
        }

        initialized = true
    }

    private fun scheduleRelease() {
        val handler = renderHandler ?: run {
            disposed = true
            return
        }
        if (disposed) return
        disposed = true
        handler.post { releaseGl() }
    }

    private fun releaseGl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) return
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        try {
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            if (fbo != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                fbo = 0
            }
            yuvConverter?.release()
            yuvConverter = null
        } finally {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
        }
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
        initialized = false
    }

    private fun deleteTextureOnRenderThread(textureId: Int) {
        val handler = renderHandler ?: return
        handler.post {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) return@post
            // Only switch contexts if needed. The release callback usually fires
            // off the render thread's frame loop, where no context is current.
            val priorContext = EGL14.eglGetCurrentContext()
            val needSwitch = priorContext != eglContext
            val priorDisplay = EGL14.eglGetCurrentDisplay()
            val priorDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val priorRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            if (needSwitch) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            }
            try {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            } finally {
                if (needSwitch) {
                    if (priorDisplay != EGL14.EGL_NO_DISPLAY && priorContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglMakeCurrent(priorDisplay, priorDraw, priorRead, priorContext)
                    } else {
                        EGL14.eglMakeCurrent(
                            eglDisplay,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT
                        )
                    }
                }
            }
        }
    }

    // ---- Per-frame rendering ------------------------------------------------

    private fun renderBeautyPass(
        buffer: VideoFrame.TextureBuffer,
        outputTextureId: Int,
        width: Int,
        height: Int
    ) {
        // Bind the FBO with the output texture as color attachment.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            outputTextureId,
            0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            error("FBO incomplete: 0x${Integer.toHexString(status)}")
        }
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Bind the camera's OES texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, buffer.textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glUniform1i(uTextureLoc, 0)

        // Convert the buffer's 3x3 transform matrix into a 4x4 used by GLSL.
        toGl4x4(buffer.transformMatrix, texMatrix4)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix4, 0)

        // Beauty parameters. The shader reads luma from the OES sample directly,
        // so coefficients are in [0,1] color space.
        val smooth = (0.85f * intensity).coerceIn(0f, 1f)
        val brightness = 0.11f * intensity
        val contrast = 1f + 0.12f * intensity
        val saturation = 1f + 0.12f * intensity
        GLES20.glUniform2f(uTexelSizeLoc, 1f / width.toFloat(), 1f / height.toFloat())
        GLES20.glUniform1f(uIntensityLoc, smooth)
        GLES20.glUniform1f(uBrightnessLoc, brightness)
        GLES20.glUniform1f(uContrastLoc, contrast)
        GLES20.glUniform1f(uSaturationLoc, saturation)

        // Vertex attributes.
        val vbuf = vertexBuffer!!.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vbuf)

        val tbuf = texCoordBuffer!!.position(0)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, tbuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun createRgbaTexture(width: Int, height: Int): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        val id = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return id
    }

    // ---- Shader plumbing ----------------------------------------------------

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val prog = GLES20.glCreateProgram()
        if (prog == 0) error("glCreateProgram failed")
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            error("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) error("glCreateShader($type) failed")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Shader compile failed: $log")
        }
        return shader
    }

    /**
     * Convert the [android.graphics.Matrix] used by [VideoFrame.TextureBuffer]
     * (a 3x3 affine matrix on (s,t) coords) to the column-major 4x4 used by GLSL.
     */
    private fun toGl4x4(src: Matrix, dst: FloatArray) {
        src.getValues(androidMatrix3)
        // androidMatrix3 layout: [scaleX, skewX, transX, skewY, scaleY, transY, persp0, persp1, persp2]
        // GL column-major 4x4 acting on vec4(s, t, 0, 1):
        for (i in 0 until 16) dst[i] = 0f
        dst[0] = androidMatrix3[0]   // m00
        dst[1] = androidMatrix3[3]   // m10
        dst[4] = androidMatrix3[1]   // m01
        dst[5] = androidMatrix3[4]   // m11
        dst[10] = 1f
        dst[12] = androidMatrix3[2]  // m02 (translate s)
        dst[13] = androidMatrix3[5]  // m12 (translate t)
        dst[15] = 1f
    }

    private fun floatBufferOf(vararg values: Float): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(values)
        fb.position(0)
        return fb
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        // Single-pass beauty shader.
        //  - Sample OES camera texture.
        //  - Compute luma (BT.601).
        //  - Compute box-blurred luma using 9 neighbouring taps.
        //  - Blend (smooth) blurred luma with original by uIntensity.
        //  - Apply brightness lift + contrast around mid-grey to luma.
        //  - Recompose RGB by adding scaled chroma (saturation) on top of new luma.
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform vec2 uTexelSize;
            uniform float uIntensity;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;

            const vec3 LUMA = vec3(0.299, 0.587, 0.114);

            float lumaAt(vec2 uv) {
                return dot(texture2D(uTexture, uv).rgb, LUMA);
            }

            void main() {
                vec3 color = texture2D(uTexture, vTexCoord).rgb;
                float origLuma = dot(color, LUMA);

                // 9-tap box blur on luma (3x3 neighborhood).
                float dx = uTexelSize.x;
                float dy = uTexelSize.y;
                float sum = 0.0;
                sum += lumaAt(vTexCoord + vec2(-dx, -dy));
                sum += lumaAt(vTexCoord + vec2( 0.0, -dy));
                sum += lumaAt(vTexCoord + vec2( dx, -dy));
                sum += lumaAt(vTexCoord + vec2(-dx,  0.0));
                sum += origLuma;
                sum += lumaAt(vTexCoord + vec2( dx,  0.0));
                sum += lumaAt(vTexCoord + vec2(-dx,  dy));
                sum += lumaAt(vTexCoord + vec2( 0.0,  dy));
                sum += lumaAt(vTexCoord + vec2( dx,  dy));
                float blurLuma = sum / 9.0;

                // Skin smoothing: blend toward blurred luma.
                float smoothLuma = mix(origLuma, blurLuma, uIntensity);

                // Brightness + contrast around mid-grey on the new luma.
                float newLuma = (smoothLuma - 0.5) * uContrast + 0.5 + uBrightness;

                // Chroma = original color minus original luma; rescale for saturation
                // and add the new luma back as the achromatic component.
                vec3 chroma = color - vec3(origLuma);
                vec3 outColor = vec3(newLuma) + chroma * uSaturation;
                gl_FragColor = vec4(clamp(outColor, 0.0, 1.0), 1.0);
            }
        """
    }
}
