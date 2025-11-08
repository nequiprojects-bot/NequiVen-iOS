package com.ios.nequixofficialv2

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.ios.nequixofficialv2.utils.AndroidCompatibilityHelper

class AddQrKeyActivity : AppCompatActivity() {
    
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var userPhone: String = ""
    private var returnToPayment: Boolean = false
    
    companion object {
        const val REQUEST_SCAN_QR = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Aplicar barra de estado morada para mantener consistencia visual
        AndroidCompatibilityHelper.applyNequiStatusBar(this)
        
        userPhone = intent.getStringExtra("user_phone") ?: ""
        returnToPayment = intent.getBooleanExtra("return_to_payment", false)
        
        // Verificar si viene con datos prellenados desde pago QR
        val prefillMode = intent.getBooleanExtra("prefill_mode", false)
        
        if (prefillMode) {
            // Modo prellenado desde pago QR: ir directamente al diálogo con los datos
            val qrName = intent.getStringExtra("qr_name") ?: ""
            val qrKey = intent.getStringExtra("qr_key") ?: ""
            
            if (qrName.isNotEmpty() && qrKey.isNotEmpty()) {
                showKeyInputDialog(qrName, qrKey)
            } else {
                com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error: Datos del QR incompletos")
                finishWithoutAnimation()
            }
        } else {
            // Modo normal: escanear QR primero (para uso desde ajustes)
            launchQrScanner()
        }
    }
    
    private fun launchQrScanner() {
        val intent = Intent(this, QrScannerActivity::class.java)
        intent.putExtra("mode", "associate_key")
        intent.putExtra("user_phone", userPhone)
        startActivityForResult(intent, REQUEST_SCAN_QR)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_SCAN_QR) {
            if (resultCode == RESULT_OK) {
                val qrName = data?.getStringExtra("qr_name") ?: ""
                
                if (qrName.isNotEmpty()) {
                    // Mostrar diálogo para ingresar la llave (sin prellenar en este flujo)
                    showKeyInputDialog(qrName, "")
                } else {
                    com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "No se pudo obtener el nombre del QR")
                    finishWithoutAnimation()
                }
            } else {
                // Usuario canceló o no escaneó nada
                finishWithoutAnimation()
            }
        }
    }
    
    private fun showKeyInputDialog(qrName: String, prefilledKey: String = "") {
        // Generar llave automáticamente: 00 + 8 dígitos aleatorios
        val generatedKey = if (prefilledKey.isNotEmpty()) {
            prefilledKey
        } else {
            "00" + (10000000..99999999).random().toString()
        }
        
        // Crear layout vertical moderno
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 50, 60, 30)
        }
        
        // Título del negocio
        val titleText = android.widget.TextView(this).apply {
            text = "Negocio: $qrName"
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.black))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }
        
        // Mostrar llave generada (readonly)
        val keyInfoText = android.widget.TextView(this).apply {
            text = "Llave generada: $generatedKey"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.nequi_purple))
            setPadding(0, 10, 0, 30)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        // Solo campo para el banco (único editable)
        val bankEditText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "Banco destino (ej: Bancolombia, Nequi, Daviplata)"
            setPadding(30, 40, 30, 40)
            background = ContextCompat.getDrawable(context, R.drawable.bg_edittext_rounded)
            textSize = 15f
        }
        
        layout.addView(titleText)
        layout.addView(keyInfoText)
        layout.addView(bankEditText)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Guardar datos del QR")
            .setView(layout)
            .setPositiveButton("Guardar") { dialog, _ ->
                val bank = bankEditText.text.toString().trim()
                
                if (bank.isNotEmpty()) {
                    // Guardar con la llave generada automáticamente
                    saveQrKeyAssociation(qrName, generatedKey, bank)
                } else {
                    com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Debes ingresar el banco destino")
                    finishWithoutAnimation()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
                finishWithoutAnimation()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun finishWithoutAnimation() {
        // Desactivar animaciones para evitar destellos al regresar a BrebQrActivity
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        finish()
    }
    
    private fun saveQrKeyAssociation(qrName: String, key: String, bank: String) {
        if (userPhone.isEmpty()) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "No se encontró el usuario")
            finishWithoutAnimation()
            return
        }
        
        android.util.Log.d("AddQrKeyActivity", "💾 Guardando QR en Firebase:")
        android.util.Log.d("AddQrKeyActivity", "   Usuario: $userPhone")
        android.util.Log.d("AddQrKeyActivity", "   QR Name: $qrName")
        android.util.Log.d("AddQrKeyActivity", "   QR Key: $key")
        android.util.Log.d("AddQrKeyActivity", "   Banco: $bank")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Guardar en Firebase: users/{userPhone}/qr_keys/{qr_name}
                val qrKeyData = hashMapOf(
                    "qr_name" to qrName,
                    "qr_key" to key,
                    "bank_destination" to bank,
                    "created_at" to System.currentTimeMillis()
                )
                
                val path = "users/$userPhone/qr_keys/$qrName"
                android.util.Log.d("AddQrKeyActivity", "📍 Ruta Firebase: $path")
                
                db.collection("users").document(userPhone)
                    .collection("qr_keys").document(qrName)
                    .set(qrKeyData).await()
                
                android.util.Log.d("AddQrKeyActivity", "✅ QR guardado exitosamente en Firebase")
                
                withContext(Dispatchers.Main) {
                    if (returnToPayment) {
                        // Devolver resultado a BrebQrActivity
                        android.util.Log.d("AddQrKeyActivity", "🔙 Devolviendo datos a BrebQrActivity:")
                        android.util.Log.d("AddQrKeyActivity", "   bank_destination: $bank")
                        android.util.Log.d("AddQrKeyActivity", "   qr_name: $qrName")
                        android.util.Log.d("AddQrKeyActivity", "   qr_key: $key")
                        
                        val resultIntent = Intent()
                        resultIntent.putExtra("bank_destination", bank)
                        resultIntent.putExtra("qr_name", qrName)
                        resultIntent.putExtra("qr_key", key)
                        setResult(RESULT_OK, resultIntent)
                    } else {
                        com.ios.nequixofficialv2.utils.NequiAlert.showSuccess(
                            this@AddQrKeyActivity, 
                            "✅ Llave guardada para: $qrName\nBanco: $bank"
                        )
                    }
                    finishWithoutAnimation()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    com.ios.nequixofficialv2.utils.NequiAlert.showError(
                        this@AddQrKeyActivity, 
                        "Error al guardar: ${e.localizedMessage}"
                    )
                    finishWithoutAnimation()
                }
            }
        }
    }
}
