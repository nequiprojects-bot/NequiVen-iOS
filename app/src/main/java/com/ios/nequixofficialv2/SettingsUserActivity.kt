package com.ios.nequixofficialv2

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class SettingsUserActivity : AppCompatActivity() {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var userPhone: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_user)
        
        // Aplicar color morado original a la barra de estado
        try {
            window.statusBarColor = ContextCompat.getColor(this, R.color.color_200020)
        } catch (_: Exception) {}

        userPhone = intent.getStringExtra("user_phone") ?: ""

        // Back
        val btnBack = findViewById<android.widget.ImageView>(R.id.btnBack)
        btnBack.setOnClickListener { finish(); overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right) }

        val etName = findViewById<android.widget.EditText>(R.id.etName)
        val etPhone = findViewById<android.widget.EditText>(R.id.etPhone)
        val btnSave = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveSettings)
        val btnCancel = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelSettings)

        // Formateo en vivo del número: AAA BBB CCCC
        etPhone.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormatting) return
                val digits = s?.toString()?.filter { it.isDigit() } ?: return
                if (digits.isEmpty()) return
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
                if (formatted != s.toString()) {
                    isFormatting = true
                    etPhone.setText(formatted)
                    etPhone.setSelection(formatted.length)
                    isFormatting = false
                }
            }
        })

        // No precargar valores: el usuario debe ingresar manualmente nombre y número

        btnSave.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            val phoneFormatted = etPhone.text?.toString()?.trim().orEmpty()
            val phoneDigits = phoneFormatted.filter { it.isDigit() }

            if (name.isEmpty() || phoneDigits.isEmpty()) {
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Completa nombre y celular")
                return@setOnClickListener
            }
            if (userPhone.isEmpty()) {
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "No se encontró el usuario")
                return@setOnClickListener
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Guardar contacto en subcolección: users/{userPhone}/contacts/{phoneDigits}
                    val contact = hashMapOf(
                        "name" to name,
                        "phone" to phoneDigits,
                        "displayPhone" to phoneFormatted,
                        "updatedAt" to System.currentTimeMillis()
                    )
                    db.collection("users").document(userPhone)
                        .collection("contacts").document(phoneDigits)
                        .set(contact).await()
                    withContext(Dispatchers.Main) {
                        com.ios.nequixofficialv2.utils.NequiAlert.showSuccess(this@SettingsUserActivity, "Contacto guardado", 2000L)
                        finish()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this@SettingsUserActivity, "Error al guardar: ${e.localizedMessage}")
                    }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                window.decorView.postDelayed({ finish() }, 120)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                window.decorView.postDelayed({ finish() }, 50)
            }
        }
        
        btnCancel.setOnClickListener { finish() }
    }
}
