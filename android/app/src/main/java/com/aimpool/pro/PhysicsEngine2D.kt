package com.aimpool.pro

import kotlin.math.*

/**
 * Moteur mathématique et géométrique 2D ultra-rapide (60/120 FPS).
 * Calcule avec exactitude :
 * 1. Raycasting avec marges de coussin (minX + R, maxX - R, minY + R, maxY - R)
 * 2. Réflexion spéculaire des bandes jusqu'à 3 rebonds
 * 3. Collision cercle-cercle (bille fantôme à 2*R)
 * 4. Vecteur normal d'impact (trajectoire bille cible)
 * 5. Vecteur tangent à 90° (déviation de la blanche) avec modulation d'effet (Spin)
 * 6. Calcul de verrouillage de poche (Pocket Snap) et marge angulaire de tolérance.
 */
object PhysicsEngine2D {

    data class Point(val x: Float, val y: Float) {
        fun distanceTo(other: Point): Float {
            val dx = x - other.x
            val dy = y - other.y
            return sqrt(dx * dx + dy * dy)
        }

        fun plus(v: Vector2D): Point = Point(x + v.dx, y + v.dy)
    }

    data class Vector2D(val dx: Float, val dy: Float) {
        val length: Float get() = sqrt(dx * dx + dy * dy)

        fun normalized(): Vector2D {
            val len = length
            return if (len > 0.00001f) Vector2D(dx / len, dy / len) else Vector2D(0f, 0f)
        }

        fun dot(other: Vector2D): Float = dx * other.dx + dy * other.dy

        fun cross(other: Vector2D): Float = dx * other.dy - dy * other.dx

        fun scaled(factor: Float): Vector2D = Vector2D(dx * factor, dy * factor)

        fun angleDegrees(): Float {
            val rad = atan2(dy, dx)
            var deg = Math.toDegrees(rad.toDouble()).toFloat()
            if (deg < 0) deg += 360f
            return deg
        }

        companion object {
            fun fromAngle(angleDegrees: Float): Vector2D {
                val rad = Math.toRadians(angleDegrees.toDouble())
                return Vector2D(cos(rad).toFloat(), sin(rad).toFloat())
            }
        }
    }

    data class TableBounds(
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float
    ) {
        val width: Float get() = maxX - minX
        val height: Float get() = maxY - minY
    }

    data class Pocket(
        val id: Int,
        val name: String,
        val center: Point,
        val radius: Float
    )

    data class RaySegment(
        val start: Point,
        val end: Point,
        val bounceIndex: Int,
        val hitCushion: CushionType? = null
    )

    enum class CushionType {
        LEFT, RIGHT, TOP, BOTTOM
    }

    data class AimSolution(
        val cuePath: List<RaySegment>,
        val ghostBallCenter: Point?,
        val hasCollision: Boolean,
        val targetPath: List<RaySegment>,
        val cueDeflectionPath: List<RaySegment>,
        val targetPocket: Pocket?,
        val pocketAngleTolerance: Float? // Tolérance en degrés (+/-)
    )

