package com.aimpool.pro

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * Vue transparente plein écran dessinant les lignes de visée.
 * Optimisée pour le rendu matériel avec zéro allocation mémoire dans onDraw()
 * afin d'éviter les pauses du garbage collector pendant la partie.
 */
class OverlayTrajectoryView(context: Context) : View(context) {

    private var currentSolution: PhysicsEngine2D.AimSolution? = null
    private var ballRadius = 24f

    // Limites de table calibrées
    private var tableBounds = PhysicsEngine2D.TableBounds(80f, 160f, 1000f, 600f)
    private var pockets = listOf<PhysicsEngine2D.Pocket>()
    private var lastAim: AimInput? = null

    private data class AimInput(
        val cue: PhysicsEngine2D.Point,
        val target: PhysicsEngine2D.Point?,
        val angle: Float,
        val spinV: Float,
        val spinH: Float
    )

    // Peintures pré-allouées (zéro allocation dans onDraw)
    private val cueLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#63FF73")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    private val cushionBouncePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B") // Or ambré éclatant
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }

    private val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981") // Vert émeraude
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val cueDeflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8") // Bleu ciel
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    private val ghostBallStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val ghostBallFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.FILL
    }

    private val pocketHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4410B981")
        style = Paint.Style.FILL
    }

    private val textBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val badgeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0B0F17")
        style = Paint.Style.FILL
    }

    init {
        // Charge la calibration enregistrée
        val calib = TableCalibrationManager.loadCalibration(context)
        tableBounds = calib.tableBounds
        pockets = calib.pockets
        ballRadius = calib.ballRadius
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // The game rotates to landscape; fixed portrait pixel bounds are invalid.
        val left = w * .195f
        val right = w * .805f
        val top = h * .20f
        val bottom = h * .90f
        tableBounds = PhysicsEngine2D.TableBounds(left, top, right, bottom)
        val midX = (left + right) / 2f
        val radius = ballRadius * 1.6f
        pockets = listOf(
            PhysicsEngine2D.Pocket(1, "HG", PhysicsEngine2D.Point(left, top), radius),
            PhysicsEngine2D.Pocket(2, "HM", PhysicsEngine2D.Point(midX, top), radius),
            PhysicsEngine2D.Pocket(3, "HD", PhysicsEngine2D.Point(right, top), radius),
            PhysicsEngine2D.Pocket(4, "BG", PhysicsEngine2D.Point(left, bottom), radius),
            PhysicsEngine2D.Pocket(5, "BM", PhysicsEngine2D.Point(midX, bottom), radius),
            PhysicsEngine2D.Pocket(6, "BD", PhysicsEngine2D.Point(right, bottom), radius)
        )
        lastAim?.let { calculate(it) }
    }

    fun updateAim(
        cuePos: PhysicsEngine2D.Point,
        targetPos: PhysicsEngine2D.Point?,
        angleDegrees: Float,
        spinV: Float,
        spinH: Float
    ) {
        val aim = AimInput(cuePos, targetPos, angleDegrees, spinV, spinH)
        lastAim = aim
        calculate(aim)
    }

    fun clearAim() {
        lastAim = null
        currentSolution = null
        postInvalidateOnAnimation()
    }

    private fun calculate(aim: AimInput) {
        currentSolution = PhysicsEngine2D.calculateAim(
            cueCenter = aim.cue,
            angleDegrees = aim.angle,
            objectBallCenter = aim.target,
            ballRadius = ballRadius,
            table = tableBounds,
            pockets = pockets,
            maxBounces = 3,
            spinVertical = aim.spinV,
            spinHorizontal = aim.spinH
        )
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val solution = currentSolution ?: return

        // 1. Surbrillance de la poche visée (Pocket Lock Indicator)
        solution.targetPocket?.let { pocket ->
            canvas.drawCircle(pocket.center.x, pocket.center.y, pocket.radius * 1.5f, pocketHaloPaint)
            
            // Badge de tolérance angulaire
            solution.pocketAngleTolerance?.let { tol ->
                val badgeText = String.format("±%.1f°", tol)
                val textWidth = textBadgePaint.measureText(badgeText)
                val bgRect = RectF(
                    pocket.center.x - textWidth / 2f - 14f,
                    pocket.center.y - pocket.radius - 42f,
                    pocket.center.x + textWidth / 2f + 14f,
                    pocket.center.y - pocket.radius - 8f
                )
                canvas.drawRoundRect(bgRect, 8f, 8f, badgeBackgroundPaint)
                canvas.drawText(badgeText, bgRect.left + 14f, bgRect.bottom - 10f, textBadgePaint)
            }
        }

        // 2. Tracé du tir de la bille blanche et des rebonds
        for (segment in solution.cuePath) {
            val paint = if (segment.bounceIndex == 0) cueLinePaint else cushionBouncePaint
            canvas.drawLine(segment.start.x, segment.start.y, segment.end.x, segment.end.y, paint)

            // Point d'impact sur la bande
            if (segment.hitCushion != null) {
                canvas.drawCircle(segment.end.x, segment.end.y, 5f, cueLinePaint)
            }
        }

        // 3. Tracé de la bille fantôme (Ghost Ball)
        solution.ghostBallCenter?.let { ghost ->
            canvas.drawCircle(ghost.x, ghost.y, ballRadius, ghostBallFillPaint)
            canvas.drawCircle(ghost.x, ghost.y, ballRadius, ghostBallStrokePaint)
            canvas.drawCircle(ghost.x, ghost.y, 4f, cueLinePaint)
        }

        // 4. Tracé de la trajectoire de la bille cible
        for (segment in solution.targetPath) {
            canvas.drawLine(segment.start.x, segment.start.y, segment.end.x, segment.end.y, targetLinePaint)
            if (segment.bounceIndex > 0) {
                canvas.drawCircle(segment.end.x, segment.end.y, 4f, targetLinePaint)
            }
        }

        // 5. Tracé de la déviation de la blanche (tangente à 90° avec spin)
        for (segment in solution.cueDeflectionPath) {
            canvas.drawLine(segment.start.x, segment.start.y, segment.end.x, segment.end.y, cueDeflectionPaint)
        }
    }
}
