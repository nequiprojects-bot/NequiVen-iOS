package com.ios.nequixofficialv2

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.view.animation.LinearInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import android.widget.ImageView
import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import com.ios.nequixofficialv2.databinding.Vmok2Binding
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast

class PinActivity : AppCompatActivity() {

    private lateinit var binding: Vmok2Binding
    private lateinit var userPhone: String
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var isVerifying: Boolean = false
    private var isNavigating: Boolean = false
    private var loadingShownAt: Long = 0L
    private enum class PinResult { UNKNOWN, CORRECT, INCORRECT, NOT_FOUND, ERROR }
    private var pinResult: PinResult = PinResult.UNKNOWN
    // Se elimina ExoPlayer: usaremos overlay con animación custom
    private var lastErrorMessage: String? = null
    private var loadingLoop: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = Vmok2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Aplicar SOLO barra de estado morada - NO tocar barra de navegación
        com.ios.nequixofficialv2.utils.AndroidCompatibilityHelper.applyNequiStatusBar(this)

        // ✅ Aplicar SOLO padding bottom para respetar barra de navegación inferior
        // NO tocar el padding top para mantener el diseño original
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, navBars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        userPhone = intent.getStringExtra("user_phone") ?: ""

        if (userPhone.isEmpty()) {
            finish()
            return
        }

        initViews()
        setupClickListeners()
        
