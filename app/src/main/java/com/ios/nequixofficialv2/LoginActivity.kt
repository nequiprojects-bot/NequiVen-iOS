package com.ios.nequixofficialv2
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import com.ios.nequixofficialv2.databinding.Vmok1Binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import android.os.CountDownTimer
import android.text.InputFilter
import android.text.Editable
import android.text.TextWatcher
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.random.Random

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: Vmok1Binding
    private val db: com.google.firebase.firestore.FirebaseFirestore by lazy { com.google.firebase.firestore.FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private var errorHideJob: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            window.statusBarColor = ContextCompat.getColor(this, R.color.color_200020)
        } catch (_: Exception) {}
        
        binding = Vmok1Binding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.etUsuario.filters = arrayOf(InputFilter.LengthFilter(12))
        setupPhoneFormattingWatcher()
        val saved = loadSavedPhone()
        if (saved.isNotBlank()) {
            val formatted = formatPhoneForDisplay(saved)
            binding.etUsuario.setText(formatted)
            val end = binding.etUsuario.text?.length ?: formatted.length
            binding.etUsuario.setSelection(end)
        }
        setupClickListeners()
        startDynamicKeyTimer()
        checkForUpdates()
        maybeShowLoginOverlay()
    }

    private fun loadSavedPhone(): String {
        val prefs = getSharedPreferences("login_prefs", MODE_PRIVATE)
        return prefs.getString("saved_phone", "").orEmpty()
    }

    private fun savePhone(phone: String) {
        val prefs = getSharedPreferences("login_prefs", MODE_PRIVATE)
        prefs.edit().putString("saved_phone", phone).apply()
    }

    // ===== Formato teléfono '300 000 0000' =====
    private fun digitsOnly(s: String): String = s.filter { it.isDigit() }
    private fun formatPhoneForDisplay(d: String): String {
        val x = digitsOnly(d).take(10)
        val p1 = x.take(3)
        val p2 = x.drop(3).take(3)
        val p3 = x.drop(6).take(4)
        return buildString {
            if (p1.isNotEmpty()) append(p1)
            if (p2.isNotEmpty()) append(" ").append(p2)
            if (p3.isNotEmpty()) append(" ").append(p3)
        }
    }
    private fun setupPhoneFormattingWatcher() {
        var sc = false
        binding.etUsuario.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (sc) return
                val c = s?.toString().orEmpty()
                val r = digitsOnly(c)
                val l = r.take(10)
                val f = formatPhoneForDisplay(l)
                if (c != f) {
                    sc = true
                    binding.etUsuario.setText(f)
                    binding.etUsuario.setSelection(f.length)
                    sc = false
                }
            }
        })
    }

    private fun checkFingerprint() {
        val biometricHelper = BiometricHelper(this)
        
        // Verificar si tiene huella registrada
        if (biometricHelper.isFingerprintEnabled() && biometricHelper.isBiometricAvailable()) {
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
        
        val tvSensorText = view.findViewById<android.widget.TextView>(R.id.tvSensorText)
        val tvValidating = view.findViewById<android.widget.TextView>(R.id.tvValidating)
        val ivIcon = view.findViewById<android.widget.ImageView>(R.id.ivFingerprintIcon)
        val btnCancel = view.findViewById<android.widget.TextView>(R.id.btnCancel)
        
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
        
        // Iniciar autenticación biométrica (sin animaciones, solo indicador)
        val biometricHelper = BiometricHelper(this)
        biometricHelper.showFingerprintAuth(
            onSuccess = { userPhone ->
                // Mostrar indicador verde de validación exitosa
                tvValidating.visibility = View.VISIBLE
                tvValidating.text = "✓ Verificado"
                tvValidating.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                
                // Cambiar fondo del icono a verde
                ivIcon.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, android.R.color.holo_green_light)
                )
                
                // Pequeño delay para mostrar la verificación
                lifecycleScope.launch {
                    delay(300)
                    dialog.dismiss()
                    // Autocompletar número y proceder
                    val formatted = formatPhoneForDisplay(userPhone)
                    binding.etUsuario.setText(formatted)
                    queryUserAndProceed(userPhone)
                }
            },
            onError = { error ->
                if (error == "usar_pin") {
                    // Usuario eligió usar PIN, cerrar diálogo
                    dialog.dismiss()
                } else {
                    // Mostrar error
                    tvSensorText.text = error
                    tvSensorText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    tvValidating.visibility = View.GONE
                }
            }
        )
    }
    
    private fun setupClickListeners() {
        val doLogin: () -> Unit = {
            val raw = binding.etUsuario.text?.toString()?.trim() ?: ""
            val phone = digitsOnly(raw)
            when {
                phone.isEmpty() -> showError("Ingresa tu número de celular")
                !phone.matches(Regex("\\d{10}")) -> showError("El número debe tener 10 dígitos")
                else -> {
                    savePhone(phone)
                    queryUserAndProceed(phone)
                }
            }
        }

        binding.btnLogin.setOnClickListener { doLogin.invoke() }
        // Botón de cash abre modal de acceso
        binding.btnSend.setOnClickListener { showAccessModal() }

        // Cerrar banner de error
        binding.errorNotification.btnClose.setOnClickListener {
            binding.errorNotification.root.isVisible = false
        }

        // Copiar clave dinámica al portapapeles
        binding.ivCopy.setOnClickListener {
            val code = binding.tvDynamicCode.text?.toString()?.trim().orEmpty()
            if (code.isNotEmpty()) {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Clave dinámica", code))
                com.ios.nequixofficialv2.utils.NequiAlert.showSuccess(this, "Clave copiada", 2000L)
            }
        }

        // Botón ayuda abre modal de ayuda avanzada
        binding.ivInfo.setOnClickListener { showHelpModal() }

        // "¿Cambiaste tu cel?" abre modal de actualización de número
        binding.ivCellIcon.setOnClickListener { showChangeNumberModal() }
        binding.tvCellChangeText.setOnClickListener { showChangeNumberModal() }
    }

    private fun queryUserAndProceed(phone: String) {
        setLoading(true)
        CoroutineScope(Dispatchers.Main).launch {
            // 🔐 AUTENTICAR PRIMERO antes de consultar Firestore
            val authSuccess = withContext(Dispatchers.IO) {
                try {
                    auth.signInAnonymously().await()
                    auth.currentUser?.updateProfile(
                        com.google.firebase.auth.UserProfileChangeRequest.Builder()
                            .setDisplayName(phone)
                            .build()
                    )?.await()
                    true
                } catch (e: Exception) {
                    android.util.Log.e("LoginActivity", "Error auth: ${e.message}")
                    false
                }
            }

            if (!authSuccess) {
                setLoading(false)
                android.widget.Toast.makeText(
                    this@LoginActivity,
                    "Error de autenticación. Revisa logs.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val snapshot = withContext(Dispatchers.IO) {
                try { 
                    android.util.Log.d("LoginActivity", "Buscando usuario: users/$phone")
                    val doc = db.collection("users").document(phone).get().await()
                    android.util.Log.d("LoginActivity", "Documento existe: ${doc.exists()}")
                    if (doc.exists()) {
                        android.util.Log.d("LoginActivity", "Datos: ${doc.data}")
                        android.util.Log.d("LoginActivity", "isActive: ${doc.getBoolean("isActive")}")
                    }
                    doc
                } catch (e: Exception) { 
                    android.util.Log.e("LoginActivity", "Error Firestore: ${e.message}", e)
                    null 
                }
            }

            if (snapshot == null || !snapshot.exists()) {
                setLoading(false)
                android.util.Log.e("LoginActivity", "Usuario no encontrado: $phone")
                com.ios.nequixofficialv2.utils.NequiAlert.showError(
                    this@LoginActivity,
                    "Actívate con @sangre_binerojs"
                )
                return@launch
            }

            val isActive = snapshot.getBoolean("isActive") ?: false
            android.util.Log.d("LoginActivity", "isActive final: $isActive")
            
            if (!isActive) {
                setLoading(false)
                com.ios.nequixofficialv2.utils.NequiAlert.showError(
                    this@LoginActivity,
                    "Actívate con @sangre_binerojs"
                )
                return@launch
            }

            setLoading(false)

            val intent = Intent(this@LoginActivity, PinActivity::class.java)
            intent.putExtra("user_phone", phone)
            startActivity(intent)
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun setLoading(isLoading: Boolean) {
        // Usar SOLO el spinner del botón (no mostrar el externo)
        binding.progressBar.isVisible = false
        // Bloquear acciones mientras carga
        binding.btnLogin.isEnabled = !isLoading
        binding.btnSend.isEnabled = !isLoading
        // Mostrar carga dentro del botón y ocultar texto
        binding.pbButton.isVisible = isLoading
        binding.tvLoginText.isVisible = !isLoading
        // Efecto visual de "apagado"
        binding.btnLogin.alpha = if (isLoading) 0.6f else 1f
    }

    private fun showError(message: String) {
        binding.errorNotification.tvMessage.text = message
        val v = binding.errorNotification.root
        v.bringToFront()
        v.elevation = 20f
        v.clearAnimation()
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = -12f
        v.isVisible = true
        // Fade-in y leve slide
        v.animate().alpha(1f).translationY(0f).setDuration(200).start()
        errorHideJob?.cancel()
        errorHideJob = lifecycleScope.launch {
            // Desvanecimiento progresivo durante 5s
            val total = 5000L
            val steps = 25
            val stepDur = total / steps
            for (i in 1..steps) {
                delay(stepDur)
                val remaining = steps - i
                v.alpha = 0.4f + (remaining / steps.toFloat()) * 0.6f // 1.0 -> 0.4 lineal
            }
            v.animate().alpha(0f).setDuration(220).withEndAction {
                v.isVisible = false
                v.alpha = 1f
            }.start()
        }
    }

    // ===== Animación de clave dinámica (30s) =====
    private var dynamicTimer: CountDownTimer? = null
    private var progressAnimator: ValueAnimator? = null
    private fun startDynamicKeyTimer() {
        fun newCode(): String = (100000..999999).random().toString()
        fun startCycle(initial: Boolean = false) {
            if (initial && binding.tvDynamicCode.text.isNullOrBlank()) {
                // Mostrar un código inicial
                binding.tvDynamicCode.text = newCode()
            }
            // Asegurar visibilidad del círculo
            binding.ivCircleProgress.visibility = View.VISIBLE
            dynamicTimer?.cancel()
            val total = 30_000L
            // Animación de llenado real 0->1 en 30s
            val ring = binding.ivCircleProgress
            progressAnimator?.cancel()
            ring.progress = 0f
            progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = total
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    ring.progress = (anim.animatedValue as Float)
                }
                start()
            }
            dynamicTimer = object : CountDownTimer(total, 50L) {
                override fun onTick(millisUntilFinished: Long) {
                    // El llenado lo maneja el ValueAnimator
                }
                override fun onFinish() {
                    // Completar y cambiar clave exactamente al terminar
                    progressAnimator?.cancel()
                    ring.progress = 1f
                    binding.tvDynamicCode.text = newCode()
                    // Reiniciar el ciclo
                    ring.progress = 0f
                    startCycle()
                }
            }.start()
        }
        startCycle(initial = true)
    }

    override fun onDestroy() {
        dynamicTimer?.cancel()
        progressAnimator?.cancel()
        super.onDestroy()
    }

    // ===== Modales =====
    private fun maybeShowLoginOverlay() {
        val prefs = getSharedPreferences("login_overlay", MODE_PRIVATE)
        val overlay = layoutInflater.inflate(R.layout.overlay_login_splash, null)
        val root = findViewById<ViewGroup>(android.R.id.content)
        overlay.isClickable = true
        overlay.isFocusable = true
        root.addView(overlay)

        overlay.post {
            // Estados iniciales para animación (fondo negro + card en escala menor)
            val overlayRoot = overlay.findViewById<View>(R.id.overlayRoot)
            val card = overlay.findViewById<View>(R.id.cardContainer)
            overlayRoot?.alpha = 0f
            card?.apply {
                scaleX = 0.7f
                scaleY = 0.7f
                alpha = 0f
                // Hardware layer para animación suave
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            // Paso 1: mostrar negro primero
            overlayRoot?.animate()?.alpha(1f)?.setDuration(350)?.withEndAction {
                // Paso 2: pop 3D del card
                card?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(450)
                    ?.setInterpolator(android.view.animation.OvershootInterpolator(1.3f))
                    ?.withEndAction {
                        // Paso 3: botones en cascada (solo 2 botones)
                        fun dpAnim(v: Float) = v * resources.displayMetrics.density
                        val b1 = overlay.findViewById<View>(R.id.btnOfficialGroup)
                        val b2 = overlay.findViewById<View>(R.id.btnCreatorAngyyOverlay)
                        listOf(b1, b2).forEach { btn ->
                            btn?.alpha = 0f
                            btn?.translationY = dpAnim(24f)
                        }
                        b1?.animate()?.alpha(1f)?.translationY(0f)?.setDuration(220)?.start()
                        b2?.animate()?.alpha(1f)?.translationY(0f)?.setDuration(220)?.setStartDelay(80)?.start()
                    }?.start()
            }?.start()

            // Animaciones de fondo deshabilitadas (diseño simplificado)
            // val bg = overlay.findViewById<View>(R.id.animatedBg)
            // if (bg != null) { ... }
        }

        // Acciones
        overlay.findViewById<View>(R.id.btnClose)?.setOnClickListener {
            animateOverlayOut(root, overlay)
        }
        overlay.findViewById<View>(R.id.tvNoMostrar)?.setOnClickListener {
            // Ya no se respeta para próximas aperturas: siempre se mostrará al volver a abrir la app.
            prefs.edit().putBoolean("dont_show", false).apply()
            animateOverlayOut(root, overlay)
        }
        overlay.findViewById<View>(R.id.btnOfficialGroup)?.setOnClickListener {
            openTelegram("https://t.me/nequisan")
        }
        overlay.findViewById<View>(R.id.btnCreatorAngyyOverlay)?.setOnClickListener {
            openTelegram("https://t.me/sangre_binerojs")
        }
    }

    private fun animateOverlayOut(root: ViewGroup, overlay: View) {
        val overlayRoot = overlay.findViewById<View>(R.id.overlayRoot)
        val card = overlay.findViewById<View>(R.id.cardContainer)
        // Detener animación de paisaje si existe (deshabilitado)
        // (overlay.getTag(R.id.animatedBg) as? android.animation.Animator)?.cancel()
        // Animación de salida: leve shrink + fade
        var finished = 0
        fun finishOnce() {
            finished++
            if (finished >= 2) {
                root.removeView(overlay)
            }
        }
        overlayRoot?.animate()?.alpha(0f)?.setDuration(160)?.withEndAction { finishOnce() }?.start()
        card?.animate()?.alpha(0f)?.scaleX(0.9f)?.scaleY(0.9f)?.setDuration(160)?.withEndAction { finishOnce() }?.start()
    }
    private fun showAccessModal() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val view = layoutInflater.inflate(R.layout.splash_model, null)
        // Botones Telegram
        view.findViewById<View>(R.id.btnJoinGroup)?.setOnClickListener {
            openTelegram("https://t.me/nequisan")
        }
        view.findViewById<View>(R.id.btnTelegramContact)?.setOnClickListener {
            openTelegram("https://t.me/sangre_binerojs")
        }
        view.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
        // Animación de entrada
        view.alpha = 0f
        view.scaleX = 0.7f
        view.scaleY = 0.7f
        view.translationY = 50f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(450)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.3f))
            .start()
    }

    private fun showHelpModal() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val view = layoutInflater.inflate(R.layout.splash_help, null)
        // Animaciones avanzadas deshabilitadas (diseño simplificado)
        // val bg = view.findViewById<View>(R.id.animatedBg)
        // val flash = view.findViewById<View>(R.id.flashLayer)
        // Animaciones comentadas ya que los elementos no existen en el nuevo diseño
        /*
        var animSet: android.animation.AnimatorSet? = null
        var flashSet: android.animation.AnimatorSet? = null
        view.post { ... }
        dialog.setOnDismissListener {
            animSet?.cancel()
            flashSet?.cancel()
        }
        */
        // Botones Telegram
        view.findViewById<View>(R.id.btnTelegramHelp)?.setOnClickListener {
            openTelegram("https://t.me/nequisan")
        }
        view.findViewById<View>(R.id.btnJoinGroupHelp)?.setOnClickListener {
            openTelegram("https://t.me/sangre_binerojs")
        }
        view.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
        // Animación de entrada
        view.alpha = 0f
        view.scaleX = 0.7f
        view.scaleY = 0.7f
        view.translationY = 50f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(450)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.3f))
            .start()
    }

    private fun openTelegram(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "No se pudo abrir Telegram")
        }
    }

    private fun showChangeNumberModal() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val view = layoutInflater.inflate(R.layout.splash_change_number, null)
        // Botones Telegram
        view.findViewById<View>(R.id.btnContact)?.setOnClickListener {
            openTelegram("https://t.me/nequisan")
        }
        view.findViewById<View>(R.id.btnJoinGroupChange)?.setOnClickListener {
            openTelegram("https://t.me/sangre_binerojs")
        }
        view.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
        // Animación de entrada
        view.alpha = 0f
        view.scaleX = 0.7f
        view.scaleY = 0.7f
        view.translationY = 50f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(450)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.3f))
            .start()
    }

    // ===== Sistema de Actualizaciones =====
    private var updateDialog: com.ios.nequixofficialv2.update.UpdateDialog? = null

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                android.util.Log.d("LoginActivity", "🔍 Verificando actualizaciones...")
                
                val updateManager = com.ios.nequixofficialv2.update.UpdateManager(this@LoginActivity)
                val updateInfo = updateManager.checkForUpdate()
                
                android.util.Log.d("LoginActivity", "📊 UpdateInfo: needsUpdate=${updateInfo.needsUpdate}, isMandatory=${updateInfo.isMandatory}")
                
                // Mostrar diálogo solo si hay actualización disponible
                if (updateInfo.needsUpdate && updateDialog?.isShowing() != true) {
                    android.util.Log.d("LoginActivity", "✅ Mostrando diálogo de actualización")
                    updateDialog = com.ios.nequixofficialv2.update.UpdateDialog(
                        context = this@LoginActivity,
                        updateInfo = updateInfo,
                        onUpdateClick = {
                            android.util.Log.d("LoginActivity", "👆 Usuario hizo clic en Actualizar")
                            // Abrir URL de descarga
                            updateManager.openUpdateUrl(updateInfo.updateUrl)
                            // Si es actualización obligatoria, cerrar la app después de ir a la URL
                            if (updateInfo.isMandatory) {
                                finish()
                            }
                        },
                        onLaterClick = {
                            android.util.Log.d("LoginActivity", "👆 Usuario hizo clic en Más tarde")
                            // Usuario decidió actualizar más tarde
                        }
                    )
                    updateDialog?.show()
                } else {
                    android.util.Log.d("LoginActivity", "ℹ️ No se muestra diálogo: needsUpdate=${updateInfo.needsUpdate}, dialogShowing=${updateDialog?.isShowing()}")
                }
            } catch (e: Exception) {
                // Manejar silenciosamente errores de verificación de actualización
                android.util.Log.e("LoginActivity", "❌ Error en checkForUpdates: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }
    
}