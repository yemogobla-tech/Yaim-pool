package com.aimpool.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Service Android en premier plan gérant la superposition d'écran (Overlay).
 * Conçu pour être 100% sûr, non-intrusif, sans risque de ban :
 * - Calque 1 : Vue de dessin Canvas totalement transparente avec FLAG_NOT_TOUCHABLE.
 *   Laisse passer 100% des clics vers 8 Ball Pool.
 * - Calque 2 : Poignées de commande déplaçables et bouton furtif instantané.
 *   Ne masque jamais la molette native de 8 Ball Pool.
 */
class AimOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var trajectoryOverlayView: OverlayTrajectoryView? = null
    private val handleWindows = mutableListOf<Pair<DraggableHandleView, WindowManager.LayoutParams>>()
    private var cueHandle: DraggableHandleView? = null
    private var targetHandle: DraggableHandleView? = null
    private var angleHandle: DraggableHandleView? = null
    private var modeHandle: DraggableHandleView? = null
    private var isStealthHidden = false

    companion object {
        const val CHANNEL_ID = "AimPoolProOverlayChannel"
        const val NOTIFICATION_ID = 8881
        const val ACTION_STOP = "com.aimpool.pro.ACTION_STOP"
        const val ACTION_TOGGLE_STEALTH = "com.aimpool.pro.ACTION_TOGGLE_STEALTH"

        fun startService(context: Context) {
            val intent = Intent(context, AimOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AimOverlayService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        initOverlayWindows()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_STEALTH -> {
                toggleStealthMode()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Initialise les deux calques de fenêtres superposées.
     */
    private fun initOverlayWindows() {
        // --- CALQUE 1 : Trajectoires de visée (100% Pass-Through, Zéro interception tactile) ---
        val trajectoryParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        trajectoryOverlayView = OverlayTrajectoryView(this)
        windowManager.addView(trajectoryOverlayView, trajectoryParams)

        // Each control is a small window. A full-screen touchable window consumes
        // game gestures even when its View.onTouchEvent returns false.
        val metrics = resources.displayMetrics
        val diameter = (62f * metrics.density).toInt()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        cueHandle = addHandle("B", Color.WHITE, (width * .30f).toInt(), (height * .52f).toInt(), diameter) { updateAim() }
        targetHandle = addHandle("C", Color.GREEN, (width * .62f).toInt(), (height * .47f).toInt(), diameter) { updateAim() }
        angleHandle = addHandle("V", Color.YELLOW, (width * .20f).toInt(), (height * .56f).toInt(), diameter) { updateAim() }
        modeHandle = addHandle("AIM", Color.CYAN, (width * .08f).toInt(), (height * .15f).toInt(), diameter,
            onMoved = {}, onTapped = { toggleStealthMode() })
        updateAim()
    }

    private fun addHandle(
        label: String,
        color: Int,
        centerX: Int,
        centerY: Int,
        diameter: Int,
        onMoved: () -> Unit,
        onTapped: () -> Unit = {}
    ): DraggableHandleView {
        val params = WindowManager.LayoutParams(
            diameter,
            diameter,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = centerX - diameter / 2
            y = centerY - diameter / 2
        }
        val view = DraggableHandleView(this, windowManager, params, label, color, onMoved, onTapped)
        windowManager.addView(view, params)
        handleWindows.add(view to params)
        return view
    }

    private fun updateAim() {
        val cue = cueHandle ?: return
        val target = targetHandle ?: return
        val angle = angleHandle ?: return
        val angleDegrees = Math.toDegrees(
            kotlin.math.atan2((cue.centerY - angle.centerY).toDouble(), (cue.centerX - angle.centerX).toDouble())
        ).toFloat()
        trajectoryOverlayView?.updateAim(
            PhysicsEngine2D.Point(cue.centerX, cue.centerY),
            PhysicsEngine2D.Point(target.centerX, target.centerY),
            angleDegrees, 0f, 0f
        )
    }

    /**
     * Mode Furtif : masque ou réaffiche les tracés instantanément (Bouton Panique).
     */
    private fun toggleStealthMode() {
        isStealthHidden = !isStealthHidden
        trajectoryOverlayView?.visibility = if (isStealthHidden) android.view.View.GONE else android.view.View.VISIBLE
        for ((view, params) in handleWindows) {
            if (view == modeHandle) continue
            params.flags = if (isStealthHidden) {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            params.alpha = if (isStealthHidden) 0f else 1f
            windowManager.updateViewLayout(view, params)
        }

        // Met à jour la notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Assistant manuel Aim Pool",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Trajectoires manuelles affichées au-dessus du jeu"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stealthIntent = Intent(this, AimOverlayService::class.java).apply {
            action = ACTION_TOGGLE_STEALTH
        }
        val pendingStealth = PendingIntent.getService(
            this, 1, stealthIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AimOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = if (isStealthHidden) "Lignes masquées" else "Assistant manuel actif"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aim Pool — mode manuel")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_aim_crosshair)
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .addAction(
                R.drawable.ic_stealth_eye,
                if (isStealthHidden) "Afficher" else "Masquer",
                pendingStealth
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Arrêter",
                pendingStop
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        trajectoryOverlayView?.let {
            windowManager.removeView(it)
            trajectoryOverlayView = null
        }
        for ((view, _) in handleWindows) windowManager.removeView(view)
        handleWindows.clear()
        cueHandle = null
        targetHandle = null
        angleHandle = null
        modeHandle = null
    }
}
