// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.metallic.chiaki.cloudplay.model.GamePlaytimeStats
import com.metallic.chiaki.cloudplay.repository.CloudGameRepository
import com.pylux.stream.R
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import kotlin.math.max
import kotlin.math.min

class Preferences(context: Context)
{
	private val tokenManager: SecureTokenManager = SecureTokenManager(context)
	
	enum class Resolution(val value: String, @StringRes val title: Int, val preset: VideoResolutionPreset)
	{
		RES_360P("360p", R.string.preferences_resolution_title_360p, VideoResolutionPreset.RES_360P),
		RES_540P("540p", R.string.preferences_resolution_title_540p, VideoResolutionPreset.RES_540P),
		RES_720P("720p", R.string.preferences_resolution_title_720p, VideoResolutionPreset.RES_720P),
		RES_1080P("1080p", R.string.preferences_resolution_title_1080p, VideoResolutionPreset.RES_1080P),
	}

	enum class FPS(val value: String, @StringRes val title: Int, val preset: VideoFPSPreset)
	{
		FPS_30("30", R.string.preferences_fps_title_30, VideoFPSPreset.FPS_30),
		FPS_60("60", R.string.preferences_fps_title_60, VideoFPSPreset.FPS_60)
	}

	enum class Codec(val value: String, @StringRes val title: Int, val codec: com.metallic.chiaki.lib.Codec)
	{
		CODEC_H264("h264", R.string.preferences_codec_title_h264, com.metallic.chiaki.lib.Codec.CODEC_H264),
		CODEC_H265("h265", R.string.preferences_codec_title_h265, com.metallic.chiaki.lib.Codec.CODEC_H265)
	}

	companion object {
		val resolutionDefault = Resolution.RES_720P
		val resolutionAll = Resolution.values()
		val fpsDefault = FPS.FPS_60
		val fpsAll = FPS.values()
		val codecDefault = Codec.CODEC_H265
		val codecAll = Codec.values()

		const val CLOUD_BITRATE_MIN_KBPS = 2000
		const val CLOUD_BITRATE_MAX_KBPS = 200000
		const val CLOUD_BITRATE_DEFAULT_KBPS = 20000

		/**
		 * Minimum bitrate (kbps) needed to keep a Cloud Play stream at the given resolution from
		 * starving the decoder/network and dropping frames. The bitrate slider is a single flat
		 * value shared across all resolutions, so without this a bitrate lowered for 720p would
		 * silently carry over when switching up to 1080p/1440p/2160p.
		 */
		fun recommendedCloudBitrateKbps(resolutionHeight: Int) = when
		{
			resolutionHeight >= 2160 -> 40000
			resolutionHeight >= 1440 -> 25000
			resolutionHeight >= 1080 -> 15000
			else -> 10000
		}

		private const val CLOUD_STORE_LOCALE_KEY = "cloud_store_locale"
		private const val LEGACY_CLOUD_LANGUAGE_PSCLOUD_KEY = "cloud_language_pscloud"

		private const val CLOUD_GAME_LANGUAGE_KEY = "cloud_game_language"
		private const val LEGACY_CLOUD_STREAM_LANGUAGE_KEY = "cloud_stream_language"

		private const val CLOUD_RESOLVED_STORE_COUNTRY_KEY = "cloud_resolved_store_country"
		private const val CLOUD_RESOLVED_STORE_LANG_KEY = "cloud_resolved_store_lang"
		private const val LEGACY_CLOUD_FALLBACK_REGION_KEY = "cloud_fallback_region"

		private const val CLOUD_CATALOG_NATIVE_MODE_KEY = "cloud_catalog_native_mode"
	}

