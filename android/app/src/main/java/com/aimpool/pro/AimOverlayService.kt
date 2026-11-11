package com.aimpool.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Service Android en premier plan gérant la superposition d'écran (Overlay).
 * Affiche des trajectoires locales au-dessus du jeu :
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
    private var detector: AutoAimDetector? = null
    private var windowsInitialized = false

    companion object {
        const val CHANNEL_ID = "AimPoolProOverlayChannel"
        const val NOTIFICATION_ID = 8881
        const val ACTION_STOP = "com.aimpool.pro.ACTION_STOP"
        const val ACTION_TOGGLE_STEALTH = "com.aimpool.pro.ACTION_TOGGLE_STEALTH"
        const val ACTION_START_AUTO = "com.aimpool.pro.ACTION_START_AUTO"
        const val EXTRA_PROJECTION_DATA = "projectionData"
        const val EXTRA_RESULT_CODE = "resultCode"

        fun startService(context: Context) {
            val intent = Intent(context, AimOverlayService::class.java).apply { action = "START_MANUAL" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startAuto(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AimOverlayService::class.java).apply {
                action = ACTION_START_AUTO
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, data)
            }
            context.startForegroundService(intent)
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
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_STEALTH -> {
                toggleStealthMode()
            }
            ACTION_START_AUTO -> {
                val type = if (Build.VERSION.SDK_INT >= 29)
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, buildNotification(), type)
                else startForeground(NOTIFICATION_ID, buildNotification())
                if (!windowsInitialized) initOverlayWindows(manual = false)
                val data = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                else intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                if (data == null || resultCode != Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = manager.getMediaProjection(resultCode, data)
                val metrics = resources.displayMetrics
                detector?.stop()
                detector = AutoAimDetector(projection, metrics.widthPixels, metrics.heightPixels,
                    metrics.densityDpi) { x, y, angle ->
                    trajectoryOverlayView?.post {
                        trajectoryOverlayView?.updateAim(PhysicsEngine2D.Point(x, y), null, angle, 0f, 0f)
                    }
                }.also { it.start() }
            }
            "START_MANUAL" -> {
                val type = if (Build.VERSION.SDK_INT >= 34)
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, buildNotification(), type)
                else startForeground(NOTIFICATION_ID, buildNotification())
                if (!windowsInitialized) initOverlayWindows(manual = true)
            }
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Initialise les deux calques de fenêtres superposées.
     */
    private fun initOverlayWindows(manual: Boolean) {
        windowsInitialized = true
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
            // Android 12+ only passes game touches through a sufficiently
            // translucent untrusted overlay (maximum obscuring opacity 0.8).
            alpha = .75f
        }

        trajectoryOverlayView = OverlayTrajectoryView(this)
        windowManager.addView(trajectoryOverlayView, trajectoryParams)

        // Each control is a small window. A full-screen touchable window consumes
        // game gestures even when its View.onTouchEvent returns false.
        val metrics = resources.displayMetrics
        val diameter = (62f * metrics.density).toInt()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (manual) {
            cueHandle = addHandle("B", Color.WHITE, (width * .30f).toInt(), (height * .52f).toInt(), diameter) { updateAim() }
            targetHandle = addHandle("C", Color.GREEN, (width * .62f).toInt(), (height * .47f).toInt(), diameter) { updateAim() }
            angleHandle = addHandle("V", Color.YELLOW, (width * .20f).toInt(), (height * .56f).toInt(), diameter) { updateAim() }
        }
        modeHandle = addHandle("AIM", Color.CYAN, (width * .08f).toInt(), (height * .15f).toInt(), diameter,
            onMoved = {}, onTapped = { toggleStealthMode() })
        if (manual) updateAim()
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
        detector?.stop()
        detector = null
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
