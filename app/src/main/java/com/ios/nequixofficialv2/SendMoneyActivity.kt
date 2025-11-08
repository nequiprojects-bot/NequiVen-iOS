package com.ios.nequixofficialv2

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import com.google.android.material.textfield.TextInputEditText
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth

class SendMoneyActivity : AppCompatActivity() {
    private var isFormattingPhone = false
    private var isFormattingAmount = false
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_money)
        
        // Aplicar color morado original a la barra de estado
        try {
            window.statusBarColor = ContextCompat.getColor(this, R.color.color_200020)
        } catch (_: Exception) {}

        // Flechitas: regresar
        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btnDown)?.setOnClickListener { finish() }

        val etPhone = findViewById<TextInputEditText>(R.id.etPhone)
        val etAmount = findViewById<TextInputEditText>(R.id.etAmount)
        val btnContinue = findViewById<Button>(R.id.btnContinue)

        // Evitar foco inicial en campos (que se vea "activo" y colorido sin tocar)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        etPhone?.clearFocus()
        etAmount?.clearFocus()
        val rootView = findViewById<View>(android.R.id.content)
        rootView?.isFocusableInTouchMode = true
        rootView?.requestFocus()

        fun evalContinue() {
            val phoneDigits = etPhone.text?.toString()?.filter { it.isDigit() } ?: ""
            val amountDigits = etAmount.text?.toString()?.filter { it.isDigit() } ?: ""
            val valid = phoneDigits.length == 10 && (amountDigits.toLongOrNull() ?: 0L) > 0L
            btnContinue?.isEnabled = valid
            btnContinue?.alpha = if (valid) 1f else 0.6f
        }
        // Estado inicial atenuado
        btnContinue?.alpha = 0.6f

        // Formateo en vivo del teléfono: 300 000 0000 (máx 10 dígitos)
        etPhone?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormattingPhone) return
                val raw = s?.toString().orEmpty()
                val digits = raw.filter { it.isDigit() }.take(10)
                val formatted = buildString {
                    append(digits.take(3))
                    if (digits.length > 3) {
                        append(" ")
                        append(digits.substring(3, minOf(6, digits.length)))
                    }
                    if (digits.length > 6) {
                        append(" ")
                        append(digits.substring(6, minOf(10, digits.length)))
                    }
                }
                if (formatted != raw) {
                    isFormattingPhone = true
                    etPhone.setText(formatted)
                    etPhone.setSelection(formatted.length)
                    isFormattingPhone = false
                }
                evalContinue()
            }
        })

        // Formateo en vivo del monto: "$ 5" o "$ 1.000" (con separadores de miles con punto)
        etAmount?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormattingAmount) return
                val raw = s?.toString().orEmpty()
                val digits = raw.filter { it.isDigit() }
                if (digits.isEmpty()) {
                    // Vaciar por completo si no hay números (sin símbolo)
                    isFormattingAmount = true
                    etAmount.setText("")
                    etAmount.setSelection(0)
                    isFormattingAmount = false
                    evalContinue()
                    return
                }
                // Con separadores de miles con punto y espacio tras el símbolo
                val symbols = DecimalFormatSymbols().apply {
                    groupingSeparator = '.'
                }
                val df = DecimalFormat("#,###", symbols)
                val body = df.format(digits.toLong())
                val formatted = "$ $body"
                if (formatted != raw) {
                    isFormattingAmount = true
                    etAmount.setText(formatted)
                    etAmount.setSelection(formatted.length)
                    isFormattingAmount = false
                }
                evalContinue()
            }
        })

        // Abrir bottom sheet de confirmación al seguir
        btnContinue?.setOnClickListener {
            val phoneShown = etPhone?.text?.toString().orEmpty()
            val phoneDigits = phoneShown.filter { it.isDigit() }
            
            // ✅ Obtener userPhone del Intent o de FirebaseAuth
            var userPhone = intent.getStringExtra("user_phone").orEmpty()
            if (userPhone.isEmpty()) {
                userPhone = auth.currentUser?.phoneNumber?.filter { it.isDigit() }?.let {
                    if (it.length > 10) it.takeLast(10) else it
                }.orEmpty()
            }
            
            val userPhoneDigits = userPhone.filter { it.isDigit() }.let { if (it.length > 10) it.takeLast(10) else it }
            
            android.util.Log.d("SendMoney", "🔍 userPhone desde Intent/Auth: '$userPhone'")
            android.util.Log.d("SendMoney", "🔍 userPhoneDigits normalizado: '$userPhoneDigits'")
            
            // ✅ VALIDACIÓN 1: No puedes enviarte plata a ti mismo
            if (phoneDigits == userPhoneDigits) {
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "No puedes enviarte plata a ti mismo")
                return@setOnClickListener
            }
            
            // ✅ VALIDACIÓN 2: Verificar si está en contactos O si existe en Firebase
            if (userPhoneDigits.isNotEmpty()) {
                db.collection("users").document(userPhoneDigits)
                    .collection("contacts").document(phoneDigits)
                    .get()
                    .addOnSuccessListener { contactDoc ->
                        if (contactDoc.exists()) {
                            // ✅ Está en contactos - continuar directo
                            android.util.Log.d("SendMoney", "✅ Número encontrado en contactos")
                            showConfirmationDialog(phoneShown, phoneDigits, userPhone)
                        } else {
                            // No está en contactos - verificar si existe en Firebase users
                            android.util.Log.d("SendMoney", "No está en contactos, verificando en Firebase users")
                            db.collection("users").document(phoneDigits)
                                .get()
                                .addOnSuccessListener { userDoc ->
                                    if (!userDoc.exists()) {
                                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Este usuario no fue encontrado o no existe")
                                        return@addOnSuccessListener
                                    }
                                    // Usuario válido en Firebase, continuar
                                    showConfirmationDialog(phoneShown, phoneDigits, userPhone)
                                }
                                .addOnFailureListener {
                                    com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error al verificar el usuario. Intenta de nuevo")
                                }
                        }
                    }
                    .addOnFailureListener {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error al verificar el contacto. Intenta de nuevo")
                    }
            } else {
                // No hay userPhone - solo verificar en Firebase users
                db.collection("users").document(phoneDigits)
                    .get()
                    .addOnSuccessListener { userDoc ->
                        if (!userDoc.exists()) {
                            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Este usuario no fue encontrado o no existe")
                            return@addOnSuccessListener
                        }
                        showConfirmationDialog(phoneShown, phoneDigits, userPhone)
                    }
                    .addOnFailureListener {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error al verificar el usuario. Intenta de nuevo")
                    }
            }
        }
    }
    
    private fun showConfirmationDialog(phoneShown: String, phoneDigits: String, userPhone: String) {
        val dialog = BottomSheetDialog(this)
        dialog.window?.setWindowAnimations(0)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_confirm_number, null)
        
        val etAmount = findViewById<TextInputEditText>(R.id.etAmount)
        val tvRecipientName = view.findViewById<TextView>(R.id.tvRecipientName)
        val tvPhoneNumber = view.findViewById<TextView>(R.id.tvPhoneNumber)
        val tvAmount = view.findViewById<TextView>(R.id.tvAmount)
        var fullRecipientName = "" // Variable para guardar el nombre completo

        // Asignar valores formateados
        val amountShown = etAmount?.text?.toString().orEmpty()
        tvRecipientName?.text = "" // se completará si hay contacto guardado
        tvPhoneNumber?.text = phoneShown
        tvAmount?.text = amountShown

        // ✅ Buscar en 2 lugares: 1) Contactos de Settings, 2) Firebase users
        if (phoneDigits.length == 10) {
            // Normalizar userPhone a 10 dígitos
            val normalizedUserPhone = userPhone.filter { it.isDigit() }.let { 
                if (it.length > 10) it.takeLast(10) else it 
            }
            
            android.util.Log.d("SendMoney", "====== BÚSQUEDA DE CONTACTO ======")
            android.util.Log.d("SendMoney", "userPhone original: '$userPhone'")
            android.util.Log.d("SendMoney", "userPhone normalizado: '$normalizedUserPhone'")
            android.util.Log.d("SendMoney", "phoneDigits destino: '$phoneDigits'")
            
            // Primero buscar en contactos del remitente
            if (normalizedUserPhone.isNotEmpty()) {
                android.util.Log.d("SendMoney", "Buscando en: users/${normalizedUserPhone}/contacts/${phoneDigits}")
                db.collection("users").document(normalizedUserPhone)
                    .collection("contacts").document(phoneDigits)
                    .get()
                    .addOnSuccessListener { contactDoc ->
                        android.util.Log.d("SendMoney", "Contacto existe: ${contactDoc.exists()}, name: ${contactDoc.getString("name")}")
                        val contactName = contactDoc.getString("name").orEmpty()
                        if (contactName.isNotBlank()) {
                            // ✅ ENCONTRADO en contactos de Settings
                            fullRecipientName = contactName
                            tvRecipientName?.text = maskName(toTitleCase(contactName))
                            android.util.Log.d("SendMoney", "✅ Usando nombre de contacto: $contactName")
                        } else {
                            // No está en contactos, buscar en Firebase
                            android.util.Log.d("SendMoney", "No encontrado en contactos, buscando en Firebase users")
                            searchRealUserName(phoneDigits, tvRecipientName) { realName ->
                                if (realName.isNotBlank()) {
                                    fullRecipientName = realName
                                }
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.e("SendMoney", "Error buscando contacto: ${e.message}")
                        // Si falla la búsqueda en contactos, buscar en users
                        searchRealUserName(phoneDigits, tvRecipientName) { realName ->
                            if (realName.isNotBlank()) {
                                fullRecipientName = realName
                            }
                        }
                    }
            } else {
                android.util.Log.d("SendMoney", "No hay userPhone, buscando directo en Firebase")
                // Si no hay userPhone, buscar directamente en users
                searchRealUserName(phoneDigits, tvRecipientName) { realName ->
                    if (realName.isNotBlank()) {
                        fullRecipientName = realName
                    }
                }
            }
        }

        // Acciones de botones
        view.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnCancel)?.setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnConfirm)?.setOnClickListener {
            // Ocultar IME y limpiar foco para evitar remaquetado durante el salto
            currentFocus?.let { v ->
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
            }
            val ctx = this
            val intent = Intent(ctx, AnimationPaymentActivity::class.java).apply {
                putExtra("phone", phoneShown)
                putExtra("amount", amountShown)
                putExtra("maskedName", fullRecipientName.ifBlank { tvRecipientName?.text?.toString().orEmpty() })
                putExtra("user_phone", userPhone)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            dialog.dismiss()
            // Pequeño diferido para permitir que el BottomSheet cierre y el IME desaparezca
            val delay = if (android.os.Build.VERSION.SDK_INT >= 34) 180L else 80L
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(intent)
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                    overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                }
            }, delay)
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun maskName(fullName: String): String {
        val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        fun maskPart(p: String): String {
            val visible = 3
            if (p.length <= 3) return p
            // Siempre mostrar 3 letras + asteriscos por el resto
            val starsCount = (p.length - visible).coerceAtLeast(1)
            return p.take(visible) + "*".repeat(starsCount)
        }
        return parts.joinToString(" ") { maskPart(it) }
    }

    private fun toTitleCase(input: String): String {
        if (input.isBlank()) return input
        return input.lowercase(java.util.Locale.getDefault())
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(java.util.Locale.getDefault()) else c.toString() }
            }
    }
    
    /**
     * Busca el nombre real del usuario en Firebase y lo muestra en Title Case
     */
    private fun searchRealUserName(phoneDigits: String, textView: TextView?, callback: (String) -> Unit) {
        db.collection("users").document(phoneDigits)
            .get()
            .addOnSuccessListener { userDoc ->
                val realName = userDoc.getString("name").orEmpty()
                if (realName.isNotBlank() && 
                    !realName.equals("NEQUIXOFFICIAL", ignoreCase = true) && 
                    !realName.equals("USUARIO NEQUI", ignoreCase = true)) {
                    // Mostrar nombre enmascarado con Title Case
                    val titleCaseName = toTitleCase(realName)
                    textView?.text = maskName(titleCaseName)
                    callback(realName)
                } else {
                    // Si no tiene nombre válido, mostrar número formateado
                    val formattedPhone = "+57 ${phoneDigits.substring(0,3)} ${phoneDigits.substring(3,6)} ${phoneDigits.substring(6)}"
                    textView?.text = formattedPhone
                    callback("")
                }
            }
            .addOnFailureListener {
                // En caso de error, mostrar número formateado
                val formattedPhone = "+57 ${phoneDigits.substring(0,3)} ${phoneDigits.substring(3,6)} ${phoneDigits.substring(6)}"
                textView?.text = formattedPhone
                callback("")
            }
    }
}
