// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch

/**
 * A throwaway [Surface] the video decoder can be pointed at while the app is backgrounded (see
 * [com.metallic.chiaki.session.StreamSession.enterBackground]), so decode keeps running via the
 * existing `AMediaCodec_setOutputSurface` swap path (video-decoder.c) instead of stalling once its
 * output buffer queue fills up with nobody consuming it.
 *
 * Frames delivered here are immediately discarded: a dedicated thread with its own minimal EGL
 * context (a 1x1 pbuffer, no on-screen surface at all) just calls [SurfaceTexture.updateTexImage]
 * as each one arrives, purely to free the buffer — no drawing happens, unlike CasVideoSurfaceView's
 * real rendering path, since there's nothing to show while there's no visible Activity.
 */
class BackgroundDrainSurface
{
	private val thread = HandlerThread("VideoDrainThread").apply { start() }
	private val handler = Handler(thread.looper)

	private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
	private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
	private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
	private var surfaceTexture: SurfaceTexture? = null

	val surface: Surface

	init
	{
		val ready = CountDownLatch(1)
		var created: Surface? = null
		handler.post {
			setUpEgl()

			val textures = IntArray(1)
			GLES20.glGenTextures(1, textures, 0)
			GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])

			val st = SurfaceTexture(textures[0])
			// Dispatched on this same thread/context, which is exactly what updateTexImage()
			// requires — it must be called with the GL context that owns the texture current
			// on the calling thread.
			st.setOnFrameAvailableListener({ texture -> texture.updateTexImage() }, handler)
			surfaceTexture = st
			created = Surface(st)
			ready.countDown()
		}
		ready.await()
		surface = created!!
	}

	private fun setUpEgl()
	{
		eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
		check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "BackgroundDrainSurface: eglGetDisplay failed" }

		val version = IntArray(2)
		check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "BackgroundDrainSurface: eglInitialize failed" }

		val configAttribs = intArrayOf(
			EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
			EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
			EGL14.EGL_RED_SIZE, 8,
			EGL14.EGL_GREEN_SIZE, 8,
			EGL14.EGL_BLUE_SIZE, 8,
			EGL14.EGL_NONE
		)
		val configs = arrayOfNulls<EGLConfig>(1)
		val numConfigs = IntArray(1)
		check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
			"BackgroundDrainSurface: eglChooseConfig failed"
		}
		val config = configs[0]!!

		val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
		eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
		check(eglContext != EGL14.EGL_NO_CONTEXT) { "BackgroundDrainSurface: eglCreateContext failed" }

		val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
		eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, pbufferAttribs, 0)
		check(eglSurface != EGL14.EGL_NO_SURFACE) { "BackgroundDrainSurface: eglCreatePbufferSurface failed" }

		check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
			"BackgroundDrainSurface: eglMakeCurrent failed"
		}
	}

	/** Tears down the drain thread/context and its SurfaceTexture. Not safe to use [surface]
	 *  after calling this. */
	fun release()
	{
		handler.post {
			surfaceTexture?.release()
			surfaceTexture = null
			if(eglDisplay != EGL14.EGL_NO_DISPLAY)
			{
				EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
				EGL14.eglDestroySurface(eglDisplay, eglSurface)
				EGL14.eglDestroyContext(eglDisplay, eglContext)
				EGL14.eglTerminate(eglDisplay)
				eglDisplay = EGL14.EGL_NO_DISPLAY
			}
		}
		thread.quitSafely()
	}
}
