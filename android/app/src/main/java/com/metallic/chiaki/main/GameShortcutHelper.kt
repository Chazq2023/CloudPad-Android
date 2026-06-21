package com.metallic.chiaki.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.pylux.stream.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GameShortcutHelper {
    const val ACTION_LAUNCH_CLOUD_GAME = "com.pylux.stream.action.LAUNCH_CLOUD_GAME"

    const val EXTRA_PRODUCT_ID = "shortcut_product_id"
    const val EXTRA_GAME_NAME = "shortcut_game_name"
    const val EXTRA_PLATFORM = "shortcut_platform"
    const val EXTRA_SERVICE_TYPE = "shortcut_service_type"
    const val EXTRA_CONCEPT_URL = "shortcut_concept_url"

    suspend fun requestPinnedShortcut(
        context: Context,
        game: CloudGame
    ) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(
                context,
                "Your launcher does not support pinned shortcuts",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val shortcutIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_LAUNCH_CLOUD_GAME
            putExtra(EXTRA_PRODUCT_ID, game.productId)
            putExtra(EXTRA_GAME_NAME, game.name)
            putExtra(EXTRA_PLATFORM, game.platform)
            putExtra(EXTRA_SERVICE_TYPE, game.serviceType)
            putExtra(EXTRA_CONCEPT_URL, game.conceptUrl)

            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val icon = loadGameIcon(context, game.imageUrl)
            ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)

        val shortcut = ShortcutInfoCompat.Builder(
            context,
            "cloud_game_${game.productId.replace(Regex("[^A-Za-z0-9_]"), "_")}"
        )
            .setShortLabel(game.name.take(20))
            .setLongLabel(game.name.take(40))
            .setIcon(icon)
            .setIntent(shortcutIntent)
            .build()

        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)

        Toast.makeText(context, "Adding ${game.name} shortcut", Toast.LENGTH_SHORT).show()
    }

    private suspend fun loadGameIcon(
        context: Context,
        imageUrl: String
    ): IconCompat? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null

        try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .build()

            val result = loader.execute(request)
            val drawable = (result as? SuccessResult)?.drawable ?: return@withContext null
            val bitmap = drawable.toBitmap(192, 192)

            IconCompat.createWithBitmap(bitmap)
        } catch (_: Exception) {
            null
        }
    }

    private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null) {
            return Bitmap.createScaledBitmap(bitmap, width, height, true)
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }
}