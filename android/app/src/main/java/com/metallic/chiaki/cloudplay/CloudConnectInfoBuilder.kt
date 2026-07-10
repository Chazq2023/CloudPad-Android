// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay

import com.metallic.chiaki.cloudplay.model.CloudStreamSession
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile

/**
 * Builds the [ConnectInfo] for a cloud (PSNow Catalog / PSCloud Library) stream from an
 * allocated [CloudStreamSession].
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
}