    /**
     * Calcule la trajectoire complète pour un tir donné.
     * @param cueCenter Position de la bille blanche
     * @param angleDegrees Angle de départ du tir en degrés
     * @param objectBallCenter Position de la bille cible (optionnelle)
     * @param ballRadius Rayon des billes (ex: 22f pixels)
     * @param table Limites intérieures des bandes de la table
     * @param pockets Liste des 6 poches de la table
     * @param maxBounces Nombre maximum de rebonds de bande (1, 2 ou 3)
     * @param spinVertical Effet avant/arrière (-1f rétro, 0f naturel, +1f coulé)
     * @param spinHorizontal Effet latéral (-1f gauche, +1f droite)
     */
    fun calculateAim(
        cueCenter: Point,
        angleDegrees: Float,
        objectBallCenter: Point?,
        ballRadius: Float,
        table: TableBounds,
        pockets: List<Pocket>,
        maxBounces: Int = 3,
        spinVertical: Float = 0f,
        spinHorizontal: Float = 0f
    ): AimSolution {
        val cueDirection = Vector2D.fromAngle(angleDegrees).normalized()
        val cueSegments = mutableListOf<RaySegment>()

        var currentStart = cueCenter
        var currentDir = cueDirection
        var ghostCenter: Point? = null
        var collisionFound = false
        var hitSegmentIndex = -1

        // 1. Détection de collision directe avant ou pendant les rebonds
        for (bounce in 0..maxBounces) {
            // Collision avec la bille cible le long du rayon courant
            val hit = if (!collisionFound && objectBallCenter != null) {
                checkRaySphereIntersection(currentStart, currentDir, objectBallCenter, ballRadius * 2f)
            } else null

            // Limite effective des coussins compte tenu du rayon de la bille
            val effectiveBounds = TableBounds(
                minX = table.minX + ballRadius,
                minY = table.minY + ballRadius,
                maxX = table.maxX - ballRadius,
                maxY = table.maxY - ballRadius
            )

            val cushionHit = castRayToCushion(currentStart, currentDir, effectiveBounds)

            if (hit != null && (cushionHit == null || hit.distance <= cushionHit.distance)) {
                // La bille blanche heurte la bille cible avant la bande
                ghostCenter = hit.point
                collisionFound = true
                hitSegmentIndex = bounce
                cueSegments.add(RaySegment(currentStart, hit.point, bounce, null))
                break
            } else if (cushionHit != null) {
                // Rebond sur une bande
                cueSegments.add(RaySegment(currentStart, cushionHit.point, bounce, cushionHit.cushion))
                currentStart = cushionHit.point
                // Réflexion spéculaire avec ajustement léger de spin latéral
                currentDir = reflectDirection(currentDir, cushionHit.cushion, spinHorizontal)
            } else {
                break
            }
        }

        // Si aucune collision n'a eu lieu, on a juste la ligne de visée et ses rebonds
        if (!collisionFound || ghostCenter == null || objectBallCenter == null) {
            return AimSolution(
                cuePath = cueSegments,
                ghostBallCenter = null,
                hasCollision = false,
                targetPath = emptyList(),
                cueDeflectionPath = emptyList(),
                targetPocket = null,
                pocketAngleTolerance = null
            )
        }

        // 2. Calcul du vecteur normal d'impact vers la bille cible
        val impactNormal = Vector2D(
            objectBallCenter.x - ghostCenter.x,
            objectBallCenter.y - ghostCenter.y
        ).normalized()

        // 3. Trajectoire de la bille cible (direction de l'impact + rebonds éventuels)
        val targetSegments = mutableListOf<RaySegment>()
        var targetStart: Point = objectBallCenter
        var targetDir = impactNormal

        for (bounce in 0..1) { // 1 rebond max pour la bille cible généralement utile
            val effectiveBounds = TableBounds(
                minX = table.minX + ballRadius,
                minY = table.minY + ballRadius,
                maxX = table.maxX - ballRadius,
                maxY = table.maxY - ballRadius
            )
            val cushionHit = castRayToCushion(targetStart, targetDir, effectiveBounds)
            if (cushionHit != null) {
                targetSegments.add(RaySegment(targetStart, cushionHit.point, bounce, cushionHit.cushion))
                targetStart = cushionHit.point
                targetDir = reflectDirection(targetDir, cushionHit.cushion, 0f)
            } else {
                val extendedEnd = targetStart.plus(targetDir.scaled(400f))
                targetSegments.add(RaySegment(targetStart, extendedEnd, bounce, null))
                break
            }
        }

        // 4. Calcul de la déviation à 90° de la bille blanche
        val hitSegment = cueSegments.getOrNull(hitSegmentIndex)
        val cueIncomingDir = if (hitSegment != null) {
            Vector2D(hitSegment.end.x - hitSegment.start.x, hitSegment.end.y - hitSegment.start.y).normalized()
        } else cueDirection

        val normalComponent = cueIncomingDir.dot(impactNormal)
        // Vecteur tangent pur (angle droit)
        val tangentVector = Vector2D(
            cueIncomingDir.dx - impactNormal.dx * normalComponent,
            cueIncomingDir.dy - impactNormal.dy * normalComponent
        ).normalized()

        // Modulation de l'effet rétro / coulé (spin vertical)
        val spinModifier = impactNormal.scaled(spinVertical * 0.45f)
        val finalCueDeflectionDir = Vector2D(
            tangentVector.dx + spinModifier.dx,
            tangentVector.dy + spinModifier.dy
        ).normalized()

        val cueDeflectionSegments = mutableListOf<RaySegment>()
        val deflectionLength = 180f + abs(spinVertical) * 80f
        cueDeflectionSegments.add(
            RaySegment(
                start = ghostCenter,
                end = ghostCenter.plus(finalCueDeflectionDir.scaled(deflectionLength)),
                bounceIndex = 0
            )
        )

        // 5. Détection de verrouillage de poche (Pocket Snap & Tolerance)
        val (lockedPocket, tolerance) = evaluatePocketAlignment(objectBallCenter, impactNormal, pockets)

        return AimSolution(
            cuePath = cueSegments,
            ghostBallCenter = ghostCenter,
            hasCollision = true,
            targetPath = targetSegments,
            cueDeflectionPath = cueDeflectionSegments,
            targetPocket = lockedPocket,
            pocketAngleTolerance = tolerance
        )
    }

    private data class RayHit(val distance: Float, val point: Point)

