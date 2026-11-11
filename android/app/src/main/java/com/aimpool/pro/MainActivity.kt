package com.aimpool.pro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aimpool.pro.databinding.ActivityMainBinding

/**
 * Interface de contrôle principale de l'application Aim Pool Pro Overlay.
 * Permet de :
 * - Vérifier et accorder la permission de superposition d'écran
 * - Initialiser MediaProjection pour l'auto-détection optionnelle
 * - Démarrer et arrêter le service d'overlay
 * - Configurer la calibration des dimensions de la table
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isOverlayRunning = false
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            AimOverlayService.startAuto(this, result.resultCode, result.data!!)
            isOverlayRunning = true
            updateUiState()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkOverlayPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        checkOverlayPermission()
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            requestOverlayPermission()
        }

        binding.btnToggleOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                return@setOnClickListener
            }

            if (isOverlayRunning) stopOverlayService()
            else {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                captureLauncher.launch(manager.createScreenCaptureIntent())
            }
        }

        binding.btnManualOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) requestOverlayPermission()
            else if (isOverlayRunning) stopOverlayService()
            else startOverlayService()
        }

        binding.btnSaveSettings.setOnClickListener {
            val radius = binding.sliderBallRadius.value
            val currentCalib = TableCalibrationManager.loadCalibration(this)
            TableCalibrationManager.saveCalibration(
                this,
                currentCalib.copy(ballRadius = radius)
            )
            Toast.makeText(this, "Paramètres sauvegardés (Rayon: ${radius.toInt()}px)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkOverlayPermission(): Boolean {
        val hasPermission = Settings.canDrawOverlays(this)
        if (hasPermission) {
            binding.txtPermissionStatus.text = "Permission Accordée"
            binding.txtPermissionStatus.setTextColor(getColor(R.color.gold_accent))
            binding.btnGrantPermission.isEnabled = false
            binding.btnGrantPermission.alpha = 0.5f
        } else {
            binding.txtPermissionStatus.text = "Permission Requise (Superposition)"
            binding.txtPermissionStatus.setTextColor(getColor(R.color.error_red))
            binding.btnGrantPermission.isEnabled = true
            binding.btnGrantPermission.alpha = 1.0f
        }
        return hasPermission
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startOverlayService() {
        AimOverlayService.startService(this)
        isOverlayRunning = true
        updateUiState()
        Toast.makeText(this, "Assistant manuel démarré. Déplacez B, C et V sur la table.", Toast.LENGTH_LONG).show()
    }

    private fun stopOverlayService() {
        AimOverlayService.stopService(this)
        isOverlayRunning = false
        updateUiState()
        Toast.makeText(this, "Aim Pool Pro Overlay Arrêté", Toast.LENGTH_SHORT).show()
    }

    private fun updateUiState() {
        if (isOverlayRunning) {
            binding.btnToggleOverlay.text = "ARRÊTER L'ASSISTANT"
            binding.btnToggleOverlay.setBackgroundColor(getColor(R.color.dark_surface))
            binding.btnToggleOverlay.setTextColor(getColor(R.color.error_red))
            binding.txtServiceStatus.text = "STATUT : ASSISTANT ACTIF"
            binding.txtServiceStatus.setTextColor(getColor(R.color.emerald_green))
        } else {
            binding.btnToggleOverlay.text = "START — VISÉE AUTOMATIQUE"
            binding.btnToggleOverlay.setBackgroundColor(getColor(R.color.gold_accent))
            binding.btnToggleOverlay.setTextColor(getColor(R.color.dark_bg))
            binding.txtServiceStatus.text = "STATUT : PRÊT À DÉMARRER"
            binding.txtServiceStatus.setTextColor(getColor(R.color.text_muted))
        }
    }
}
