// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay

import android.content.Context
import com.metallic.chiaki.cloudplay.api.CloudStreamingBackend
import com.metallic.chiaki.cloudplay.model.CloudStreamSession
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile

/**
 * Builds the [ConnectInfo] for a cloud (PSNow Catalog / PSCloud Library) stream from an
 * allocated [CloudStreamSession], and can re-run the whole allocation for the same game later
 * (used by the in-stream Quick Settings "refresh" action after resolution/bitrate/datacenter
 * settings change — those values are baked into the session at allocation time, so applying
 * new ones requires a fresh allocation, not just a reconnect).
 */
object CloudConnectInfoBuilder
{
	fun build(session: CloudStreamSession, preferences: Preferences, gameIdentifier: String): ConnectInfo
	{
		// Set codec based on service type (Qt lines 344-353): PSCLOUD: H.265/HEVC, PSNOW: H.264
		val codec = if(session.serviceType == "pscloud") Codec.CODEC_H265 else Codec.CODEC_H264

		val resolutionValue = if(session.serviceType == "pscloud")
			preferences.getCloudResolutionPscloud()
		else
			preferences.getCloudResolutionPsnow()

		val cloudBitrate = if(session.serviceType == "pscloud")
			preferences.getCloudBitratePscloud()
		else
			preferences.getCloudBitratePsnow()

		val (width, height) = when(resolutionValue)
		{
			1080 -> 1920 to 1080
			1440 -> 2560 to 1440
			2160 -> 3840 to 2160
			else -> 1280 to 720
		}

		val videoProfile = ConnectVideoProfile(
			width = width,
			height = height,
			maxFPS = 60,
			bitrate = cloudBitrate,
			codec = codec
		)

		return ConnectInfo(
			ps5 = session.platform == "ps5",
			host = session.serverIp, // Cloud mode: just the IP address (port is in cloudPort)
			registKey = ByteArray(0x10), // Empty for cloud (not used)
			morning = ByteArray(0x10), // Empty for cloud (not used)
			videoProfile = videoProfile,
			serviceType = session.serviceType,
			cloudGamePlatform = session.platform,
			cloudLaunchSpec = session.launchSpec,
			cloudHandshakeKey = session.handshakeKey,
			cloudSessionId = session.sessionId,
			cloudPort = session.serverPort,
			cloudPsnWrapperType = session.psnWrapperType,
			cloudMtuIn = session.mtuIn,
			cloudMtuOut = session.mtuOut,
			cloudRttUs = session.rttMs.toLong() * 1000L, // Convert ms to microseconds
			cloudGameIdentifier = gameIdentifier,
			cloudGameName = session.gameName,
			cloudOwnedEntitlementId = session.entitlementId
		)
	}

	/**
	 * Re-runs the cloud allocation flow for the same game a [current] cloud [ConnectInfo] was
	 * built from, producing a fresh [ConnectInfo] that reflects whatever resolution/bitrate/
	 * datacenter preferences are set right now. Fails if [current] wasn't a cloud session, or
	 * didn't carry the game-identifying fields [ConnectInfo.build] stores for this purpose.
	 */
	suspend fun refresh(
		context: Context,
		preferences: Preferences,
		current: ConnectInfo,
		onProgress: ((String) -> Unit)? = null,
		isCancelled: () -> Boolean = { false }
	): Result<ConnectInfo>
	{
		val serviceType = current.serviceType
			?: return Result.failure(IllegalStateException("Not a cloud session"))
		val gameIdentifier = current.cloudGameIdentifier
			?: return Result.failure(IllegalStateException("Missing game identifier for refresh"))

		val backend = CloudStreamingBackend(context, preferences)
		val result = backend.startCompleteCloudSession(
			serviceType = serviceType,
			gameIdentifier = gameIdentifier,
			gameName = current.cloudGameName ?: "",
			npssoToken = preferences.getNpssoToken(),
			ownedEntitlementId = current.cloudOwnedEntitlementId ?: "",
			ownedPlatform = current.cloudGamePlatform ?: "",
			onProgress = onProgress,
			isCancelled = isCancelled
		)

		return result.map { session -> build(session, preferences, gameIdentifier) }
	}
}
