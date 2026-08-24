// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.pylux.stream.R
import kotlin.math.abs

class AnalogStickView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	val radius: Float
	private val handleRadius: Float
	private val drawableBase: Drawable?
	private val drawableHandle: Drawable?

	var state = Vector(0f, 0f)
		private set(value)
		{
			field = value
			stateChangedCallback?.let { it(field) }
		}

	var stateChangedCallback: ((Vector) -> Unit)? = null

	private val touchTracker = TouchTracker().also {
		it.positionChangedCallback = this::updateState
	}

	private var center: Vector? = null
	var alwaysShow: Boolean = false
		set(value)
		{
			field = value
			if(value && center == null && width > 0 && height > 0)
				center = Vector(width / 2f, height / 2f)
			else if(!value && state == Vector(0f, 0f))
				center = null
			invalidate()
		}

	/**
	 * Same as state, but scaled to the circle
	 */
	private var handlePosition: Vector = Vector(0f, 0f)

	private val clipBoundsTmp = Rect()

	init
	{
		context.theme.obtainStyledAttributes(attrs, R.styleable.AnalogStickView, 0, 0).apply {
			radius = getDimension(R.styleable.AnalogStickView_radius, 0f)
			handleRadius = getDimension(R.styleable.AnalogStickView_handleRadius, 0f)
			drawableBase = getDrawable(R.styleable.AnalogStickView_drawableBase)
			drawableHandle = getDrawable(R.styleable.AnalogStickView_drawableHandle)
			recycle()
		}
	}

	/** The stick's base drawable is always given a square bounding box (same radius on both
	 *  axes), so on a layout region shorter than the stick's natural diameter — e.g. a wide/
	 *  short-aspect-ratio phone where the D-Pad's fixed height leaves less room below it than
	 *  usual — the view's own canvas doesn't have enough vertical space to show the full
	 *  circle, and it renders with its top and bottom edges flattened off instead of round.
	 *  Clamping to the view's own half-width/half-height keeps the drawn circle a true circle
	 *  (just a smaller one) on any screen, instead of a squashed oval. */
	private fun visibleCircleRadius() = minOf(radius + handleRadius, width / 2f, height / 2f)

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)

		val center = center
		if(center != null)
		{
			val circleRadius = visibleCircleRadius()
			drawableBase?.setBounds((center.x - circleRadius).toInt(), (center.y - circleRadius).toInt(), (center.x + circleRadius).toInt(), (center.y + circleRadius).toInt())
			drawableBase?.draw(canvas)

			val handleDrawRadius = minOf(handleRadius, circleRadius)
			val handleX = center.x + handlePosition.x * radius
			val handleY = center.y + handlePosition.y * radius
			drawableHandle?.setBounds((handleX - handleDrawRadius).toInt(), (handleY - handleDrawRadius).toInt(), (handleX + handleDrawRadius).toInt(),(handleY + handleDrawRadius).toInt())
			drawableHandle?.draw(canvas)
		}
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
	{
		super.onSizeChanged(w, h, oldw, oldh)
		if(alwaysShow && state == Vector(0f, 0f)) center = Vector(w / 2f, h / 2f)
	}

	private fun updateState(position: Vector?)
	{
		if(radius <= 0f)
			return

		if(position == null)
		{
			center = if(alwaysShow) Vector(width / 2f, height / 2f) else null
			state = Vector(0f, 0f)
			handlePosition = Vector(0f, 0f)
			invalidate()
			return
		}

		val center: Vector = this.center ?: position
		this.center = center

		val dir = position - center
		val length = dir.length
		if(length > 0)
		{
			val strength = if(length > radius) 1.0f else length / radius
			val dirNormalized = dir / length
			handlePosition = dirNormalized * strength
			val dirBoxNormalized =
				if(abs(dirNormalized.x) > abs(dirNormalized.y))
					dirNormalized / abs(dirNormalized.x)
				else
					dirNormalized / abs(dirNormalized.y)
			state = dirBoxNormalized * strength
		}
		else
		{
			handlePosition = Vector(0f, 0f)
			state = Vector(0f, 0f)
		}

		invalidate()
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		if(event.actionMasked == MotionEvent.ACTION_DOWN && alwaysShow && !isInsideVisibleStick(event.x, event.y))
			return false
		touchTracker.touchEvent(event)
		return true
	}

	private fun isInsideVisibleStick(x: Float, y: Float): Boolean
	{
		val center = center ?: return false
		val dx = x - center.x
		val dy = y - center.y
		val visibleRadius = visibleCircleRadius()
		return dx * dx + dy * dy <= visibleRadius * visibleRadius
	}
}
