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
import com.metallic.chiaki.common.Preferences
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
 * The sharpening kernel itself runs at a fixed strength (matching the AMD FidelityFX CAS
 * reference's own "contrast" constant), and the 1-10 level slider instead blends between the
 * original pixel and that fixed-strength result — see [CAS_FRAGMENT_SHADER]'s comment for why
 * this (rather than feeding the slider into the kernel's own peak/weight formula) is what gives
 * high slider values their noticeably more aggressive look instead of a flat linear ramp.
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
	private var sourceWidth = 1
	private var sourceHeight = 1
	private var fsrOutputEnabled = false

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
	fun setVideoSize(width: Int, height: Int)
	{
		sourceWidth = width
		sourceHeight = height
		renderer.setVideoSize(width, height)
		updateOutputSize()
	}

	fun setSharpening(enabled: Boolean, level: Int) = renderer.setSharpening(enabled, level)
	fun setFsr(enabled: Boolean, upscale: Boolean, sharpening: Int)
	{
		fsrOutputEnabled = enabled && upscale
		updateOutputSize()
		renderer.setFsr(enabled, upscale, sharpening)
	}

	private fun updateOutputSize()
	{
		mainHandler.post {
			when
			{
				fsrOutputEnabled && sourceHeight == 720 -> holder.setFixedSize(1920, 1080)
				fsrOutputEnabled && sourceHeight == 1080 -> holder.setFixedSize(2560, 1440)
				else -> holder.setSizeFromLayout()
			}
		}
	}

	private inner class CasRenderer : Renderer
	{
		private var textureId = 0
		private var surfaceTexture: SurfaceTexture? = null
		private var program = 0
		private var aPositionLoc = 0
		private var aTexCoordLoc = 0
		private var uSTMatrixLoc = 0
		private var uTexelSizeLoc = 0
		private var uLevelLoc = 0
		private var uEnabledLoc = 0
		private var easuProgram = 0
		private var aEasuPositionLoc = 0
		private var aEasuTexCoordLoc = 0
		private var uEasuSTMatrixLoc = 0
		private var uEasuInputSizeLoc = 0
		private var uEasuOutputSizeLoc = 0
		private var uEasuUpscaleLoc = 0
		private var rcasProgram = 0
		private var aRcasPositionLoc = 0
		private var aRcasTexCoordLoc = 0
		private var uRcasTexelSizeLoc = 0
		private var uRcasSharpnessLoc = 0
		private var fsrFramebuffer = 0
		private var fsrTexture = 0
		private var surfaceWidth = 1
		private var surfaceHeight = 1

		private val stMatrix = FloatArray(16)

		@Volatile private var videoWidth = 1
		@Volatile private var videoHeight = 1
		@Volatile private var sharpeningEnabled = false
		@Volatile private var sharpeningLevel = Preferences.CAS_SHARPENING_LEVEL_MIN
		@Volatile private var fsrEnabled = false
		@Volatile private var fsrUpscale = false
		@Volatile private var fsrSharpening = Preferences.FSR_SHARPENING_DEFAULT

		fun setVideoSize(width: Int, height: Int)
		{
			videoWidth = width.coerceAtLeast(1)
			videoHeight = height.coerceAtLeast(1)
		}

		fun setSharpening(enabled: Boolean, level: Int)
		{
			sharpeningEnabled = enabled
			sharpeningLevel = level.coerceIn(Preferences.CAS_SHARPENING_LEVEL_MIN, Preferences.CAS_SHARPENING_LEVEL_MAX)
			requestRender()
		}

		fun setFsr(enabled: Boolean, upscale: Boolean, sharpening: Int)
		{
			fsrEnabled = enabled
			fsrUpscale = upscale
			fsrSharpening = sharpening.coerceIn(Preferences.FSR_SHARPENING_MIN, Preferences.FSR_SHARPENING_MAX)
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
			uLevelLoc = GLES20.glGetUniformLocation(program, "uLevel")
			uEnabledLoc = GLES20.glGetUniformLocation(program, "uEnabled")

			// Locations are fixed for a program's lifetime once linked, and (per this override's
			// own early-return above) these programs are only ever built once — cached here rather
			// than re-resolved by name every onDrawFrame call.
			easuProgram = buildProgram(VERTEX_SHADER, EASU_FRAGMENT_SHADER)
			aEasuPositionLoc = GLES20.glGetAttribLocation(easuProgram, "aPosition")
			aEasuTexCoordLoc = GLES20.glGetAttribLocation(easuProgram, "aTexCoord")
			uEasuSTMatrixLoc = GLES20.glGetUniformLocation(easuProgram, "uSTMatrix")
			uEasuInputSizeLoc = GLES20.glGetUniformLocation(easuProgram, "uInputSize")
			uEasuOutputSizeLoc = GLES20.glGetUniformLocation(easuProgram, "uOutputSize")
			uEasuUpscaleLoc = GLES20.glGetUniformLocation(easuProgram, "uUpscale")

			rcasProgram = buildProgram(RCAS_VERTEX_SHADER, RCAS_FRAGMENT_SHADER)
			aRcasPositionLoc = GLES20.glGetAttribLocation(rcasProgram, "aPosition")
			aRcasTexCoordLoc = GLES20.glGetAttribLocation(rcasProgram, "aTexCoord")
			uRcasTexelSizeLoc = GLES20.glGetUniformLocation(rcasProgram, "uTexelSize")
			uRcasSharpnessLoc = GLES20.glGetUniformLocation(rcasProgram, "uSharpness")

			val surface = Surface(st)
			mainHandler.post { onSurfaceReady?.invoke(surface) }
		}

		override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int)
		{
			surfaceWidth = width.coerceAtLeast(1)
			surfaceHeight = height.coerceAtLeast(1)
			allocateFsrTarget(surfaceWidth, surfaceHeight)
			GLES20.glViewport(0, 0, width, height)
		}

		private fun allocateFsrTarget(width: Int, height: Int)
		{
			if(fsrTexture == 0)
			{
				val ids = IntArray(1)
				GLES20.glGenTextures(1, ids, 0); fsrTexture = ids[0]
				GLES20.glGenFramebuffers(1, ids, 0); fsrFramebuffer = ids[0]
			}
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fsrTexture)
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
			GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
			GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fsrFramebuffer)
			GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fsrTexture, 0)
			check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FSR framebuffer is incomplete" }
			GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
		}

		override fun onDrawFrame(gl: GL10?)
		{
			val st = surfaceTexture ?: return
			st.updateTexImage()
			st.getTransformMatrix(stMatrix)

			if(fsrEnabled)
			{
				GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fsrFramebuffer)
				GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
				drawExternal(easuProgram, aEasuPositionLoc, aEasuTexCoordLoc, uEasuSTMatrixLoc) {
					GLES20.glUniform2f(uEasuInputSizeLoc, videoWidth.toFloat(), videoHeight.toFloat())
					GLES20.glUniform2f(uEasuOutputSizeLoc, surfaceWidth.toFloat(), surfaceHeight.toFloat())
					GLES20.glUniform1f(uEasuUpscaleLoc, if(fsrUpscale) 1f else 0f)
				}
				GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
				GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
				drawRcas()
			}
			else drawExternal(program, aPositionLoc, aTexCoordLoc, uSTMatrixLoc) {
				GLES20.glUniform2f(uTexelSizeLoc, 1f / videoWidth, 1f / videoHeight)
				GLES20.glUniform1f(uLevelLoc, sharpeningLevel.toFloat())
				GLES20.glUniform1f(uEnabledLoc, if(sharpeningEnabled) 1f else 0f)
			}
		}

		private inline fun drawExternal(shaderProgram: Int, positionLoc: Int, texCoordLoc: Int, stMatrixLoc: Int, uniforms: () -> Unit)
		{
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glUseProgram(shaderProgram)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
			drawQuad(positionLoc, texCoordLoc)
			GLES20.glUniformMatrix4fv(stMatrixLoc, 1, false, stMatrix, 0)
			uniforms(); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
		}

		private fun drawRcas()
		{
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glUseProgram(rcasProgram)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fsrTexture)
			drawQuad(aRcasPositionLoc, aRcasTexCoordLoc)
			GLES20.glUniform2f(uRcasTexelSizeLoc, 1f / surfaceWidth, 1f / surfaceHeight)
			GLES20.glUniform1f(uRcasSharpnessLoc, fsrSharpening / 100f)
			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
		}

		private fun drawQuad(positionLoc: Int, texCoordLoc: Int)
		{
			quadVertices.position(0); GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE, quadVertices); GLES20.glEnableVertexAttribArray(positionLoc)
			quadTexCoords.position(0); GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE, quadTexCoords); GLES20.glEnableVertexAttribArray(texCoordLoc)
		}
	}

	companion object
	{
		private const val TAG = "CasVideoSurfaceView"

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

		private const val RCAS_VERTEX_SHADER = """
			attribute vec4 aPosition;
			attribute vec2 aTexCoord;
			varying vec2 vTexCoord;
			void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }
		"""

		// AMD FidelityFX CAS (sharpen-only, no upscale), 5-tap "plus" pattern. Matches the
		// approach used by better-xcloud's "Clarity Boost" shader: the sharpening kernel itself
		// runs at a *fixed* strength (peak derived from AMD's own reference contrast=0.8, i.e.
		// 8-3*0.8=5.6) rather than being driven by the slider — the slider instead blends
		// between the original pixel and that fixed-strength result via uLevel/2. Since the
		// blend factor isn't clamped to [0,1], levels above ~2 extrapolate past the nominal CAS
		// result rather than plateauing, which is what gives the top of the 1-10 range its
		// noticeably more aggressive look instead of a flat linear ramp. uEnabled gates the
		// whole effect so a disabled toggle is a pure passthrough regardless of the level the
		// slider was left at.
		private const val CAS_FRAGMENT_SHADER = """
			#extension GL_OES_EGL_image_external : require
			precision mediump float;
			varying vec2 vTexCoord;
			uniform samplerExternalOES sTexture;
			uniform vec2 uTexelSize;
			uniform float uLevel;
			uniform float uEnabled;

			const float CAS_PEAK = 5.6;

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

				vec3 weight = -1.0 / (amp * CAS_PEAK);
				vec3 rcpWeight = 1.0 / (1.0 + 4.0 * weight);

				vec3 window = n + s + w + e;
				vec3 casOutput = clamp((window * weight + centerColor) * rcpWeight, 0.0, 1.0);

				vec3 sharpened = clamp(mix(centerColor, casOutput, uLevel * 0.5), 0.0, 1.0);
				gl_FragColor = vec4(sharpened, 1.0);
			}
		"""

		// Direct GLSL ES port of AMD FidelityFX FSR 1's reference FsrEasuF routine.
		private const val EASU_FRAGMENT_SHADER = """
			#extension GL_OES_EGL_image_external : require
			precision highp float;
			uniform samplerExternalOES sTexture;
			uniform mat4 uSTMatrix;
			uniform vec2 uInputSize;
			uniform vec2 uOutputSize;
			uniform float uUpscale;
			varying vec2 vTexCoord;

			vec3 loadPixel(vec2 p) {
				vec2 uv = (p + 0.5) / uInputSize;
				uv = (uSTMatrix * vec4(uv, 0.0, 1.0)).xy;
				return texture2D(sTexture, uv).rgb;
			}
			float luma2(vec3 c) { return c.b * 0.5 + c.r * 0.5 + c.g; }
			void easuSet(inout vec2 dir, inout float len, vec2 pp, float w,
				float a, float b, float c, float d, float e) {
				float dc=d-c, cb=c-b, lenX=max(abs(dc),abs(cb));
				float dirX=d-b; dir.x += dirX*w;
				lenX=clamp(abs(dirX)/max(lenX,0.00001),0.0,1.0); len += lenX*lenX*w;
				float ec=e-c, ca=c-a, lenY=max(abs(ec),abs(ca));
				float dirY=e-a; dir.y += dirY*w;
				lenY=clamp(abs(dirY)/max(lenY,0.00001),0.0,1.0); len += lenY*lenY*w;
			}
			void easuTap(inout vec3 color, inout float weight, vec2 off, vec2 dir,
				vec2 len2, float lob, float clp, vec3 tap) {
				vec2 v=vec2(off.x*dir.x+off.y*dir.y, off.x*(-dir.y)+off.y*dir.x)*len2;
				float d2=min(dot(v,v),clp);
				float wB=0.4*d2-1.0, wA=lob*d2-1.0;
				wB=wB*wB; wA=wA*wA;
				float w=(1.5625*wB-0.5625)*wA;
				color += tap*w; weight += w;
			}
			void main() {
				if(uUpscale < 0.5) { gl_FragColor=texture2D(sTexture,vTexCoord); return; }
				vec2 p=(gl_FragCoord.xy-0.5)*(uInputSize/uOutputSize)-0.5;
				vec2 fp=floor(p), pp=p-fp;
				vec3 b=loadPixel(fp+vec2(0.0,-1.0)), c=loadPixel(fp+vec2(1.0,-1.0));
				vec3 e=loadPixel(fp+vec2(-1.0,0.0)), f=loadPixel(fp), g=loadPixel(fp+vec2(1.0,0.0)), h=loadPixel(fp+vec2(2.0,0.0));
				vec3 i=loadPixel(fp+vec2(-1.0,1.0)), j=loadPixel(fp+vec2(0.0,1.0)), k=loadPixel(fp+vec2(1.0,1.0)), l=loadPixel(fp+vec2(2.0,1.0));
				vec3 n=loadPixel(fp+vec2(0.0,2.0)), o=loadPixel(fp+vec2(1.0,2.0));
				float bL=luma2(b),cL=luma2(c),eL=luma2(e),fL=luma2(f),gL=luma2(g),hL=luma2(h);
				float iL=luma2(i),jL=luma2(j),kL=luma2(k),lL=luma2(l),nL=luma2(n),oL=luma2(o);
				vec2 dir=vec2(0.0); float len=0.0;
				easuSet(dir,len,pp,(1.0-pp.x)*(1.0-pp.y),bL,eL,fL,gL,jL);
				easuSet(dir,len,pp,pp.x*(1.0-pp.y),cL,fL,gL,hL,kL);
				easuSet(dir,len,pp,(1.0-pp.x)*pp.y,fL,iL,jL,kL,nL);
				easuSet(dir,len,pp,pp.x*pp.y,gL,jL,kL,lL,oL);
				float dirR=dot(dir,dir); if(dirR<0.000030517578125) dir=vec2(1.0,0.0); else dir*=inversesqrt(dirR);
				len=0.5*len; len*=len;
				float stretch=dot(dir,dir)/max(max(abs(dir.x),abs(dir.y)),0.00001);
				vec2 len2=vec2(1.0+(stretch-1.0)*len,1.0-0.5*len);
				float lob=0.5+(0.21-0.5)*len, clp=1.0/lob;
				vec3 min4=min(min(f,g),min(j,k)), max4=max(max(f,g),max(j,k));
				vec3 ac=vec3(0.0); float aw=0.0;
				easuTap(ac,aw,vec2(0,-1)-pp,dir,len2,lob,clp,b); easuTap(ac,aw,vec2(1,-1)-pp,dir,len2,lob,clp,c);
				easuTap(ac,aw,vec2(-1,1)-pp,dir,len2,lob,clp,i); easuTap(ac,aw,vec2(0,1)-pp,dir,len2,lob,clp,j);
				easuTap(ac,aw,vec2(0,0)-pp,dir,len2,lob,clp,f); easuTap(ac,aw,vec2(-1,0)-pp,dir,len2,lob,clp,e);
				easuTap(ac,aw,vec2(1,1)-pp,dir,len2,lob,clp,k); easuTap(ac,aw,vec2(2,1)-pp,dir,len2,lob,clp,l);
				easuTap(ac,aw,vec2(2,0)-pp,dir,len2,lob,clp,h); easuTap(ac,aw,vec2(1,0)-pp,dir,len2,lob,clp,g);
				easuTap(ac,aw,vec2(1,2)-pp,dir,len2,lob,clp,o); easuTap(ac,aw,vec2(0,2)-pp,dir,len2,lob,clp,n);
				gl_FragColor=vec4(clamp(ac/aw,min4,max4),1.0);
			}
		"""

		// Direct GLSL ES port of AMD FidelityFX FSR 1's FsrRcasF routine.
		private const val RCAS_FRAGMENT_SHADER = """
			precision highp float;
			uniform sampler2D sTexture;
			uniform vec2 uTexelSize;
			uniform float uSharpness;
			varying vec2 vTexCoord;
			float luma2(vec3 c){return c.b*0.5+c.r*0.5+c.g;}
			void main(){
				vec3 b=texture2D(sTexture,vTexCoord-vec2(0.0,uTexelSize.y)).rgb;
				vec3 d=texture2D(sTexture,vTexCoord-vec2(uTexelSize.x,0.0)).rgb;
				vec3 e=texture2D(sTexture,vTexCoord).rgb;
				vec3 f=texture2D(sTexture,vTexCoord+vec2(uTexelSize.x,0.0)).rgb;
				vec3 h=texture2D(sTexture,vTexCoord+vec2(0.0,uTexelSize.y)).rgb;
				vec3 mn4=min(min(b,d),min(f,h)), mx4=max(max(b,d),max(f,h));
				vec3 hitMin=min(mn4,e)/max(4.0*mx4,vec3(0.00001));
				vec3 hitMax=(vec3(1.0)-max(mx4,e))/min(4.0*mn4-vec3(4.0),vec3(-0.00001));
				vec3 lobes=max(-hitMin,hitMax);
				float lobe=max(-0.1875,min(max(lobes.r,max(lobes.g,lobes.b)),0.0))*uSharpness;
				gl_FragColor=vec4(clamp((lobe*(b+d+f+h)+e)/(4.0*lobe+1.0),0.0,1.0),1.0);
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
