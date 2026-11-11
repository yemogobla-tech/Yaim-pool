package com.aimpool.pro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Vue interactive superposée capturant les gestes UNIQUEMENT sur ses propres poignées.
 * Ne bloque JAMAIS la molette native de 8 Ball Pool ni la jauge de force du jeu.
 */
@SuppressLint("ViewConstructor")
class FloatingControlsView(
    context: Context,
    private val onAimChanged: (cue: PhysicsEngine2D.Point, target: PhysicsEngine2D.Point?, angle: Float, spinV: Float, spinH: Float) -> Unit,
    private val onToggleStealth: () -> Unit,
    private val onCloseRequested: () -> Unit
) : View(context) {

    // Positions initiales des poignées
    private var cuePos = PhysicsEngine2D.Point(320f, 400f)
    private var targetPos = PhysicsEngine2D.Point(620f, 320f)
    private var aimAngle = 0f

    // Effet de balle (Spin)
    private var spinVertical = 0f
    private var spinHorizontal = 0f

    // Bouton Furtif / Panique (placé discrètement en haut de l'écran)
    private val stealthBtnRect = RectF(40f, 60f, 130f, 150f)
    private var isStealth = false

    // Bouton Fermer
    private val closeBtnRect = RectF(150f, 60f, 240f, 150f)

    // Rayons tactiles des poignées
    private val handleRadius = 45f
    private val buttStickDistance = 140f

    // États de glissement tactile
    private var activeDragMode: DragMode = DragMode.NONE

    private enum class DragMode {
        NONE, CUE_BALL, TARGET_BALL, BUTT_HANDLE, STEALTH_BUTTON, CLOSE_BUTTON
    }

    // Peintures pré-allouées
    private val cueRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val targetRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val buttHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }

    private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD0B0F17")
        style = Paint.Style.FILL
    }

    private val buttonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44D4AF37")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val iconTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        updateAngleFromBalls()
    }

    fun setStealthIconState(hidden: Boolean) {
        isStealth = hidden
        invalidate()
    }

    private fun updateAngleFromBalls() {
        val dx = targetPos.x - cuePos.x
        val dy = targetPos.y - cuePos.y
        aimAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (aimAngle < 0) aimAngle += 360f
        notifyChange()
    }

    private fun notifyChange() {
        onAimChanged(cuePos, targetPos, aimAngle, spinVertical, spinHorizontal)
        invalidate()
    }

    private fun getButtHandlePos(): PhysicsEngine2D.Point {
        val rad = Math.toRadians((aimAngle + 180.0) % 360.0)
        return PhysicsEngine2D.Point(
            cuePos.x + (cos(rad) * buttStickDistance).toFloat(),
            cuePos.y + (sin(rad) * buttStickDistance).toFloat()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Bouton Furtif / Panique
        canvas.drawRoundRect(stealthBtnRect, 18f, 18f, buttonBgPaint)
        canvas.drawRoundRect(stealthBtnRect, 18f, 18f, buttonBorderPaint)
        val stealthLabel = if (isStealth) "OFF" else "PRO"
        canvas.drawText(stealthLabel, stealthBtnRect.centerX(), stealthBtnRect.centerY() + 10f, iconTextPaint)

        // 2. Bouton Fermer
        canvas.drawRoundRect(closeBtnRect, 18f, 18f, buttonBgPaint)
        canvas.drawRoundRect(closeBtnRect, 18f, 18f, buttonBorderPaint)
        canvas.drawText("✕", closeBtnRect.centerX(), closeBtnRect.centerY() + 10f, iconTextPaint)

        // Si en mode furtif, ne dessine pas les poignées sur l'écran
        if (isStealth) return

        // 3. Poignée Bille Blanche (Anneau tactile)
        canvas.drawCircle(cuePos.x, cuePos.y, handleRadius, cueRingPaint)
        canvas.drawCircle(cuePos.x, cuePos.y, 4f, cueRingPaint)

        // 4. Poignée Bille Cible (Anneau vert)
        canvas.drawCircle(targetPos.x, targetPos.y, handleRadius, targetRingPaint)
        canvas.drawCircle(targetPos.x, targetPos.y, 4f, targetRingPaint)

        // 5. Manche arrière de la queue (Butt Handle pour orienter naturellement le tir)
        val buttPos = getButtHandlePos()
        canvas.drawLine(cuePos.x, cuePos.y, buttPos.x, buttPos.y, buttHandlePaint)
        canvas.drawCircle(buttPos.x, buttPos.y, 22f, buttHandlePaint)
        canvas.drawCircle(buttPos.x, buttPos.y, 5f, buttHandlePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchX = event.x
        val touchY = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Vérifie les clics sur les boutons fixes
                if (stealthBtnRect.contains(touchX, touchY)) {
                    activeDragMode = DragMode.STEALTH_BUTTON
                    return true
                }
                if (closeBtnRect.contains(touchX, touchY)) {
                    activeDragMode = DragMode.CLOSE_BUTTON
                    return true
                }

                if (isStealth) return false

                // Vérifie si le toucher touche l'une des poignées
                val distCue = hypot((touchX - cuePos.x).toDouble(), (touchY - cuePos.y).toDouble())
                if (distCue <= handleRadius * 1.5) {
                    activeDragMode = DragMode.CUE_BALL
                    return true
                }

                val distTarget = hypot((touchX - targetPos.x).toDouble(), (touchY - targetPos.y).toDouble())
                if (distTarget <= handleRadius * 1.5) {
                    activeDragMode = DragMode.TARGET_BALL
                    return true
                }

                val buttPos = getButtHandlePos()
                val distButt = hypot((touchX - buttPos.x).toDouble(), (touchY - buttPos.y).toDouble())
                if (distButt <= 50.0) {
                    activeDragMode = DragMode.BUTT_HANDLE
                    return true
                }

                // TOUCHER DANS LE VIDE : Ne capture pas l'événement !
                // Laisse passer le clic vers 8 Ball Pool pour viser ou tirer
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                when (activeDragMode) {
                    DragMode.CUE_BALL -> {
                        cuePos = PhysicsEngine2D.Point(touchX, touchY)
                        updateAngleFromBalls()
                    }
                    DragMode.TARGET_BALL -> {
                        targetPos = PhysicsEngine2D.Point(touchX, touchY)
                        updateAngleFromBalls()
                    }
                    DragMode.BUTT_HANDLE -> {
                        // Tourne le tir autour de la bille blanche
                        val dx = cuePos.x - touchX
                        val dy = cuePos.y - touchY
                        aimAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        if (aimAngle < 0) aimAngle += 360f
                        notifyChange()
                    }
                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                when (activeDragMode) {
                    DragMode.STEALTH_BUTTON -> onToggleStealth()
                    DragMode.CLOSE_BUTTON -> onCloseRequested()
                    else -> {}
                }
                activeDragMode = DragMode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