        // Mostrar diálogo de huella automáticamente si está disponible
        checkFingerprint()
    }

    // Permisos de cámara ahora se solicitan en HomeActivity

    private fun initViews() {
        binding.tvTitle.text = "Ingrese su PIN de seguridad"
    }

    private fun showLoadingAnimation(show: Boolean) {
        if (show) {
            loadingShownAt = SystemClock.elapsedRealtime()
            binding.loadingOverlay.isVisible = true
            pinResult = PinResult.UNKNOWN
            // Iniciar animación elástica idéntica a la de Confirmar
            val s1 = binding.loadingOverlay.findViewById<ImageView>(R.id.square1)
            val s2 = binding.loadingOverlay.findViewById<ImageView>(R.id.square2)
            s1?.apply {
                rotation = 0f; translationX = 0f; translationY = 0f; scaleX = 1f; scaleY = 1f
            }
            s2?.apply {
                alpha = 0f; rotation = 0f; translationX = 0f; translationY = 0f; scaleX = 1f; scaleY = 1f
                bringToFront()
            }
            s1?.post {
                val overlap = dp(4f)
                val w = s1.width.takeIf { it > 0 } ?: 40
                val d = w / 2f - overlap
                startElasticOpenOverlay(s1, s2, d)
            }
        } else {
            binding.loadingOverlay.isVisible = false
            stopElasticOpenOverlay()
        }
    }

    private fun setupClickListeners() {
        // Ya no usamos error_notification layout - ahora usamos NequiAlert
        
        // Back arrow: volver al Login
        binding.ivBackArrow.setOnClickListener { finish() }

        val addDigit: (Char) -> Unit = { d ->
            val current = (binding.etPassword.text?.toString() ?: "")
            if (current.length < 4) {
                val next = current + d
                binding.etPassword.setText(next)
                updateBoxes(next)
            }
        }

        fun View.animateTap() { animate().scaleX(0.9f).scaleY(0.9f).setDuration(60).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(60).start() }.start() }

        binding.btnKey0.setOnClickListener { it.animateTap(); addDigit('0') }
        binding.btnKey1.setOnClickListener { it.animateTap(); addDigit('1') }
        binding.btnKey2.setOnClickListener { it.animateTap(); addDigit('2') }
        binding.btnKey3.setOnClickListener { it.animateTap(); addDigit('3') }
        binding.btnKey4.setOnClickListener { it.animateTap(); addDigit('4') }
        binding.btnKey5.setOnClickListener { it.animateTap(); addDigit('5') }
        binding.btnKey6.setOnClickListener { it.animateTap(); addDigit('6') }
        binding.btnKey7.setOnClickListener { it.animateTap(); addDigit('7') }
        binding.btnKey8.setOnClickListener { it.animateTap(); addDigit('8') }
        binding.btnKey9.setOnClickListener { it.animateTap(); addDigit('9') }

        binding.btnBackspace.setOnClickListener {
            it.animateTap()
            val current = (binding.etPassword.text?.toString() ?: "")
            if (current.isNotEmpty()) {
                val next = current.dropLast(1)
                binding.etPassword.setText(next)
                updateBoxes(next)
            }
        }
        
        // Botón de huella: mostrar diálogo de autenticación
        binding.btnKeyDelete.setOnClickListener {
            it.animateTap()
            val biometricHelper = BiometricHelper(this)
            val savedPhone = biometricHelper.getUserPhone()
            if (savedPhone == userPhone && biometricHelper.isFingerprintEnabled() && biometricHelper.isBiometricAvailable()) {
                showFingerprintAuthDialog()
            }
        }

        // Olvidé mi clave: bottom sheet
        binding.tvForgotPassword.setOnClickListener { showForgotPinSheet() }
    }

    private fun validatePin(pin: String): Boolean {
        if (pin.length != 4 || !pin.all { it.isDigit() }) {
            binding.etPassword.error = "El PIN debe ser de 4 dígitos numéricos"
            return false
        }
        return true
    }

    private fun verifyPin(pin: String) {
        if (isVerifying) return
        isVerifying = true
        lifecycleScope.launch {
            // Comprobar conectividad primero
            if (!isOnline()) {
                pinResult = PinResult.ERROR
                lastErrorMessage = "Sin conexión a Wi‑Fi"
                isVerifying = false
                return@launch
            }
            
            // VERIFICAR DISPOSITIVO ANTES DE VALIDAR PIN
            val deviceManager = com.ios.nequixofficialv2.security.DeviceManager(this@PinActivity)
            val deviceCheck = deviceManager.checkDeviceAccess(userPhone)
            
            when (deviceCheck) {
                is com.ios.nequixofficialv2.security.DeviceManager.DeviceCheckResult.Blocked -> {
                    // Dispositivo bloqueado - mostrar diálogo
                    pinResult = PinResult.ERROR
                    isVerifying = false
                    showDeviceBlockedDialog(deviceCheck.registeredDeviceModel)
                    return@launch
                }
                is com.ios.nequixofficialv2.security.DeviceManager.DeviceCheckResult.Error -> {
                    pinResult = PinResult.ERROR
                    lastErrorMessage = "Error de verificación: ${deviceCheck.message}"
                    isVerifying = false
                    return@launch
                }
                is com.ios.nequixofficialv2.security.DeviceManager.DeviceCheckResult.Authorized -> {
                    // Dispositivo autorizado, continuar con validación de PIN
                }
            }
            
            val doc = try { db.collection("users").document(userPhone).get().await() } catch (e: Exception) {
                // Si falla, verificar conectividad nuevamente para mensaje correcto
                pinResult = PinResult.ERROR
                lastErrorMessage = if (!isOnline()) "Sin conexión a Wi‑Fi" else "Ocurrió un error. Intenta de nuevo"
                null
            }
            // Oculta animación si hubo error o clave incorrecta; si es correcta, se navegará y se destruirá esta Activity
            isVerifying = false
            if (doc == null || !doc.exists()) {
                pinResult = PinResult.NOT_FOUND
                lastErrorMessage = "Usuario no encontrado"
                return@launch
            }
            val stored = doc.getString("clave") ?: ""
            if (stored != pin) {
                pinResult = PinResult.INCORRECT
                lastErrorMessage = "Clave incorrecta Contactame @sangre_binerojs"
                return@launch
            }

            // Acceso concedido: ir al Home y pasar el teléfono del usuario (una sola vez)
            if (!isNavigating) {
                isNavigating = true
                pinResult = PinResult.CORRECT
            }
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun setLoading(isLoading: Boolean) {
        binding.tvConfirmText.text = if (isLoading) "Verificando..." else "No dudamos que seas tú...\nPero es mejor confirmar ;)"
    }

    private fun showError(message: String) {
        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, message)
    }

    private fun navigateToHome() {
        binding.loadingOverlay.isVisible = false
        
        val home = Intent(this@PinActivity, HomeActivity::class.java)
        home.putExtra("user_phone", userPhone)
        startActivity(home)
        finish()
    }

    private fun updateBoxes(value: String) {
        val boxes = listOf(binding.passwordBox1, binding.passwordBox2, binding.passwordBox3, binding.passwordBox4)
        val pink = ContextCompat.getColor(this, R.color.nequi_pink)
        val white = ContextCompat.getColor(this, R.color.white)
        for (i in boxes.indices) {
            if (i < value.length) {
                boxes[i].setText("*")
                boxes[i].setTextColor(pink)
            } else {
                boxes[i].text = ""
                boxes[i].setTextColor(white)
            }
        }
        if (value.length == 4) {
            // Mostrar overlay con animación custom y verificar en paralelo
            showLoadingAnimation(true)
            verifyPin(value)
            // Decidir cuando termine verificación; duración mínima: 2s (incorrecto/error), 3s (correcto)
            lifecycleScope.launch {
                val start = loadingShownAt
                // Espera activa hasta que pinResult cambie desde UNKNOWN
                while (pinResult == PinResult.UNKNOWN) delay(50)
                val elapsed = SystemClock.elapsedRealtime() - start
                val target = 3000L
                val remaining = (target - elapsed).coerceAtLeast(0L)
                delay(remaining)
                when (pinResult) {
                    PinResult.CORRECT -> navigateToHome()
                    PinResult.INCORRECT -> { showLoadingAnimation(false); showError("Clave incorrecta"); binding.etPassword.setText(""); updateBoxes("") }
                    PinResult.NOT_FOUND, PinResult.ERROR -> { showLoadingAnimation(false); showError(lastErrorMessage ?: "Usuario no encontrado") }
                    else -> showLoadingAnimation(false)
                }
            }
        }
    }

    // --- Animación elástica (igual a la de Confirmar) para el overlay ---
    private fun startElasticOpenOverlay(s1: View?, s2: View?, distance: Float) {
        if (s1 == null || s2 == null) return
        stopElasticOpenOverlay()

        fun cycle(): AnimatorSet {
            val d = distance
            val rotate = ObjectAnimator.ofFloat(s1, View.ROTATION, 0f, 360f).apply {
                duration = 600
                interpolator = AccelerateDecelerateInterpolator()
            }
            val open = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(s2, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(s1, View.TRANSLATION_X, 0f, -d),
                    ObjectAnimator.ofFloat(s1, View.TRANSLATION_Y, 0f, d),
                    ObjectAnimator.ofFloat(s2, View.TRANSLATION_X, 0f, d),
                    ObjectAnimator.ofFloat(s2, View.TRANSLATION_Y, 0f, -d),
                    ObjectAnimator.ofFloat(s1, View.SCALE_X, 1f, 0.9f),
                    ObjectAnimator.ofFloat(s1, View.SCALE_Y, 1f, 0.9f),
                    ObjectAnimator.ofFloat(s2, View.SCALE_X, 1f, 0.9f),
                    ObjectAnimator.ofFloat(s2, View.SCALE_Y, 1f, 0.9f)
                )
                duration = 420
                interpolator = OvershootInterpolator(1.08f)
            }
            val hold = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(s1, View.ALPHA, 1f, 1f),
                    ObjectAnimator.ofFloat(s2, View.ALPHA, 1f, 1f)
                )
                duration = 120
            }
            val close = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(s2, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(s1, View.TRANSLATION_X, -d, 0f),
                    ObjectAnimator.ofFloat(s1, View.TRANSLATION_Y, d, 0f),
                    ObjectAnimator.ofFloat(s2, View.TRANSLATION_X, d, 0f),
                    ObjectAnimator.ofFloat(s2, View.TRANSLATION_Y, -d, 0f),
                    ObjectAnimator.ofFloat(s1, View.SCALE_X, 0.9f, 1f),
                    ObjectAnimator.ofFloat(s1, View.SCALE_Y, 0.9f, 1f),
                    ObjectAnimator.ofFloat(s2, View.SCALE_X, 0.9f, 1f),
                    ObjectAnimator.ofFloat(s2, View.SCALE_Y, 0.9f, 1f)
                )
                duration = 420
                interpolator = OvershootInterpolator(1.08f)
            }
            return AnimatorSet().apply { playSequentially(rotate, open, hold, close) }
        }

        val seq = cycle()
        seq.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (s1.windowToken != null && binding.loadingOverlay.isVisible) {
                    loadingLoop = cycle()
                    loadingLoop?.addListener(this)
                    loadingLoop?.start()
                }
            }
        })
        loadingLoop = seq
        seq.start()
    }

    private fun stopElasticOpenOverlay() {
        loadingLoop?.let { set ->
            set.childAnimations?.forEach { it.cancel() }
            set.cancel()
        }
        loadingLoop = null
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun showForgotPinSheet() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val v = layoutInflater.inflate(R.layout.sheet_forgot_pin, null)
        v.findViewById<View>(R.id.btnTelegram)?.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/sangre_binerojs"))) } catch (_: Exception) {}
        }
        v.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(v)
        dialog.show()
        // Animación de entrada
        v.alpha = 0f
        v.scaleX = 0.9f
        v.scaleY = 0.9f
        v.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
            .start()
    }

    // ===== Sistema de Bloqueo de Dispositivos =====
    private var deviceBlockedDialog: com.ios.nequixofficialv2.security.DeviceBlockedDialog? = null

    private fun showDeviceBlockedDialog(registeredDeviceModel: String) {
        // Ocultar animación de carga
        showLoadingAnimation(false)
        
        // Limpiar PIN
        binding.etPassword.setText("")
        updateBoxes("")
        
        if (deviceBlockedDialog?.isShowing() == true) return
        
        deviceBlockedDialog = com.ios.nequixofficialv2.security.DeviceBlockedDialog(
            context = this,
            registeredDeviceModel = registeredDeviceModel,
            onClose = {
                // Al presionar "Regresar al sistema", solo cerrar el diálogo
                // El usuario se queda en la pantalla de PIN
                deviceBlockedDialog?.dismiss()
                deviceBlockedDialog = null
            }
        )
        deviceBlockedDialog?.show()
    }
    
    // ===== Autenticación con Huella =====
    private fun checkFingerprint() {
        val biometricHelper = BiometricHelper(this)
        
        // Verificar si tiene huella registrada y que sea para este usuario
        val savedPhone = biometricHelper.getUserPhone()
        if (savedPhone == userPhone && biometricHelper.isFingerprintEnabled() && biometricHelper.isBiometricAvailable()) {
            // Esperar un momento para que la UI esté lista
            lifecycleScope.launch {
                delay(500)
                showFingerprintAuthDialog()
            }
        }
    }
    
    private fun showFingerprintAuthDialog() {
        val dialog = android.app.Dialog(this)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val view = layoutInflater.inflate(R.layout.dialog_fingerprint_auth, null)
        dialog.setContentView(view)
        
        // Asegurar que el diálogo respete el ancho del layout XML (300dp)
        dialog.window?.setLayout(
            (300 * resources.displayMetrics.density).toInt(), // 300dp convertido a pixels
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        
        val tvSensorText = view.findViewById<android.widget.TextView>(R.id.tvSensorText)
        val tvValidating = view.findViewById<android.widget.TextView>(R.id.tvValidating)
        val ivIcon = view.findViewById<android.widget.ImageView>(R.id.ivFingerprintIcon)
        val btnCancel = view.findViewById<android.widget.TextView>(R.id.btnCancel)
        
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
        
        // Iniciar autenticación biométrica
        val biometricHelper = BiometricHelper(this)
        biometricHelper.showFingerprintAuth(
            onSuccess = { authenticatedPhone ->
                // Verificar que el teléfono coincida
                if (authenticatedPhone == userPhone) {
                    // Mostrar indicador de validación exitosa
                    tvValidating.visibility = View.VISIBLE
                    tvValidating.text = "Reconocido de huella dactilar"
                    tvValidating.setTextColor(android.graphics.Color.parseColor("#757575"))
                    
                    // Ocultar texto del sensor
                    tvSensorText.visibility = View.GONE
                    
                    // Cambiar a icono de check con fondo turquesa
                    ivIcon.setImageResource(R.drawable.ic_fingerprint_success)
                    ivIcon.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#26A69A")
                    )
                    ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.WHITE
                    )
                    
                    // Cerrar diálogo y mostrar animación de verificación del PIN
                    lifecycleScope.launch {
                        delay(300)
                        dialog.dismiss()
                        
                        // Mostrar la misma animación de carga que cuando ingresan el PIN
                        showLoadingAnimation(true)
                        
                        // Simular verificación (igual que el PIN)
                        delay(3000) // Duración de la animación
                        showLoadingAnimation(false)
                        
                        // Navegar a Home
                        navigateToHome()
                    }
                } else {
                    tvSensorText.text = "Huella no corresponde a este usuario"
                    tvSensorText.setTextColor(android.graphics.Color.parseColor("#F44336"))
                    tvValidating.visibility = View.GONE
                }
            },
            onError = { error ->
                // Mostrar estado de error SIEMPRE (incluso si cancela o cierra)
                tvSensorText.visibility = View.GONE
                tvValidating.visibility = View.VISIBLE
                tvValidating.text = "Something went wrong.\nTry again!"
                tvValidating.setTextColor(android.graphics.Color.parseColor("#F44336"))
                
                // Cambiar a icono de error con fondo rojo
                ivIcon.setImageResource(R.drawable.ic_fingerprint_error)
                ivIcon.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#F44336")
                )
                ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.WHITE
                )
                
                // Cerrar el diálogo automáticamente después de 1 segundo
                lifecycleScope.launch {
                    delay(1000) // 1 segundo
                    dialog.dismiss()
                }
            }
        )
    }

}