    /**
     * Résout géométriquement l'intersection entre le rayon et un cercle de rayon 2*R.
     */
    private fun checkRaySphereIntersection(
        rayOrigin: Point,
        rayDir: Vector2D,
        sphereCenter: Point,
        sphereRadius: Float
    ): RayHit? {
        val ocX = rayOrigin.x - sphereCenter.x
        val ocY = rayOrigin.y - sphereCenter.y

        val b = 2f * (ocX * rayDir.dx + ocY * rayDir.dy)
        val c = (ocX * ocX + ocY * ocY) - (sphereRadius * sphereRadius)

        val discriminant = b * b - 4f * c
        if (discriminant < 0) return null

        val sqrtDisc = sqrt(discriminant)
        val t1 = (-b - sqrtDisc) / 2f
        val t2 = (-b + sqrtDisc) / 2f

        val t = when {
            t1 > 0.05f -> t1
            t2 > 0.05f -> t2
            else -> return null
        }

        return RayHit(
            distance = t,
            point = Point(rayOrigin.x + rayDir.dx * t, rayOrigin.y + rayDir.dy * t)
        )
    }

    private data class CushionIntersection(
        val distance: Float,
        val point: Point,
        val cushion: CushionType
    )

    /**
     * Trouve la bande la plus proche intersectée par le rayon.
     */
    private fun castRayToCushion(
        origin: Point,
        dir: Vector2D,
        bounds: TableBounds
    ): CushionIntersection? {
        var closestDist = Float.MAX_VALUE
        var hitPoint: Point? = null
        var hitCushion: CushionType? = null

        // Bande Gauche (x = minX)
        if (dir.dx < -0.0001f) {
            val t = (bounds.minX - origin.x) / dir.dx
            if (t > 0.1f) {
                val y = origin.y + dir.dy * t
                if (y in bounds.minY..bounds.maxY && t < closestDist) {
                    closestDist = t
                    hitPoint = Point(bounds.minX, y)
                    hitCushion = CushionType.LEFT
                }
            }
        }

        // Bande Droite (x = maxX)
        if (dir.dx > 0.0001f) {
            val t = (bounds.maxX - origin.x) / dir.dx
            if (t > 0.1f) {
                val y = origin.y + dir.dy * t
                if (y in bounds.minY..bounds.maxY && t < closestDist) {
                    closestDist = t
                    hitPoint = Point(bounds.maxX, y)
                    hitCushion = CushionType.RIGHT
                }
            }
        }

        // Bande Supérieure (y = minY)
        if (dir.dy < -0.0001f) {
            val t = (bounds.minY - origin.y) / dir.dy
            if (t > 0.1f) {
                val x = origin.x + dir.dx * t
                if (x in bounds.minX..bounds.maxX && t < closestDist) {
                    closestDist = t
                    hitPoint = Point(x, bounds.minY)
                    hitCushion = CushionType.TOP
                }
            }
        }

        // Bande Inférieure (y = maxY)
        if (dir.dy > 0.0001f) {
            val t = (bounds.maxY - origin.y) / dir.dy
            if (t > 0.1f) {
                val x = origin.x + dir.dx * t
                if (x in bounds.minX..bounds.maxX && t < closestDist) {
                    closestDist = t
                    hitPoint = Point(x, bounds.maxY)
                    hitCushion = CushionType.BOTTOM
                }
            }
        }

        return if (hitPoint != null && hitCushion != null) {
            CushionIntersection(closestDist, hitPoint, hitCushion)
        } else null
    }

    /**
     * Réflexion spéculaire sur une bande.
     */
    private fun reflectDirection(
        dir: Vector2D,
        cushion: CushionType,
        spinHorizontal: Float
    ): Vector2D {
        val spinEffect = spinHorizontal * 0.12f
        return when (cushion) {
            CushionType.LEFT, CushionType.RIGHT -> {
                // Inversion de dx
                Vector2D(-dir.dx, dir.dy + spinEffect).normalized()
            }
            CushionType.TOP, CushionType.BOTTOM -> {
                // Inversion de dy
                Vector2D(dir.dx + spinEffect, -dir.dy).normalized()
            }
        }
    }

    /**
     * Évalue si la bille cible se dirige vers l'une des 6 poches et calcule la marge angulaire.
     */
    private fun evaluatePocketAlignment(
        objectBall: Point,
        impactDir: Vector2D,
        pockets: List<Pocket>
    ): Pair<Pocket?, Float?> {
        for (pocket in pockets) {
            val toPocket = Vector2D(pocket.center.x - objectBall.x, pocket.center.y - objectBall.y)
            val dist = toPocket.length
            if (dist < 10f) continue

            val toPocketNorm = toPocket.normalized()
            val dot = impactDir.dot(toPocketNorm)

            // Si la direction pointe vers la poche (angle < ~12°)
            if (dot > 0.978f) { // cos(12°) ≈ 0.978
                val angleErr = Math.toDegrees(acos(dot.toDouble().coerceIn(-1.0, 1.0))).toFloat()
                // Tolérance de poche basée sur son rayon d'ouverture et sa distance
                val maxAllowedAngle = Math.toDegrees(atan2(pocket.radius.toDouble(), dist.toDouble())).toFloat()
                val tolerance = max(0.1f, maxAllowedAngle - angleErr)
                return Pair(pocket, tolerance)
            }
        }
        return Pair(null, null)
    }
}
