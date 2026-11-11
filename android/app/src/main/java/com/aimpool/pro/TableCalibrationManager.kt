package com.aimpool.pro

import android.content.Context
import android.content.SharedPreferences

/**
 * Sauvegarde et restaure les paramètres de calibration de la table de billard
 * dans les SharedPreferences de l'application.
 */
object TableCalibrationManager {

    private const val PREFS_NAME = "AimPoolPro_Calibration"
    private const val KEY_MIN_X = "min_x"
    private const val KEY_MIN_Y = "min_y"
    private const val KEY_MAX_X = "max_x"
    private const val KEY_MAX_Y = "max_y"
    private const val KEY_BALL_RADIUS = "ball_radius"

    data class CalibrationData(
        val tableBounds: PhysicsEngine2D.TableBounds,
        val pockets: List<PhysicsEngine2D.Pocket>,
        val ballRadius: Float
    )

    fun saveCalibration(context: Context, data: CalibrationData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_MIN_X, data.tableBounds.minX)
            .putFloat(KEY_MIN_Y, data.tableBounds.minY)
            .putFloat(KEY_MAX_X, data.tableBounds.maxX)
            .putFloat(KEY_MAX_Y, data.tableBounds.maxY)
            .putFloat(KEY_BALL_RADIUS, data.ballRadius)
            .apply()
    }

    fun loadCalibration(context: Context): CalibrationData {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val minX = prefs.getFloat(KEY_MIN_X, 85f)
        val minY = prefs.getFloat(KEY_MIN_Y, 180f)
        val maxX = prefs.getFloat(KEY_MAX_X, 1020f)
        val maxY = prefs.getFloat(KEY_MAX_Y, 680f)
        val ballRadius = prefs.getFloat(KEY_BALL_RADIUS, 24f)

        val tableBounds = PhysicsEngine2D.TableBounds(minX, minY, maxX, maxY)
        val pocketRadius = ballRadius * 1.6f

        // Calcul automatique des 6 poches standard
        val midX = (minX + maxX) / 2f
        val pockets = listOf(
            PhysicsEngine2D.Pocket(1, "Haut Gauche", PhysicsEngine2D.Point(minX, minY), pocketRadius),
            PhysicsEngine2D.Pocket(2, "Milieu Haut", PhysicsEngine2D.Point(midX, minY - 4f), pocketRadius),
            PhysicsEngine2D.Pocket(3, "Haut Droite", PhysicsEngine2D.Point(maxX, minY), pocketRadius),
            PhysicsEngine2D.Pocket(4, "Bas Gauche", PhysicsEngine2D.Point(minX, maxY), pocketRadius),
            PhysicsEngine2D.Pocket(5, "Milieu Bas", PhysicsEngine2D.Point(midX, maxY + 4f), pocketRadius),
            PhysicsEngine2D.Pocket(6, "Bas Droite", PhysicsEngine2D.Point(maxX, maxY), pocketRadius)
        )

        return CalibrationData(tableBounds, pockets, ballRadius)
    }
}
