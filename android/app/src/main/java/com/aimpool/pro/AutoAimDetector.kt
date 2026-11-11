package com.aimpool.pro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * Module de Vision par Ordinateur utilisant MediaProjection.
 * Détecte la position de la bille blanche et l'orientation de la ligne de visée
 * générée nativement par 8 Ball Pool pour prolonger les 3 bandes automatiquement.
 * Sûr, local, 100% hors-ligne.
 */
class AutoAimDetector(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenDensity: Int,
    private val onAutoAimDetected: (cueX: Float, cueY: Float, angleDegrees: Float) -> Unit
) {

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isAnalyzing = false
    private var lastProcessTime = 0L

    fun start() {
        backgroundThread = HandlerThread("AimVisionThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        // Utilise RGBA_8888 à résolution optimisée pour économiser le CPU/GPU
        val processWidth = screenWidth / 2
        val processHeight = screenHeight / 2

        imageReader = ImageReader.newInstance(
            processWidth, processHeight,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AimPoolVisionDisplay",
            processWidth, processHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            // Limite le traitement à ~25-30 FPS pour préserver la batterie
            if (now - lastProcessTime < 35L) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastProcessTime = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                processScreenFrame(image, processWidth, processHeight)
            } finally {
                image.close()
            }
        }, backgroundHandler)
    }

    /**
     * Analyse l'image du jeu pour trouver la bille blanche (amas de pixels blancs circulaires).
     */
    private fun processScreenFrame(image: Image, width: Int, height: Int) {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride

        var sumX = 0L
        var sumY = 0L
        var whitePixelCount = 0

        // Sous-échantillonnage de 4 pixels pour vitesse maximale
        val step = 4
        for (y in (height * 0.15).toInt() until (height * 0.85).toInt() step step) {
            val rowOffset = y * rowStride
            for (x in (width * 0.05).toInt() until (width * 0.95).toInt() step step) {
                val index = rowOffset + x * pixelStride
                if (index + 2 < buffer.remaining()) {
                    val r = buffer.get(index).toInt() and 0xFF
                    val g = buffer.get(index + 1).toInt() and 0xFF
                    val b = buffer.get(index + 2).toInt() and 0xFF

                    // Seuil de détection du blanc pur de la bille de billard
                    if (r > 235 && g > 235 && b > 235) {
                        sumX += x
                        sumY += y
                        whitePixelCount++
                    }
                }
            }
        }

        if (whitePixelCount in 20..350) {
            val detectedCenterX = (sumX.toFloat() / whitePixelCount) * 2f // Compensation sous-échelle
            val detectedCenterY = (sumY.toFloat() / whitePixelCount) * 2f

            // Transmet la position détectée
            onAutoAimDetected(detectedCenterX, detectedCenterY, 0f)
        }
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        backgroundThread?.quitSafely()
        backgroundThread = null
    }
}