	private val appContext = context.applicationContext
	private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when(key)
		{
			resolutionKey -> bitrateAutoSubject.onNext(bitrateAuto)
		}
	}.also { sharedPreferences.registerOnSharedPreferenceChangeListener(it) }

	private val resources = context.resources

	val discoveryEnabledKey get() = resources.getString(R.string.preferences_discovery_enabled_key)
	var discoveryEnabled
		get() = sharedPreferences.getBoolean(discoveryEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(discoveryEnabledKey, value).apply() }

	val onScreenControlsEnabledKey get() = resources.getString(R.string.preferences_on_screen_controls_enabled_key)
	var onScreenControlsEnabled
		get() = sharedPreferences.getBoolean(onScreenControlsEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(onScreenControlsEnabledKey, value).apply() }

	val touchpadOnlyEnabledKey get() = resources.getString(R.string.preferences_touchpad_only_enabled_key)
	var touchpadOnlyEnabled
		get() = sharedPreferences.getBoolean(touchpadOnlyEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(touchpadOnlyEnabledKey, value).apply() }

	val micEnabledKey get() = resources.getString(R.string.preferences_microphone_enabled_key)
	var micEnabled
		get() = sharedPreferences.getBoolean(micEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(micEnabledKey, value).apply() }

	val motionEnabledKey get() = resources.getString(R.string.preferences_motion_enabled_key)
	var motionEnabled
		get() = sharedPreferences.getBoolean(motionEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(motionEnabledKey, value).apply() }

	val buttonHapticEnabledKey get() = resources.getString(R.string.preferences_button_haptic_enabled_key)
	var buttonHapticEnabled
		get() = sharedPreferences.getBoolean(buttonHapticEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(buttonHapticEnabledKey, value).apply() }

	val logVerboseKey get() = resources.getString(R.string.preferences_log_verbose_key)
	var logVerbose
		get() = sharedPreferences.getBoolean(logVerboseKey, false)
		set(value) { sharedPreferences.edit().putBoolean(logVerboseKey, value).apply() }

	val showPerformanceOverlayKey get() = resources.getString(R.string.preferences_show_performance_overlay_key)
	var showPerformanceOverlay
		get() = sharedPreferences.getBoolean(showPerformanceOverlayKey, false)
		set(value) { sharedPreferences.edit().putBoolean(showPerformanceOverlayKey, value).apply() }

	val pipEnabledKey get() = resources.getString(R.string.preferences_pip_enabled_key)
	var pipEnabled
		get() = sharedPreferences.getBoolean(pipEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(pipEnabledKey, value).apply() }

	val swapCrossMoonKey get() = resources.getString(R.string.preferences_swap_cross_moon_key)
	var swapCrossMoon
		get() = sharedPreferences.getBoolean(swapCrossMoonKey, false)
		set(value) { sharedPreferences.edit().putBoolean(swapCrossMoonKey, value).apply() }

	val resolutionKey get() = resources.getString(R.string.preferences_resolution_key)
	var resolution
		get() = sharedPreferences.getString(resolutionKey, resolutionDefault.value)?.let { value ->
			Resolution.values().firstOrNull { it.value == value }
		} ?: resolutionDefault
		set(value) { sharedPreferences.edit().putString(resolutionKey, value.value).apply() }

	val fpsKey get() = resources.getString(R.string.preferences_fps_key)
	var fps
		get() = sharedPreferences.getString(fpsKey, fpsDefault.value)?.let { value ->
			FPS.values().firstOrNull { it.value == value }
		}  ?: fpsDefault
		set(value) { sharedPreferences.edit().putString(fpsKey, value.value).apply() }

	fun validateBitrate(bitrate: Int) = max(2000, min(50000, bitrate))
	val bitrateKey get() = resources.getString(R.string.preferences_bitrate_key)
	var bitrate
		get() = sharedPreferences.getInt(bitrateKey, 0).let { if(it == 0) null else validateBitrate(it) }
		set(value) { sharedPreferences.edit().putInt(bitrateKey, if(value != null) validateBitrate(value) else 0).apply() }
	val bitrateAuto get() = videoProfileDefaultBitrate.bitrate
	private val bitrateAutoSubject by lazy { BehaviorSubject.createDefault(bitrateAuto) }
	val bitrateAutoObservable: Observable<Int> get() = bitrateAutoSubject

	val codecKey get() = resources.getString(R.string.preferences_codec_key)
	var codec
		get() = sharedPreferences.getString(codecKey, codecDefault.value)?.let { value ->
			Codec.values().firstOrNull { it.value == value }
		}  ?: codecDefault
		set(value) { sharedPreferences.edit().putString(codecKey, value.value).apply() }

	private val videoProfileDefaultBitrate get() = ConnectVideoProfile.preset(resolution.preset, fps.preset, codec.codec)
	val videoProfile get() = videoProfileDefaultBitrate.let {
		val bitrate = bitrate
		if(bitrate == null)
			it
		else
			it.copy(bitrate = bitrate)
	}

	// Cloud Play settings
	/**
	 * Get NPSSO token from secure storage
	 */
	fun getNpssoToken(): String
	{
		return tokenManager.getNpssoToken()
	}

	/**
	 * Save NPSSO token to secure storage
	 */
	fun setNpssoToken(token: String)
	{
		tokenManager.saveNpssoToken(token)
	}
	
	/**
	 * Check if NPSSO token exists
	 */
	fun hasNpssoToken(): Boolean
	{
		return tokenManager.hasNpssoToken()
	}
	
	/**
	 * Clear NPSSO token (logout)
	 */
	fun clearNpssoToken()
	{
		tokenManager.clearNpssoToken()
	}

	// ==========================================================================
	// PSN Remote Play token storage (for holepunch-based console connections)
	// These are separate from the NPSSO token used for cloud play
	// ==========================================================================
	private val PSN_AUTH_TOKEN_KEY = "psn_rp_auth_token"
	private val PSN_REFRESH_TOKEN_KEY = "psn_rp_refresh_token"
	private val PSN_AUTH_TOKEN_EXPIRY_KEY = "psn_rp_auth_token_expiry"
	private val PSN_ACCOUNT_ID_KEY = "psn_rp_account_id"
	private val PSN_DUID_KEY = "psn_rp_duid"

	var psnAuthToken: String
		get() = sharedPreferences.getString(PSN_AUTH_TOKEN_KEY, "") ?: ""
		set(value) { sharedPreferences.edit().putString(PSN_AUTH_TOKEN_KEY, value).apply() }

	var psnRefreshToken: String
		get() = sharedPreferences.getString(PSN_REFRESH_TOKEN_KEY, "") ?: ""
		set(value) { sharedPreferences.edit().putString(PSN_REFRESH_TOKEN_KEY, value).apply() }

	var psnAuthTokenExpiry: Long
		get() = sharedPreferences.getLong(PSN_AUTH_TOKEN_EXPIRY_KEY, 0L)
		set(value) { sharedPreferences.edit().putLong(PSN_AUTH_TOKEN_EXPIRY_KEY, value).apply() }

	/** Base64-encoded 8-byte PSN account ID */
	var psnAccountId: String
		get() = sharedPreferences.getString(PSN_ACCOUNT_ID_KEY, "") ?: ""
		set(value) { sharedPreferences.edit().putString(PSN_ACCOUNT_ID_KEY, value).apply() }

	/** Client device DUID for PSN holepunch auth (generated once, reused) */
	var psnDuid: String
		get() = sharedPreferences.getString(PSN_DUID_KEY, "") ?: ""
		set(value) { sharedPreferences.edit().putString(PSN_DUID_KEY, value).apply() }

	val hasPsnRemotePlayTokens: Boolean
		get() = psnAuthToken.isNotEmpty() && psnRefreshToken.isNotEmpty()

	val isPsnTokenExpired: Boolean
		get()
		{
			val expiry = psnAuthTokenExpiry
			if(expiry == 0L) return true
			// Give 60 seconds buffer
			return System.currentTimeMillis() + 60_000 >= expiry
		}

	fun clearPsnRemotePlayTokens()
	{
		sharedPreferences.edit()
			.remove(PSN_AUTH_TOKEN_KEY)
			.remove(PSN_REFRESH_TOKEN_KEY)
			.remove(PSN_AUTH_TOKEN_EXPIRY_KEY)
			.remove(PSN_ACCOUNT_ID_KEY)
			.apply()
	}

	// ==========================================================================
	// PSN Trophy token storage — separate scope/client from the Remote Play tokens above,
	// exchanged from the same stored NPSSO cookie (see PsnTrophyTokenManager).
	// ==========================================================================
	private val PSN_TROPHY_AUTH_TOKEN_KEY = "psn_trophy_auth_token"
	private val PSN_TROPHY_REFRESH_TOKEN_KEY = "psn_trophy_refresh_token"
	private val PSN_TROPHY_AUTH_TOKEN_EXPIRY_KEY = "psn_trophy_auth_token_expiry"

	var psnTrophyAuthToken: String
		get() = sharedPreferences.getString(PSN_TROPHY_AUTH_TOKEN_KEY, "") ?: ""
		set(value) { sharedPreferences.edit().putString(PSN_TROPHY_AUTH_TOKEN_KEY, value).apply() }

	var psnTrophyRefreshToken: String
		get() = sharedPreferences.getString(PSN_TROPHY_REFRESH_TOKEN_KEY, "") ?: ""
		set(value) { sharedPreferences.edit().putString(PSN_TROPHY_REFRESH_TOKEN_KEY, value).apply() }

	var psnTrophyAuthTokenExpiry: Long
		get() = sharedPreferences.getLong(PSN_TROPHY_AUTH_TOKEN_EXPIRY_KEY, 0L)
		set(value) { sharedPreferences.edit().putLong(PSN_TROPHY_AUTH_TOKEN_EXPIRY_KEY, value).apply() }

	// Only the access token is required here — Sony's response for this client/scope doesn't
	// always include a refresh_token, which used to make this permanently false and force a
	// full NPSSO->code->token re-exchange on every single call (confirmed live: every ~30s poll
	// from TrophyUnlockWatcher was re-authenticating from scratch instead of reusing the still-
	// valid cached access token). getValidToken()/refreshToken() already fall back to a fresh
	// exchange once the access token actually expires and no refresh token is available.
	val hasPsnTrophyTokens: Boolean
		get() = psnTrophyAuthToken.isNotEmpty()

	val isPsnTrophyTokenExpired: Boolean
		get()
		{
			val expiry = psnTrophyAuthTokenExpiry
			if (expiry == 0L) return true
			return System.currentTimeMillis() + 60_000 >= expiry
		}

	// Cached account-wide trophy titles list (Trophies feature) — avoids re-fetching all ~hundreds
	// of titles on every "Trophies" menu tap. Serialization lives in TrophyService to avoid
	// duplicating its JSON parsing here.
	private val TROPHY_TITLES_CACHE_KEY = "trophy_titles_cache"
	private val TROPHY_TITLES_CACHE_FETCHED_AT_KEY = "trophy_titles_cache_fetched_at"
	private val TROPHY_TITLES_CACHE_MAX_AGE_MS = 60 * 60 * 1000L // 1 hour

	fun getCachedTrophyTitlesJson(): String? = sharedPreferences.getString(TROPHY_TITLES_CACHE_KEY, null)

	val isTrophyTitlesCacheFresh: Boolean
		get()
		{
			val fetchedAt = sharedPreferences.getLong(TROPHY_TITLES_CACHE_FETCHED_AT_KEY, 0L)
			if (fetchedAt == 0L) return false
			return System.currentTimeMillis() - fetchedAt < TROPHY_TITLES_CACHE_MAX_AGE_MS
		}

	fun setCachedTrophyTitlesJson(json: String)
	{
		sharedPreferences.edit()
			.putString(TROPHY_TITLES_CACHE_KEY, json)
			.putLong(TROPHY_TITLES_CACHE_FETCHED_AT_KEY, System.currentTimeMillis())
			.apply()
	}

	// Cached friends list (Friends feature) — same shape as the trophy titles cache above, but a
	// much shorter TTL: presence (online status/current game) goes stale within minutes, unlike
	// trophy titles which barely change. Serialization lives in FriendsService, same reasoning as
	// TrophyService's above.
	private val FRIENDS_CACHE_KEY = "friends_cache"
	private val FRIENDS_CACHE_FETCHED_AT_KEY = "friends_cache_fetched_at"
	private val FRIENDS_CACHE_MAX_AGE_MS = 2 * 60 * 1000L // 2 minutes

	fun getCachedFriendsJson(): String? = sharedPreferences.getString(FRIENDS_CACHE_KEY, null)

	val isFriendsCacheFresh: Boolean
		get()
		{
			val fetchedAt = sharedPreferences.getLong(FRIENDS_CACHE_FETCHED_AT_KEY, 0L)
			if (fetchedAt == 0L) return false
			return System.currentTimeMillis() - fetchedAt < FRIENDS_CACHE_MAX_AGE_MS
		}

	fun setCachedFriendsJson(json: String)
	{
		sharedPreferences.edit()
			.putString(FRIENDS_CACHE_KEY, json)
			.putLong(FRIENDS_CACHE_FETCHED_AT_KEY, System.currentTimeMillis())
			.apply()
	}

	// Store/catalog locale and game-language settings.
	// Store locale controls PS Store/Kamaji catalog/container requests.
	// Game language controls the actual Gaikai stream language.
	fun migrateLocaleIfNeeded()
	{
		// Legacy migration is now handled lazily by getCloudStoreLocale()
		// and getCloudGameLanguage().
	}

	private fun deviceDefaultStoreLocale(): String
	{
		val deviceLocale = java.util.Locale.getDefault()
		val language = deviceLocale.language.takeIf { it.isNotEmpty() } ?: "en"
		val country = deviceLocale.country.takeIf { it.isNotEmpty() } ?: "US"
		return "$language-${country.uppercase()}"
	}

	fun isCloudStoreLocaleConfigured(): Boolean =
		sharedPreferences.contains(CLOUD_STORE_LOCALE_KEY) ||
			sharedPreferences.contains(LEGACY_CLOUD_LANGUAGE_PSCLOUD_KEY)

	private fun migrateCloudStoreLocaleIfNeeded(): String
	{
		if (sharedPreferences.contains(CLOUD_STORE_LOCALE_KEY))
		{
			return sharedPreferences.getString(CLOUD_STORE_LOCALE_KEY, deviceDefaultStoreLocale())
				?: deviceDefaultStoreLocale()
		}

		val legacy = sharedPreferences.getString(LEGACY_CLOUD_LANGUAGE_PSCLOUD_KEY, null)

		if (!legacy.isNullOrEmpty())
		{
			sharedPreferences.edit()
				.putString(CLOUD_STORE_LOCALE_KEY, legacy)
				.apply()
			return legacy
		}

		return deviceDefaultStoreLocale()
	}

	fun getCloudStoreLocale(): String = migrateCloudStoreLocaleIfNeeded()

	fun setCloudStoreLocale(value: String)
	{
		val configured = isCloudStoreLocaleConfigured()
		val previous = getCloudStoreLocale()

		if (configured && previous == value) return

		sharedPreferences.edit()
			.putString(CLOUD_STORE_LOCALE_KEY, value)
			.apply()

		Log.i(
			"Preferences",
			"Cloud store locale ${if (configured) "changed" else "configured"}: $previous -> $value"
		)

		CloudGameRepository.invalidateCatalogCache(appContext, "locale change")
	}

	fun noteCloudStoreLocaleSettled(value: String)
	{
		if (value.isEmpty()) return
		if (isCloudStoreLocaleConfigured() && getCloudStoreLocale() == value) return

		sharedPreferences.edit()
			.putString(CLOUD_STORE_LOCALE_KEY, value)
			.apply()

		Log.i("Preferences", "Cloud store locale settled: $value")
	}

	fun setCloudStoreLocaleFromSession(language: String?, country: String?)
	{
		val locale = com.metallic.chiaki.cloudplay.CloudLocale.fromSession(language, country)
			?: return

		if (isCloudStoreLocaleConfigured())
		{
			val storedCountry =
				com.metallic.chiaki.cloudplay.CloudLocale.parseStorePath(getCloudStoreLocale()).first

			val sessionCountry =
				com.metallic.chiaki.cloudplay.CloudLocale.parseStorePath(locale).first

			if (storedCountry == sessionCountry)
			{
				Log.i(
					"Preferences",
					"Kamaji session country unchanged ($sessionCountry), keeping ${getCloudStoreLocale()}"
				)
				return
			}
		}

		setCloudStoreLocale(locale)
	}

	private fun migrateCloudGameLanguageIfNeeded(): String
	{
		if (sharedPreferences.contains(CLOUD_GAME_LANGUAGE_KEY))
		{
			return sharedPreferences.getString(CLOUD_GAME_LANGUAGE_KEY, "") ?: ""
		}

		val legacy = sharedPreferences.getString(LEGACY_CLOUD_STREAM_LANGUAGE_KEY, "") ?: ""

		sharedPreferences.edit()
			.putString(CLOUD_GAME_LANGUAGE_KEY, legacy)
			.apply()

		return legacy
	}

	fun getCloudGameLanguage(): String = migrateCloudGameLanguageIfNeeded()

	fun setCloudGameLanguage(value: String)
	{
		sharedPreferences.edit()
			.putString(CLOUD_GAME_LANGUAGE_KEY, value)
			.apply()
	}

	fun getCloudResolvedStoreCountry(): String
	{
		if (sharedPreferences.contains(CLOUD_RESOLVED_STORE_COUNTRY_KEY))
		{
			return sharedPreferences.getString(CLOUD_RESOLVED_STORE_COUNTRY_KEY, "") ?: ""
		}

		val legacy = sharedPreferences.getString(LEGACY_CLOUD_FALLBACK_REGION_KEY, "") ?: ""

		sharedPreferences.edit()
			.putString(CLOUD_RESOLVED_STORE_COUNTRY_KEY, legacy)
			.apply()

		return legacy
	}

	fun setCloudResolvedStoreCountry(country: String)
	{
		sharedPreferences.edit()
			.putString(CLOUD_RESOLVED_STORE_COUNTRY_KEY, country)
			.apply()
	}

	fun getCloudResolvedStoreLang(): String =
		sharedPreferences.getString(CLOUD_RESOLVED_STORE_LANG_KEY, "") ?: ""

	fun setCloudResolvedStoreLang(lang: String)
	{
		sharedPreferences.edit()
			.putString(CLOUD_RESOLVED_STORE_LANG_KEY, lang)
			.apply()
	}

	fun getCloudCatalogNativeMode(): Boolean
	{
		if (sharedPreferences.contains(CLOUD_CATALOG_NATIVE_MODE_KEY))
		{
			return sharedPreferences.getBoolean(CLOUD_CATALOG_NATIVE_MODE_KEY, true)
		}

		val native = getCloudResolvedStoreCountry().isEmpty()

		sharedPreferences.edit()
			.putBoolean(CLOUD_CATALOG_NATIVE_MODE_KEY, native)
			.apply()

		return native
	}

	fun setCloudCatalogNativeMode(nativeMode: Boolean)
	{
		sharedPreferences.edit()
			.putBoolean(CLOUD_CATALOG_NATIVE_MODE_KEY, nativeMode)
			.apply()
	}

	fun isCloudCatalogIsForeign(): Boolean = !getCloudCatalogNativeMode()

	// Compatibility wrappers so existing code paths keep compiling.
	fun getCloudLanguage(): String = getCloudStoreLocale()
	fun setCloudLanguage(value: String) = setCloudStoreLocale(value)
	fun isCloudLanguageConfigured(): Boolean = isCloudStoreLocaleConfigured()
	fun setCloudLanguageFromSession(language: String?, country: String?) = setCloudStoreLocaleFromSession(language, country)

	fun getRawStoredLocale(): String? =
		sharedPreferences.getString(CLOUD_STORE_LOCALE_KEY, null)
			?: sharedPreferences.getString(LEGACY_CLOUD_LANGUAGE_PSCLOUD_KEY, null)
	
	// Cloud resolution settings (matching Qt GetCloudResolutionPSNOW/SetCloudResolutionPSNOW)
	val cloudResolutionPsnowKey get() = resources.getString(R.string.preferences_cloud_resolution_psnow_key)
	fun getCloudResolutionPsnow(): Int
	{
		return sharedPreferences.getString(cloudResolutionPsnowKey, "720")?.toIntOrNull() ?: 720
	}
	
	fun setCloudResolutionPsnow(value: Int)
	{
		sharedPreferences.edit().putString(cloudResolutionPsnowKey, value.toString()).apply()
		val recommended = recommendedCloudBitrateKbps(value)
		if(getCloudBitratePsnow() < recommended)
			setCloudBitratePsnow(recommended)
	}
	
	// Cloud resolution settings for PSCloud (matching Qt GetCloudResolutionPSCloud/SetCloudResolutionPSCloud)
	val cloudResolutionPscloudKey get() = resources.getString(R.string.preferences_cloud_resolution_pscloud_key)
	fun getCloudResolutionPscloud(): Int
	{
		return sharedPreferences.getString(cloudResolutionPscloudKey, "720")?.toIntOrNull() ?: 720
	}
	
	fun setCloudResolutionPscloud(value: Int)
	{
		sharedPreferences.edit().putString(cloudResolutionPscloudKey, value.toString()).apply()
		val recommended = recommendedCloudBitrateKbps(value)
		if(getCloudBitratePscloud() < recommended)
			setCloudBitratePscloud(recommended)
	}

	// Cloud bitrate settings
	val cloudBitratePsnowKey get() = resources.getString(R.string.preferences_cloud_bitrate_psnow_key)
	val cloudBitratePscloudKey get() = resources.getString(R.string.preferences_cloud_bitrate_pscloud_key)

	private fun clampCloudBitrateKbps(value: Int): Int =
		value.coerceIn(CLOUD_BITRATE_MIN_KBPS, CLOUD_BITRATE_MAX_KBPS)

	fun getCloudBitratePsnow(): Int {
		val legacy = sharedPreferences.getInt("cloud_bitrate", CLOUD_BITRATE_DEFAULT_KBPS)
		return clampCloudBitrateKbps(sharedPreferences.getInt(cloudBitratePsnowKey, legacy))
	}

	fun setCloudBitratePsnow(value: Int) {
		sharedPreferences.edit()
			.putInt(cloudBitratePsnowKey, clampCloudBitrateKbps(value))
			.apply()
	}

	fun getCloudBitratePscloud(): Int {
		val legacy = sharedPreferences.getInt("cloud_bitrate", CLOUD_BITRATE_DEFAULT_KBPS)
		return clampCloudBitrateKbps(sharedPreferences.getInt(cloudBitratePscloudKey, legacy))
	}

	fun setCloudBitratePscloud(value: Int) {
		sharedPreferences.edit()
			.putInt(cloudBitratePscloudKey, clampCloudBitrateKbps(value))
			.apply()
	}

	// Cloud datacenter settings (matching Qt GetCloudDatacenterPSNOW/SetCloudDatacenterPSNOW)
	val cloudDatacenterPsnowKey get() = resources.getString(R.string.preferences_cloud_datacenter_psnow_key)
	fun getCloudDatacenterPsnow(): String
	{
		return sharedPreferences.getString(cloudDatacenterPsnowKey, "Auto") ?: "Auto"
	}

	fun setCloudDatacenterPsnow(value: String)
	{
		sharedPreferences.edit().putString(cloudDatacenterPsnowKey, value).apply()
	}

	// Cloud datacenters JSON (matching Qt GetCloudDatacentersJsonPSNOW/SetCloudDatacentersJsonPSNOW)
	val cloudDatacentersJsonPsnowKey get() = resources.getString(R.string.preferences_cloud_datacenters_json_psnow_key)
	fun getCloudDatacentersJsonPsnow(): String
	{
		return sharedPreferences.getString(cloudDatacentersJsonPsnowKey, "") ?: ""
	}

	fun setCloudDatacentersJsonPsnow(json: String)
	{
		sharedPreferences.edit().putString(cloudDatacentersJsonPsnowKey, json).apply()
	}

	// PSCloud datacenter settings (matching Qt GetCloudDatacenterPSCloud/SetCloudDatacenterPSCloud)
	val cloudDatacenterPscloudKey get() = resources.getString(R.string.preferences_cloud_datacenter_pscloud_key)
	fun getCloudDatacenterPscloud(): String
	{
		return sharedPreferences.getString(cloudDatacenterPscloudKey, "Auto") ?: "Auto"
	}

	fun setCloudDatacenterPscloud(value: String)
	{
		sharedPreferences.edit().putString(cloudDatacenterPscloudKey, value).apply()
	}

	// PSCloud datacenters JSON (matching Qt GetCloudDatacentersJsonPSCloud/SetCloudDatacentersJsonPSCloud)
	val cloudDatacentersJsonPscloudKey get() = resources.getString(R.string.preferences_cloud_datacenters_json_pscloud_key)
	fun getCloudDatacentersJsonPscloud(): String
	{
		return sharedPreferences.getString(cloudDatacentersJsonPscloudKey, "") ?: ""
	}

	fun setCloudDatacentersJsonPscloud(json: String)
	{
		sharedPreferences.edit().putString(cloudDatacentersJsonPscloudKey, json).apply()
	}

	// Cloud Play UI state
	private val LAST_CLOUD_SECTION_KEY = "last_cloud_section"
	private val PSCLOUD_FILTER_OWNED_KEY = "pscloud_filter_owned"
	private val LAST_MAIN_TAB_KEY = "last_main_tab"
	private val CLOUD_SORT_STATE_KEY = "cloud_sort_state"
	private val PSCLOUD_STREAMABILITY_FILTER_KEY = "pscloud_streamability_filter"
	private val FAVORITE_GAMES_KEY = "favorite_games"
	private val CONFIRMED_STREAMABLE_KEY = "confirmed_streamable_status"
	private val PSNOW_FILTER_FAVORITES_KEY = "psnow_filter_favorites"
	private val PSCLOUD_FILTER_FAVORITES_KEY = "pscloud_filter_favorites"
	private val LICENSE_AGREED_KEY = "license_agreed"
	private val TOTAL_STREAM_TIME_MS_KEY = "total_stream_time_ms"
	private val GAME_PLAYTIME_KEY = "game_playtime_stats"
	/** Migrated from one-time flag; removed after [lastDonationPromptWallClockMs] is seeded. */
	private val DONATION_STREAM_AUTO_PROMPT_SHOWN_KEY = "donation_stream_auto_prompt_shown"
	private val LAST_DONATION_PROMPT_WALL_MS_KEY = "last_donation_prompt_wall_ms"
	private val DONATION_PAYWALL_SHOW_COUNT_KEY = "donation_paywall_show_count"

	/** Total times the support paywall was opened (1-based after [incrementDonationPaywallShowCount]). */
	val donationPaywallShowCount: Int
		get() = sharedPreferences.getInt(DONATION_PAYWALL_SHOW_COUNT_KEY, 0)

	/** @return New total paywall open count after increment. */
	fun incrementDonationPaywallShowCount(): Int
	{
		val next = donationPaywallShowCount + 1
		sharedPreferences.edit().putInt(DONATION_PAYWALL_SHOW_COUNT_KEY, next).apply()
		return next
	}

	fun getLastCloudSection(): String
	{
		return sharedPreferences.getString(LAST_CLOUD_SECTION_KEY, "psnow") ?: "psnow"
	}

	fun setLastCloudSection(section: String)
	{
		sharedPreferences.edit().putString(LAST_CLOUD_SECTION_KEY, section).apply()
	}

	fun getPsCloudFilterOwned(): Boolean
	{
		return sharedPreferences.getBoolean(PSCLOUD_FILTER_OWNED_KEY, false)
	}

	fun setPsCloudFilterOwned(isOwned: Boolean)
	{
		sharedPreferences.edit().putBoolean(PSCLOUD_FILTER_OWNED_KEY, isOwned).apply()
	}

	fun getLastMainTab(): Int
	{
		return sharedPreferences.getInt(LAST_MAIN_TAB_KEY, 0) // Default to Remote Play (0)
	}

	fun setLastMainTab(tabPosition: Int)
	{
		sharedPreferences.edit().putInt(LAST_MAIN_TAB_KEY, tabPosition).apply()
	}
	
	fun getCloudSortState(): Int
	{
		return sharedPreferences.getInt(CLOUD_SORT_STATE_KEY, 0) // Default to Name: A→Z (0)
	}
	
	fun setCloudSortState(sortState: Int)
	{
		sharedPreferences.edit().putInt(CLOUD_SORT_STATE_KEY, sortState).apply()
	}

	/** PS5 Library streamability filter: 0=All, 1=Streamable, 2=Non-streamable, 3=Not Verified. */
	fun getPsCloudStreamabilityFilter(): Int
	{
		return sharedPreferences.getInt(PSCLOUD_STREAMABILITY_FILTER_KEY, 0)
	}

	fun setPsCloudStreamabilityFilter(filterState: Int)
	{
		sharedPreferences.edit().putInt(PSCLOUD_STREAMABILITY_FILTER_KEY, filterState).apply()
	}

	// Favorite games management
	fun getFavoriteGames(): Set<String>
	{
		return sharedPreferences.getStringSet(FAVORITE_GAMES_KEY, emptySet()) ?: emptySet()
	}
	
	fun addFavoriteGame(productId: String)
	{
		val favorites = getFavoriteGames().toMutableSet()
		favorites.add(productId)
		sharedPreferences.edit().putStringSet(FAVORITE_GAMES_KEY, favorites).apply()
	}
	
	fun removeFavoriteGame(productId: String)
	{
		val favorites = getFavoriteGames().toMutableSet()
		favorites.remove(productId)
		sharedPreferences.edit().putStringSet(FAVORITE_GAMES_KEY, favorites).apply()
	}
	
	fun isFavoriteGame(productId: String): Boolean
	{
		return getFavoriteGames().contains(productId)
	}

	/**
	 * Streamability overrides confirmed by an actual launch attempt (success or Gaikai-rejected
	 * failure), keyed by productId. Takes priority over the catalog-derived best-guess shown
	 * before any real attempt, and persists across library refreshes and app restarts.
	 */
	fun getConfirmedStreamableOverrides(): Map<String, Boolean>
	{
		val json = sharedPreferences.getString(CONFIRMED_STREAMABLE_KEY, null) ?: return emptyMap()
		return try
		{
			val obj = org.json.JSONObject(json)
			val map = mutableMapOf<String, Boolean>()
			obj.keys().forEach { key -> map[key] = obj.getBoolean(key) }
			map
		}
		catch (e: Exception)
		{
			Log.w("Preferences", "Error reading confirmed streamable overrides", e)
			emptyMap()
		}
	}

	fun setConfirmedStreamable(productId: String, streamable: Boolean)
	{
		val current = getConfirmedStreamableOverrides().toMutableMap()
		current[productId] = streamable
		val obj = org.json.JSONObject()
		current.forEach { (key, value) -> obj.put(key, value) }
		sharedPreferences.edit().putString(CONFIRMED_STREAMABLE_KEY, obj.toString()).apply()
	}

	/**
	 * Per-game playtime stats (PS3/PS4 Catalog and PS5 Library), keyed by [CloudGame.productId].
	 * Written by [recordPlaySession] once per stream disconnect (see StreamActivity.flushStreamTimeSegment).
	 */
	fun getGamePlaytimeStats(): Map<String, GamePlaytimeStats>
	{
		val json = sharedPreferences.getString(GAME_PLAYTIME_KEY, null) ?: return emptyMap()
		return try
		{
			val obj = org.json.JSONObject(json)
			val map = mutableMapOf<String, GamePlaytimeStats>()
			obj.keys().forEach { key ->
				val entry = obj.getJSONObject(key)
				map[key] = GamePlaytimeStats(
					totalPlaytimeMs = entry.optLong("totalPlaytimeMs", 0L),
					lastPlayedMs = entry.optLong("lastPlayedMs", 0L),
					longestSessionMs = entry.optLong("longestSessionMs", 0L)
				)
			}
			map
		}
		catch (e: Exception)
		{
			Log.w("Preferences", "Error reading game playtime stats", e)
			emptyMap()
		}
	}

	fun getGamePlaytimeStats(productId: String): GamePlaytimeStats? = getGamePlaytimeStats()[productId]

	fun getLastPlayedMs(productId: String): Long = getGamePlaytimeStats(productId)?.lastPlayedMs ?: 0L

	/**
	 * Records a completed play session against [productId] — accumulates total playtime, bumps
	 * the longest single session if this one was longer, and overwrites the last-played timestamp.
	 */
	fun recordPlaySession(productId: String, sessionDurationMs: Long, sessionStartedWallClockMs: Long)
	{
		if (sessionDurationMs <= 0L) return

		val current = getGamePlaytimeStats().toMutableMap()
		val existing = current[productId]
		current[productId] = GamePlaytimeStats(
			totalPlaytimeMs = (existing?.totalPlaytimeMs ?: 0L) + sessionDurationMs,
			lastPlayedMs = sessionStartedWallClockMs,
			longestSessionMs = maxOf(existing?.longestSessionMs ?: 0L, sessionDurationMs)
		)

		val obj = org.json.JSONObject()
		current.forEach { (key, stats) ->
			val entryObj = org.json.JSONObject()
			entryObj.put("totalPlaytimeMs", stats.totalPlaytimeMs)
			entryObj.put("lastPlayedMs", stats.lastPlayedMs)
			entryObj.put("longestSessionMs", stats.longestSessionMs)
			obj.put(key, entryObj)
		}
		sharedPreferences.edit().putString(GAME_PLAYTIME_KEY, obj.toString()).apply()
	}

	// Filter states for favorites
	fun getPsnowFilterFavorites(): Boolean
	{
		return sharedPreferences.getBoolean(PSNOW_FILTER_FAVORITES_KEY, false)
	}
	
	fun setPsnowFilterFavorites(isFavorites: Boolean)
	{
		sharedPreferences.edit().putBoolean(PSNOW_FILTER_FAVORITES_KEY, isFavorites).apply()
	}
	
	fun getPsCloudFilterFavorites(): Boolean
	{
		return sharedPreferences.getBoolean(PSCLOUD_FILTER_FAVORITES_KEY, false)
	}
	
	fun setPsCloudFilterFavorites(isFavorites: Boolean)
	{
		sharedPreferences.edit().putBoolean(PSCLOUD_FILTER_FAVORITES_KEY, isFavorites).apply()
	}
	
	// License agreement
	fun hasAgreedToLicense(): Boolean
	{
		return sharedPreferences.getBoolean(LICENSE_AGREED_KEY, false)
	}
	
	fun setLicenseAgreed(agreed: Boolean)
	{
		sharedPreferences.edit().putBoolean(LICENSE_AGREED_KEY, agreed).apply()
	}

	val themeColourKey get() = resources.getString(R.string.preferences_theme_colour_key)

	fun getThemeColour(): String = sharedPreferences.getString(themeColourKey, "pink") ?: "pink"

	fun setThemeColour(value: String) {
		sharedPreferences.edit().putString(themeColourKey, value).apply()
	}

	fun isBlueTheme(): Boolean = getThemeColour() == "blue"

	fun getThemeStyleRes(): Int = when (getThemeColour()) {
		"blue"   -> R.style.AppTheme_Blue
		"green"  -> R.style.AppTheme_Green
		"yellow" -> R.style.AppTheme_Yellow
		"orange" -> R.style.AppTheme_Orange
		else     -> R.style.AppTheme
	}

	/** Like [getThemeStyleRes], but for StreamActivity specifically — StreamTheme.<colour>
	 *  instead of AppTheme.<colour>, since only StreamTheme carries the translucent status/nav
	 *  bar flags the stream screen's window needs to actually fill the screen. */
	fun getStreamThemeStyleRes(): Int = when (getThemeColour()) {
		"blue"   -> R.style.StreamTheme_Blue
		"green"  -> R.style.StreamTheme_Green
		"yellow" -> R.style.StreamTheme_Yellow
		"orange" -> R.style.StreamTheme_Orange
		else     -> R.style.StreamTheme
	}

	private val CONTROLLER_MAPPING_KEY = "controller_mapping_json"

	fun saveControllerMapping(mapping: Map<com.metallic.chiaki.session.ControllerAction, com.metallic.chiaki.session.PhysicalInput>)
	{
		sharedPreferences.edit()
			.putString(CONTROLLER_MAPPING_KEY, com.metallic.chiaki.session.PhysicalInput.mappingToJson(mapping))
			.apply()
	}

	fun loadControllerMapping(): Map<com.metallic.chiaki.session.ControllerAction, com.metallic.chiaki.session.PhysicalInput>
	{
		val json = sharedPreferences.getString(CONTROLLER_MAPPING_KEY, null) ?: return emptyMap()
		return com.metallic.chiaki.session.PhysicalInput.mappingFromJson(json)
	}

	fun clearControllerMapping()
	{
		sharedPreferences.edit().remove(CONTROLLER_MAPPING_KEY).apply()
	}

	/** Cumulative time spent in connected remote play sessions (client-side estimate). */
	val totalStreamTimeMs: Long
		get() = sharedPreferences.getLong(TOTAL_STREAM_TIME_MS_KEY, 0L)

	fun addTotalStreamTimeMs(deltaMs: Long)
	{
		if (deltaMs <= 0L) return
		val sum = totalStreamTimeMs + deltaMs
		sharedPreferences.edit().putLong(TOTAL_STREAM_TIME_MS_KEY, sum).apply()
	}

	/**
	 * Wall clock: last time the in-stream auto donation prompt ran (dialog or “already supporting” toast).
	 * Used to enforce at most one such prompt per hour; 0 means none yet.
	 */
	var lastDonationPromptWallClockMs: Long
		get()
		{
			var wall = sharedPreferences.getLong(LAST_DONATION_PROMPT_WALL_MS_KEY, 0L)
			if (wall == 0L && sharedPreferences.getBoolean(DONATION_STREAM_AUTO_PROMPT_SHOWN_KEY, false))
			{
				wall = System.currentTimeMillis()
				sharedPreferences.edit()
					.putLong(LAST_DONATION_PROMPT_WALL_MS_KEY, wall)
					.remove(DONATION_STREAM_AUTO_PROMPT_SHOWN_KEY)
					.apply()
				return wall
			}
			return wall
		}
		set(value)
		{
			sharedPreferences.edit()
				.putLong(LAST_DONATION_PROMPT_WALL_MS_KEY, value)
				.remove(DONATION_STREAM_AUTO_PROMPT_SHOWN_KEY)
				.apply()
		}
}