// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pylux.stream.R

/**
 * Bare-bones foreground service whose only job is holding an ongoing notification while a stream
 * keeps running in the background (see StreamActivity.onPause/onStop and
 * [com.metallic.chiaki.session.StreamSession.enterBackground]) — this is what stops Android from
 * reclaiming the process once nothing is visible on screen.
 *
 * It deliberately does not own the [com.metallic.chiaki.session.StreamSession] itself —
 * StreamActivity/StreamViewModel keep that exactly as before. This service only exists to elevate
 * the whole (single) process's priority for as long as it's running, and to surface a way back
 * (tap) or a way to stop (Disconnect action).
 */
class StreamBackgroundService : Service()
{
	companion object
	{
		private const val CHANNEL_ID = "stream_background"
		private const val NOTIFICATION_ID = 4201
		private const val ACTION_DISCONNECT = "com.metallic.chiaki.stream.action.DISCONNECT"

		/** Invoked once, on the main thread, when the notification's Disconnect action is
		 *  tapped. Set right before [start], cleared by [stop]. */
		private var disconnectListener: (() -> Unit)? = null

		/** True while a stream is running in the background — i.e. between [start] and [stop].
		 *  Tapping the home-screen launcher icon while StreamActivity is backgrounded fires a
		 *  fresh MainActivity intent (a pre-existing Android quirk: the launcher icon always
		 *  targets MainActivity, which gets pushed on top of the back stack rather than simply
		 *  bringing the already-running StreamActivity forward), so MainActivity checks this flag
		 *  to redirect straight back into the live stream instead of showing the library. */
		var isRunning = false
			private set

		fun start(context: Context, onDisconnect: () -> Unit)
		{
			disconnectListener = onDisconnect
			isRunning = true
			context.startForegroundService(Intent(context, StreamBackgroundService::class.java))
		}

		fun stop(context: Context)
		{
			disconnectListener = null
			isRunning = false
			context.stopService(Intent(context, StreamBackgroundService::class.java))
		}
	}

	override fun onCreate()
	{
		super.onCreate()
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		{
			val channel = NotificationChannel(
				CHANNEL_ID, getString(R.string.stream_background_channel_name), NotificationManager.IMPORTANCE_LOW
			)
			getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
	{
		if(intent?.action == ACTION_DISCONNECT)
		{
			isRunning = false
			disconnectListener?.invoke()
			stopSelf()
			return START_NOT_STICKY
		}

		val returnIntent = Intent(this, StreamActivity::class.java).setFlags(
			Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
		)
		val contentPendingIntent = PendingIntent.getActivity(
			this, 0, returnIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		val disconnectPendingIntent = PendingIntent.getService(
			this, 0,
			Intent(this, StreamBackgroundService::class.java).setAction(ACTION_DISCONNECT),
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		val notification = NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification_stream)
			.setContentTitle(getString(R.string.stream_background_notification_title))
			.setContentText(getString(R.string.stream_background_notification_text))
			.setContentIntent(contentPendingIntent)
			.addAction(0, getString(R.string.stream_background_notification_disconnect), disconnectPendingIntent)
			.setOngoing(true)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.build()

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
		else
			startForeground(NOTIFICATION_ID, notification)

		return START_NOT_STICKY
	}

	override fun onBind(intent: Intent?): IBinder? = null
}
