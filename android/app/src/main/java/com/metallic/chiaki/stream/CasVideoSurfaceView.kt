// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the decoded video stream through an off-screen [SurfaceTexture] (the MediaCodec output
 * target, unrelated to this view's own on-screen EGL surface) sampled by a single-pass Contrast
 * Adaptive Sharpening fragment shader, so decoded frames can be sharpened before they hit the
 * screen without touching the native decoder at all — MediaCodec/ANativeWindow doesn't care what
 * kind of Surface it's handed. See [onSurfaceReady] for how the decoder's target Surface is handed
 * back out to [com.metallic.chiaki.session.StreamSession].
 *
 * The EGL context is preserved across pause/resume ([GLSurfaceView.setPreserveEGLContextOnPause])
 * so the external texture/SurfaceTexture/Surface triple is created exactly once for this view's
 * lifetime — [Renderer.onSurfaceCreated] can otherwise re-fire after a resume, which would hand
 * out a second Surface the decoder never sees.
 */
class CasVideoSurfaceView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs)
{
	/** Invoked once, on the main thread, as soon as the decoder's target [Surface] exists. */
	var onSurfaceReady: ((Surface) -> Unit)? = null

	private val mainHandler = Handler(Looper.getMainLooper())
	private val renderer = CasRenderer()

	init
	{
		setEGLContextClientVersion(2)
		preserveEGLContextOnPause = true
		setRenderer(renderer)
		renderMode = RENDERMODE_WHEN_DIRTY
	}

	/** Stream resolution (not this view's on-screen size) — the CAS shader's tap spacing is
	 *  sized in decoded-texture texels, matching what the decoder was actually configured with
	 *  (see [com.metallic.chiaki.lib.ConnectVideoProfile]), not the on-screen viewport. */
	fun setVideoSize(width: Int, height: Int) = renderer.setVideoSize(width, height)

	fun setSharpening(enabled: Boolean, level: Int) = renderer.setSharpening(enabled, level)

	private inner class CasRenderer : Renderer
	{
		private var textureId = 0
		private var surfaceTexture: SurfaceTexture? = null
		private var program = 0
		private var aPositionLoc = 0
		private var aTexCoordLoc = 0
		private var uSTMatrixLoc = 0
		private var uTexelSizeLoc = 0
		private var uSharpnessLoc = 0
		private var uEnabledLoc = 0

		private val stMatrix = FloatArray(16)

		@Volatile private var videoWidth = 1
		@Volatile private var videoHeight = 1
		@Volatile private var sharpeningEnabled = false
		@Volatile private var sharpnessT = CAS_SHARPNESS_MIN

		fun setVideoSize(width: Int, height: Int)
		{
			videoWidth = width.coerceAtLeast(1)
			videoHeight = height.coerceAtLeast(1)
		}

		fun setSharpening(enabled: Boolean, level: Int)
		{
			sharpeningEnabled = enabled
			sharpnessT = (level.coerceIn(1, 10) / 10f).coerceIn(CAS_SHARPNESS_MIN, 1f)
			requestRender()
		}

		override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?)
		{
			// Context is preserved across pause/resume, so this only runs once per view lifetime.
			if(surfaceTexture != null)
				return

			GLES20.glClearColor(0f, 0f, 0f, 1f)

			val textures = IntArray(1)
			GLES20.glGenTextures(1, textures, 0)
			textureId = textures[0]
			GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
			GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

			val st = SurfaceTexture(textureId)
			st.setOnFrameAvailableListener { requestRender() }
			surfaceTexture = st

			program = buildProgram(VERTEX_SHADER, CAS_FRAGMENT_SHADER)
			aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
			aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
			uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
			uTexelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")
			uSharpnessLoc = GLES20.glGetUniformLocation(program, "uSharpness")
			uEnabledLoc = GLES20.glGetUniformLocation(program, "uEnabled")

			val surface = Surface(st)
			mainHandler.post { onSurfaceReady?.invoke(surface) }
		}

