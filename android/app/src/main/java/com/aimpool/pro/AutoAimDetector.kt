package com.aimpool.pro

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Reads the game's white guideline and cue ball from a user-approved screen capture. */
class AutoAimDetector(
    private val projection: MediaProjection,
    initialWidth: Int,
    initialHeight: Int,
    private val densityDpi: Int,
    private val onAim: (Float, Float, Float) -> Unit,
    private val onAimLost: () -> Unit
) {
    private val thread = HandlerThread("AimPoolVision").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var width = initialWidth
    private var height = initialHeight
    private var lastFrame = 0L
    private var lastCueX = -1f
    private var lastCueY = -1f
    private var lineVisible = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = stop()
        override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
            if (newWidth > 0 && newHeight > 0) handler.post { resize(newWidth, newHeight) }
        }
    }

    fun start() {
        projection.registerCallback(projectionCallback, handler)
        val imageReader = makeReader(width, height)
        reader = imageReader
        display = projection.createVirtualDisplay(
            "AimPoolCapture", width / 2, height / 2, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, handler
        )
    }

    private fun makeReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(max(1, w / 2), max(1, h / 2), PixelFormat.RGBA_8888, 2).also { next ->
            next.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrame >= 120L) {
                        lastFrame = now
                        analyze(image)
                    }
                } finally { image.close() }
            }, handler)
        }

    private fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w
        height = h
        val next = makeReader(w, h)
        display?.setSurface(null)
        display?.resize(max(1, w / 2), max(1, h / 2), densityDpi)
        display?.setSurface(next.surface)
        reader?.close()
        reader = next
        lastCueX = -1f
        lastCueY = -1f
        lost()
    }

    private fun lost() {
        if (lineVisible) {
            lineVisible = false
            onAimLost()
        }
    }

    private fun analyze(image: Image) {
        val w = image.width
        val h = image.height
        if (w <= h) { lost(); return } // Menus are portrait; the table is landscape.
        val plane = image.planes.firstOrNull() ?: return
        val pixels = plane.buffer
        val stride = plane.rowStride
        val pixelStride = plane.pixelStride
        val left = (w * .19f).toInt()
        val right = (w * .81f).toInt()
        val top = (h * .20f).toInt()
        val bottom = (h * .82f).toInt()

        // The real table has blue felt across this region. Menus contain white
        // text and circular icons that otherwise look like balls/guidelines.
        var feltSamples = 0
        for (fy in floatArrayOf(.30f, .50f, .70f)) {
            for (fx in floatArrayOf(.25f, .50f, .75f)) {
                val i = (h * fy).toInt() * stride + (w * fx).toInt() * pixelStride
                if (i + 2 >= pixels.limit()) continue
                val r = pixels.get(i).toInt() and 255
                val g = pixels.get(i + 1).toInt() and 255
                val b = pixels.get(i + 2).toInt() and 255
                if (r < 150 && g > r + 20 && b > g + 5) feltSamples++
            }
        }
        if (feltSamples < 7) { lost(); return }

        fun pale(x: Int, y: Int): Boolean {
            if (x !in 0 until w || y !in 0 until h) return false
            val i = y * stride + x * pixelStride
            if (i + 2 >= pixels.limit()) return false
            val r = pixels.get(i).toInt() and 255
            val g = pixels.get(i + 1).toInt() and 255
            val b = pixels.get(i + 2).toInt() and 255
            return r > 175 && g > 175 && b > 175 &&
                max(r, max(g, b)) - min(r, min(g, b)) < 55
        }

        data class Candidate(val x: Int, val y: Int, val score: Int)
        val candidates = ArrayList<Candidate>(32)
        for (y in top + 12 until bottom - 12 step 2) {
            for (x in left + 12 until right - 12 step 2) {
                if (!pale(x, y)) continue
                var inside = 0
                for (dy in -3..3 step 2) for (dx in -3..3 step 2)
                    if (pale(x + dx, y + dy)) inside++
                if (inside < 9) continue
                var outside = 0
                for (a in 0 until 16) {
                    val rad = a * Math.PI / 8.0
                    val rx = x + (cos(rad) * 11).toInt()
                    val ry = y + (sin(rad) * 11).toInt()
                    if (!pale(rx, ry)) outside++
                }
                val score = inside * 2 + outside * 3
                if (score < 54) continue
                val near = candidates.indexOfFirst {
                    kotlin.math.abs(it.x - x) < 12 && kotlin.math.abs(it.y - y) < 12
                }
                if (near >= 0) {
                    if (score > candidates[near].score) candidates[near] = Candidate(x, y, score)
                } else if (candidates.size < 32) {
                    candidates.add(Candidate(x, y, score))
                } else {
                    val lowest = candidates.indices.minByOrNull { candidates[it].score } ?: continue
                    if (score > candidates[lowest].score) candidates[lowest] = Candidate(x, y, score)
                }
            }
        }
        if (candidates.isEmpty()) { lost(); return }

        var bestX = -1
        var bestY = -1
        var bestAngle = -1
        var bestLine = 0
        for (candidate in candidates) {
            var cueX = candidate.x
            var cueY = candidate.y
            var weightedX = 0
            var weightedY = 0
            var count = 0
            for (dy in -8..8) for (dx in -8..8) {
                if (dx * dx + dy * dy <= 64 && pale(cueX + dx, cueY + dy)) {
                    weightedX += cueX + dx
                    weightedY += cueY + dy
                    count++
                }
            }
            if (count > 0) {
                cueX = weightedX / count
                cueY = weightedY / count
            }

            for (degrees in 0 until 360 step 4) {
                val rad = Math.toRadians(degrees.toDouble())
                val dx = cos(rad)
                val dy = sin(rad)
                for (offset in -6..6 step 2) {
                    var score = 0
                    for (distance in 15..110 step 4) {
                        val sx = cueX + (dx * distance - dy * offset).toInt()
                        val sy = cueY + (dy * distance + dx * offset).toInt()
                        if (sx !in left until right || sy !in top until bottom) break
                        if (pale(sx, sy)) score++
                    }
                    val sticky = if (lastCueX >= 0 &&
                        kotlin.math.abs(cueX * 2f - lastCueX) < 50f &&
                        kotlin.math.abs(cueY * 2f - lastCueY) < 50f) 2 else 0
                    if (score + sticky > bestLine) {
                        bestLine = score + sticky
                        bestAngle = degrees
                        bestX = cueX
                        bestY = cueY
                    }
                }
            }
        }
        if (bestLine < 11 || bestX < 0) { lost(); return }
        lastCueX = bestX * 2f
        lastCueY = bestY * 2f
        lineVisible = true
        onAim(lastCueX, lastCueY, bestAngle.toFloat())
    }

    fun stop() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection.unregisterCallback(projectionCallback)
        thread.quitSafely()
    }
}