		override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int)
		{
			GLES20.glViewport(0, 0, width, height)
		}

		override fun onDrawFrame(gl: GL10?)
		{
			val st = surfaceTexture ?: return
			st.updateTexImage()
			st.getTransformMatrix(stMatrix)

			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
			GLES20.glUseProgram(program)

			GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
			GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

			quadVertices.position(0)
			GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE, quadVertices)
			GLES20.glEnableVertexAttribArray(aPositionLoc)

			quadTexCoords.position(0)
			GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE, quadTexCoords)
			GLES20.glEnableVertexAttribArray(aTexCoordLoc)

			GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)
			GLES20.glUniform2f(uTexelSizeLoc, 1f / videoWidth, 1f / videoHeight)
			GLES20.glUniform1f(uSharpnessLoc, sharpnessT)
			GLES20.glUniform1f(uEnabledLoc, if(sharpeningEnabled) 1f else 0f)

			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

			GLES20.glDisableVertexAttribArray(aPositionLoc)
			GLES20.glDisableVertexAttribArray(aTexCoordLoc)
		}
	}

	companion object
	{
		private const val TAG = "CasVideoSurfaceView"

		/** Slider value 1 still applies a small amount of sharpening rather than none — the
		 *  toggle, not the slider, is what fully disables the effect (uEnabled uniform). */
		private const val CAS_SHARPNESS_MIN = 0.1f

		private const val VERTEX_STRIDE = 2 * 4

		private val quadVertices = floatBufferOf(
			-1f, -1f,
			1f, -1f,
			-1f, 1f,
			1f, 1f
		)

		private val quadTexCoords = floatBufferOf(
			0f, 0f,
			1f, 0f,
			0f, 1f,
			1f, 1f
		)

		private fun floatBufferOf(vararg values: Float): FloatBuffer =
			ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
				put(values)
				position(0)
			}

		private const val VERTEX_SHADER = """
			attribute vec4 aPosition;
			attribute vec2 aTexCoord;
			uniform mat4 uSTMatrix;
			varying vec2 vTexCoord;
			void main()
			{
				gl_Position = aPosition;
				vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
			}
		"""

		// AMD FidelityFX CAS (sharpen-only, no upscale), 5-tap "plus" pattern. uSharpness is
		// the normalized 0..1 slider value; uEnabled gates the whole effect so a disabled
		// toggle is a pure passthrough regardless of whatever level the slider was left at.
		private const val CAS_FRAGMENT_SHADER = """
			#extension GL_OES_EGL_image_external : require
			precision mediump float;
			varying vec2 vTexCoord;
			uniform samplerExternalOES sTexture;
			uniform vec2 uTexelSize;
			uniform float uSharpness;
			uniform float uEnabled;

			void main()
			{
				vec3 centerColor = texture2D(sTexture, vTexCoord).rgb;
				if(uEnabled < 0.5)
				{
					gl_FragColor = vec4(centerColor, 1.0);
					return;
				}

				vec3 n = texture2D(sTexture, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb;
				vec3 s = texture2D(sTexture, vTexCoord + vec2(0.0,  uTexelSize.y)).rgb;
				vec3 w = texture2D(sTexture, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb;
				vec3 e = texture2D(sTexture, vTexCoord + vec2( uTexelSize.x, 0.0)).rgb;

				vec3 mn = min(min(min(min(n, s), w), e), centerColor);
				vec3 mx = max(max(max(max(n, s), w), e), centerColor);

				vec3 rcpMx = 1.0 / max(mx, vec3(0.0001));
				vec3 amp = clamp(min(mn, 2.0 - mx) * rcpMx, 0.0, 1.0);
				amp = inversesqrt(amp);

				float peak = 8.0 - 3.0 * uSharpness;
				vec3 weight = -1.0 / (amp * peak);
				vec3 rcpWeight = 1.0 / (1.0 + 4.0 * weight);

				vec3 window = n + s + w + e;
				vec3 sharpened = clamp((window * weight + centerColor) * rcpWeight, 0.0, 1.0);
				gl_FragColor = vec4(sharpened, 1.0);
			}
		"""

		private fun compileShader(type: Int, source: String): Int
		{
			val shader = GLES20.glCreateShader(type)
			GLES20.glShaderSource(shader, source)
			GLES20.glCompileShader(shader)
			val status = IntArray(1)
			GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
			if(status[0] == 0)
			{
				Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
				GLES20.glDeleteShader(shader)
				return 0
			}
			return shader
		}

		private fun buildProgram(vertexSource: String, fragmentSource: String): Int
		{
			val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
			val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
			val program = GLES20.glCreateProgram()
			GLES20.glAttachShader(program, vertexShader)
			GLES20.glAttachShader(program, fragmentShader)
			GLES20.glLinkProgram(program)
			val status = IntArray(1)
			GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
			if(status[0] == 0)
			{
				Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
			}
			return program
		}
	}
}
