package com.ios.nequixofficialv2

import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import android.widget.Toast
import io.scanbot.demo.barcodescanner.e
import io.scanbot.demo.barcodescanner.model.Movement
import io.scanbot.demo.barcodescanner.model.MovementType
import java.util.Date
import com.ios.nequixofficialv2.utils.NotificationManager as AppNotificationManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.ios.nequixofficialv2.utils.AndroidCompatibilityHelper

class ComprobanteActivity : AppCompatActivity() {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var animDrawable: AnimationDrawable? = null
    private var avd: AnimatedVectorDrawable? = null
    private var avdCompat: AnimatedVectorDrawableCompat? = null
    private val appNotificationManager: AppNotificationManager by lazy { AppNotificationManager(this) }
    
    companion object {
        // 🚨 CONTROL ESTÁTICO ANTI-DUPLICADOS: Sobrevive a recreaciones de Activity
        private val processedTransfers = mutableSetOf<String>()
        
        // Limpiar transferencias antiguas (mayores a 30 segundos)
        private val transferTimestamps = mutableMapOf<String, Long>()
        
        private fun isTransferProcessed(transferId: String): Boolean {
            val now = System.currentTimeMillis()
            
            // Limpiar transferencias antiguas (>30 segundos)
            transferTimestamps.entries.removeAll { (_, timestamp) ->
                now - timestamp > 30000
            }
            processedTransfers.removeAll { id ->
                transferTimestamps[id]?.let { now - it > 30000 } ?: true
            }
            
            return processedTransfers.contains(transferId)
        }
        
        private fun markTransferAsProcessed(transferId: String) {
            processedTransfers.add(transferId)
            transferTimestamps[transferId] = System.currentTimeMillis()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comprobante)

        // Aplicar barra de estado morada para evitar destellos en Android 7-11
        AndroidCompatibilityHelper.applyNequiStatusBar(this)

        // TRANSICIÓN SUAVE: Fade-in del layout para evitar saltos blancos
        window.setBackgroundDrawableResource(R.color.comprobante_background)
        
        // Iniciar animación del círculo de carga si aplica
        startLoadingAnimation()

        // PROCESO FLUIDO: Iniciar validación inmediatamente sin delay para evitar saltos
        processPayment()
    }

    private fun processPayment() {
        // ANIMACIÓN FLUIDA: Delay mínimo para mostrar la animación sin saltos
        // Usar Handler con Looper explícito para Android 13-15
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val phone = intent.getStringExtra("phone").orEmpty()
                val amount = intent.getStringExtra("amount").orEmpty()
                // Fallback: si no viene en Intent, usar FirebaseAuth y normalizar a 10 dígitos
                val userPhone = intent.getStringExtra("user_phone").orEmpty().ifBlank {
                FirebaseAuth.getInstance().currentUser?.phoneNumber
                    ?.filter { it.isDigit() }
                    ?.let { if (it.length > 10) it.takeLast(10) else it }
                    .orEmpty()
            }

            val amountValue = parseAmountToLong(amount)

            fun go(clazz: Class<*>) {
                val i = Intent(this, clazz)
                i.putExtra("phone", phone)
                i.putExtra("amount", amount)
                i.putExtra("user_phone", userPhone)

                // Diferenciar si es un pago QR según longitud de número (en QR no hay 10 dígitos)
                if (clazz == VoucherActivity::class.java) {
                    val digits = phone.filter { it.isDigit() }
                    val isQrPayment = digits.length != 10
                    i.putExtra("is_qr_voucher", isQrPayment)
                    if (isQrPayment) {
                        intent.getStringExtra("maskedName")?.let { i.putExtra("maskedName", it) }
                    }
                }
                // Detener animaciones antes de navegar
                animDrawable?.stop()
                avd?.stop()
                avdCompat?.stop()
                startActivity(i)
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 120)
                } else {
                    @Suppress("DEPRECATION")
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 50)
                }
            }

            if (userPhone.isBlank() || amountValue == null) {
                // En flujo QR, continuar al comprobante
                val target = if (intent.hasExtra("maskedName")) VoucherActivity::class.java else SaldoInsuficienteActivity::class.java
                go(target)
                return@postDelayed
            }

            // Captura no nula para usar dentro de la transacción
            val required: Long = amountValue

            val normalizedDocId = userPhone.filter { it.isDigit() }.let { if (it.length > 10) it.takeLast(10) else it }
            val userRef = db.collection("users").document(normalizedDocId)

            // 1) Leer saldo primero y decidir insuficiencia real
            userRef.get().addOnSuccessListener { snap ->
                val current = readBalanceFlexible(snap, "saldo")
                if (current == null) {
                    go(HomeActivity::class.java)
                    return@addOnSuccessListener
                }
                if (current < required) {
                    val target = if (intent.hasExtra("maskedName")) VoucherActivity::class.java else SaldoInsuficienteActivity::class.java
                    go(target)
                    return@addOnSuccessListener
                }

                // 2) Ejecutar transacción para descontar
                db.runTransaction { transaction ->
                    val snapshot = transaction.get(userRef)
                    val currentBalance = readBalanceFlexible(snapshot, "saldo") ?: 0L
                    if (currentBalance < required) {
                        throw com.google.firebase.firestore.FirebaseFirestoreException("Saldo insuficiente", com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED)
                    }
                    transaction.update(userRef, "saldo", currentBalance - required)
                }.addOnSuccessListener {
                    // Registrar movimiento de salida antes de ir al comprobante
                    val phoneDigits = phone.filter { it.isDigit() }
                    val isQrPayment = intent.hasExtra("maskedName") && phoneDigits.length != 10
                    
                    // Para pagos QR, usar el nombre ofuscado directamente
                    if (isQrPayment) {
                        val qrNameRaw = intent.getStringExtra("maskedName").orEmpty()
                        val qrNameObfuscated = com.ios.nequixofficialv2.utils.AndroidCompatibilityHelper.obfuscateName(qrNameRaw, uppercase = true)
                        
                        android.util.Log.d("ComprobanteActivity", "💳 Pago QR - Nombre original: $qrNameRaw")
                        android.util.Log.d("ComprobanteActivity", "💳 Pago QR - Nombre ofuscado: $qrNameObfuscated")
                        
                        // ✅ LIMPIAR TILDES AUTOMÁTICAMENTE del nombre QR
                        val cleanedQrName = com.ios.nequixofficialv2.utils.StringUtils.cleanName(qrNameObfuscated)
                        
                        val movement = Movement(
                            id = "",
                            name = cleanedQrName.ifBlank { phoneDigits },
                            amount = required.toDouble(),
                            date = Date(),
                            phone = phoneDigits,
                            type = MovementType.OUTGOING,
                            isIncoming = false,
                            isQrPayment = true
                        )
                        
                        // 🚨 CONTROL ANTI-DUPLICADOS para QR
                        val transferId = "${userPhone}_to_${phoneDigits}_${required}_${System.nanoTime()}"
                        
                        if (isTransferProcessed(transferId)) {
                            android.util.Log.w("ComprobanteActivity", "⚠️ PAGO QR YA PROCESADO: $transferId")
                            go(VoucherActivity::class.java)
                            return@addOnSuccessListener
                        }
                        
                        markTransferAsProcessed(transferId)
                        android.util.Log.d("ComprobanteActivity", "🎯 PROCESANDO PAGO QR: $transferId")
                        
                        // Guardar movimiento OUTGOING para QR
                        e.saveMovement(this, movement) { success, error ->
                            android.util.Log.d("ComprobanteActivity", "✅ PAGO QR OUTGOING guardado - Success: $success")
                            go(VoucherActivity::class.java)
                        }
                        
                        // Guardar INCOMING para el receptor (si aplica)
                        saveIncomingMovementForRecipient(phoneDigits, qrNameObfuscated, required.toDouble(), userPhone, transferId)
                    } else {
                        // Flujo normal: resolver nombre del contacto/usuario
                        resolveRecipientName(userPhone, phoneDigits) { recipientName ->
                            // Aplicar formato Title Case consistente
                            val normalizedName = toTitleCase(recipientName.trim())
                            
                            // ✅ LIMPIAR TILDES AUTOMÁTICAMENTE del nombre del destinatario
                            val cleanedRecipientName = com.ios.nequixofficialv2.utils.StringUtils.cleanName(normalizedName)
                            
                            val movement = Movement(
                                id = "",
                                name = cleanedRecipientName.ifBlank { phoneDigits },
                                amount = required.toDouble(),
                                date = Date(),
                                phone = phoneDigits,
                                type = MovementType.OUTGOING,
                                isIncoming = false,
                                isQrPayment = false
                            )
                        // 🚨 CONTROL ANTI-DUPLICADOS: Crear ID único para esta transferencia
                        // Usar nanoTime para máxima unicidad (evita colisiones en milisegundo)
                        val transferId = "${userPhone}_to_${phoneDigits}_${required}_${System.nanoTime()}"
                        
                        if (isTransferProcessed(transferId)) {
                            android.util.Log.w("ComprobanteActivity", "⚠️⚠️⚠️ TRANSFERENCIA YA PROCESADA, OMITIENDO: $transferId")
                            go(VoucherActivity::class.java)
                            return@resolveRecipientName
                        }
                        
                        // Marcar como procesado INMEDIATAMENTE
                        markTransferAsProcessed(transferId)
                        android.util.Log.d("ComprobanteActivity", "🎯 PROCESANDO TRANSFERENCIA ÚNICA: $transferId")
                        android.util.Log.d("ComprobanteActivity", "Datos - PhoneDigits: $phoneDigits, NormalizedName: $normalizedName, Required: $required, UserPhone: $userPhone")
                        
                        // SOLUCIÓN SIMPLE: Solo guardar OUTGOING aquí
                        e.saveMovement(this, movement) { success, error ->
                            android.util.Log.d("ComprobanteActivity", "✅ OUTGOING guardado - Success: $success")
                            // Ir directamente al comprobante SIN crear INCOMING duplicado
                            go(VoucherActivity::class.java)
                        }
                        
                        // SEPARADO: Crear INCOMING para el receptor UNA SOLA VEZ (fuera del callback para evitar duplicados)
                        android.util.Log.d("ComprobanteActivity", "🎯 Iniciando guardado INCOMING para: $phoneDigits")
                        
                        // Guardar INCOMING inmediatamente después de marcar como procesado
                        saveIncomingMovementForRecipient(phoneDigits, normalizedName, required.toDouble(), userPhone, transferId)
                        }
                    }
                }.addOnFailureListener {
                    val target = if (intent.hasExtra("maskedName")) VoucherActivity::class.java else SaldoInsuficienteActivity::class.java
                    go(target)
                }
            }.addOnFailureListener {
                go(HomeActivity::class.java)
            }
            } catch (e: Exception) {
                android.util.Log.e("ComprobanteActivity", "Error in processPayment: ${e.message}")
                goHome()
            }
        }, 800) // FLUIDO: Reducido de 2000ms a 800ms para evitar saltos
    }

    override fun onBackPressed() { goHome() }

    private fun goHome() {
        val i = Intent(this, HomeActivity::class.java)
        val userPhone = intent.getStringExtra("user_phone")
        if (!userPhone.isNullOrBlank()) {
            i.putExtra("user_phone", userPhone)
        }
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(i)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            window.decorView.postDelayed({ finish() }, 120)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            window.decorView.postDelayed({ finish() }, 50)
        }
    }

    private fun resolveRecipientName(userPhone: String, phoneDigits: String, cb: (String) -> Unit) {
        if (userPhone.isBlank() || phoneDigits.isBlank()) {
            cb("")
            return
        }
        
        // 🔥 BÚSQUEDA DUAL: Primero en contactos, luego en Firebase users
        db.collection("users").document(userPhone)
            .collection("contacts").document(phoneDigits)
            .get()
            .addOnSuccessListener { contactSnap ->
                val contactName = contactSnap.getString("name")?.trim().orEmpty()
                
                if (contactName.isNotEmpty()) {
                    // ✅ Encontrado en contactos
                    android.util.Log.d("ComprobanteActivity", "📇 Nombre en contactos: $contactName")
                    cb(contactName)
                } else {
                    // ❌ No está en contactos, buscar en Firebase users collection
                    android.util.Log.d("ComprobanteActivity", "🔍 No encontrado en contactos, buscando en Firebase users...")
                    
                    db.collection("users").document(phoneDigits)
                        .get()
                        .addOnSuccessListener { userDoc ->
                            val realName = userDoc.getString("name")?.trim().orEmpty()
                            
                            if (realName.isNotBlank() && 
                                !realName.equals("NEQUIXOFFICIAL", ignoreCase = true) &&
                                !realName.equals("USUARIO NEQUI", ignoreCase = true)) {
                                // ✅ Nombre real encontrado
                                android.util.Log.d("ComprobanteActivity", "✅ Nombre real en Firebase: $realName")
                                cb(realName)
                            } else {
                                // ⚠️ Nombre inválido, usar número formateado
                                val formattedPhone = if (phoneDigits.length == 10) {
                                    "+57 ${phoneDigits.substring(0,3)} ${phoneDigits.substring(3,6)} ${phoneDigits.substring(6)}"
                                } else {
                                    phoneDigits
                                }
                                android.util.Log.d("ComprobanteActivity", "⚠️ Nombre inválido, usando: $formattedPhone")
                                cb(formattedPhone)
                            }
                        }
                        .addOnFailureListener { e ->
                            // ❌ Error en Firebase users, usar número formateado
                            val formattedPhone = if (phoneDigits.length == 10) {
                                "+57 ${phoneDigits.substring(0,3)} ${phoneDigits.substring(3,6)} ${phoneDigits.substring(6)}"
                            } else {
                                phoneDigits
                            }
                            android.util.Log.e("ComprobanteActivity", "❌ Error en Firebase users: ${e.message}, usando: $formattedPhone")
                            cb(formattedPhone)
                        }
                }
            }
            .addOnFailureListener { e ->
                // ❌ Error en contactos, intentar Firebase users directamente
                android.util.Log.e("ComprobanteActivity", "❌ Error en contactos: ${e.message}, intentando Firebase users...")
                
                db.collection("users").document(phoneDigits)
                    .get()
                    .addOnSuccessListener { userDoc ->
                        val realName = userDoc.getString("name")?.trim().orEmpty()
                        
                        if (realName.isNotBlank() && 
                            !realName.equals("NEQUIXOFFICIAL", ignoreCase = true) &&
                            !realName.equals("USUARIO NEQUI", ignoreCase = true)) {
                            android.util.Log.d("ComprobanteActivity", "✅ Nombre real en Firebase: $realName")
                            cb(realName)
                        } else {
                            val formattedPhone = if (phoneDigits.length == 10) {
                                "+57 ${phoneDigits.substring(0,3)} ${phoneDigits.substring(3,6)} ${phoneDigits.substring(6)}"
                            } else {
                                phoneDigits
                            }
                            android.util.Log.d("ComprobanteActivity", "⚠️ Usando número formateado: $formattedPhone")
                            cb(formattedPhone)
                        }
                    }
                    .addOnFailureListener { e2 ->
                        val formattedPhone = if (phoneDigits.length == 10) {
                            "+57 ${phoneDigits.substring(0,3)} ${phoneDigits.substring(3,6)} ${phoneDigits.substring(6)}"
                        } else {
                            phoneDigits
                        }
                        android.util.Log.e("ComprobanteActivity", "❌ Error total: ${e2.message}, usando: $formattedPhone")
                        cb(formattedPhone)
                    }
            }
    }

    private fun startLoadingAnimation() {
        val iv = findViewById<ImageView?>(R.id.loadingCircleComprobante) ?: return
        val d = iv.drawable ?: return
        when (d) {
            is AnimationDrawable -> {
                animDrawable = d.apply { isOneShot = false; start() }
            }
            is AnimatedVectorDrawable -> {
                avd = d
                avd?.start()
            }
            else -> {
                val maybeCompat = AnimatedVectorDrawableCompat.create(this, R.drawable.loading_circle_comprobante)
                if (maybeCompat != null) {
                    iv.setImageDrawable(maybeCompat)
                    avdCompat = maybeCompat
                    avdCompat?.start()
                }
            }
        }
    }
    override fun onPause() {
        super.onPause()
        // Detener para ahorrar recursos
        animDrawable?.stop()
        // AVD se detiene solo, pero por seguridad
    }

    private fun parseAmountToLong(amount: String?): Long? {
        if (amount.isNullOrBlank()) return null
        // Solución directa: ignorar todo excepto los dígitos
        val digits = amount.filter { it.isDigit() }
        return digits.toLongOrNull()
    }

    private fun readBalanceFlexible(snap: com.google.firebase.firestore.DocumentSnapshot, field: String): Long? {
        val anyVal = snap.get(field) ?: return null
        return when (anyVal) {
            is Number -> anyVal.toLong()
            is String -> {
                // Solución directa: ignorar todo excepto los dígitos
                val digits = anyVal.filter { it.isDigit() }
                digits.toLongOrNull()
            }
            else -> null
        }
    }

    private fun toTitleCase(input: String): String {
        if (input.isBlank()) return input
        return input.lowercase(java.util.Locale.getDefault())
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { c -> 
                    if (c.isLowerCase()) c.titlecase(java.util.Locale.getDefault()) 
                    else c.toString() 
                }
            }
    }
    
    /**
     * Guarda movimiento INCOMING para el receptor de la transferencia
     */
    private fun saveIncomingMovementForRecipient(
        recipientPhoneDigits: String, 
        recipientName: String, 
        amount: Double, 
        senderPhone: String,
        transferId: String
    ) {
        android.util.Log.d("ComprobanteActivity", "🎯 GUARDANDO 1 INCOMING PARA: $recipientPhoneDigits")
        android.util.Log.d("ComprobanteActivity", "Remitente: $senderPhone, Monto: +$amount, TransferID: $transferId")
        
        val senderPhoneDigits = senderPhone.filter { it.isDigit() }
        
        // 🔥 CORRECCIÓN: Buscar nombre real del REMITENTE antes de guardar
        resolveSenderNameForIncoming(senderPhoneDigits) { senderName ->
            android.util.Log.d("ComprobanteActivity", "📇 Nombre del remitente resuelto: $senderName")
            
            // ✅ LIMPIAR TILDES AUTOMÁTICAMENTE del nombre del remitente
            val cleanedSenderName = com.ios.nequixofficialv2.utils.StringUtils.cleanName(senderName)
            
            // Crear movimiento INCOMING con nombre real del remitente SIN TILDES
            val incomingMovement = Movement(
                id = "", // ← VACÍO para que Firebase genere ID automático único
                name = cleanedSenderName, // ✅ Nombre sin tildes
                amount = amount,
                date = Date(),
                phone = senderPhoneDigits,
                type = MovementType.INCOMING,
                isIncoming = true,
                isQrPayment = false
            )
            
            android.util.Log.d("ComprobanteActivity", "📥 Creando INCOMING: name=$senderName, amount=$amount, phone=$senderPhoneDigits")
            
            // Guardar con nombre real
            e.saveMovementForUser(recipientPhoneDigits, incomingMovement) { success, error ->
                if (success) {
                    android.util.Log.d("ComprobanteActivity", "✅ INCOMING guardado exitosamente con nombre: $senderName")
                    
                    // Solo después de guardar, actualizar saldo UNA VEZ
                    updateRecipientBalance(recipientPhoneDigits, amount)
                    
                    // 🔔 La notificación se mostrará automáticamente en el dispositivo del RECEPTOR
                    // gracias a MovementListenerService (app cerrada) o HomeActivity listener (app abierta)
                    android.util.Log.d("ComprobanteActivity", "📲 Movimiento guardado - notificación será detectada por el receptor")
                    
                } else {
                    android.util.Log.e("ComprobanteActivity", "❌ Error guardando INCOMING: $error")
                }
            }
        }
    }
    
    /**
     * Busca el nombre real del REMITENTE para el movimiento INCOMING
     * Prioridad: 1) contactos del receptor, 2) Firebase users, 3) número formateado
     */
    private fun resolveSenderNameForIncoming(senderPhoneDigits: String, cb: (String) -> Unit) {
        if (senderPhoneDigits.isBlank()) {
            cb("")
            return
        }
        
        // Buscar directamente en Firebase users (nombre real del remitente)
        db.collection("users").document(senderPhoneDigits)
            .get()
            .addOnSuccessListener { userDoc ->
                val realName = userDoc.getString("name")?.trim().orEmpty()
                
                if (realName.isNotBlank() && 
                    !realName.equals("NEQUIXOFFICIAL", ignoreCase = true) &&
                    !realName.equals("USUARIO NEQUI", ignoreCase = true)) {
                    // ✅ Nombre real encontrado
                    android.util.Log.d("ComprobanteActivity", "✅ Nombre del remitente en Firebase: $realName")
                    cb(realName)
                } else {
                    // ⚠️ Nombre inválido, usar número formateado
                    val formattedPhone = if (senderPhoneDigits.length == 10) {
                        "+57 ${senderPhoneDigits.substring(0,3)} ${senderPhoneDigits.substring(3,6)} ${senderPhoneDigits.substring(6)}"
                    } else {
                        senderPhoneDigits
                    }
                    android.util.Log.d("ComprobanteActivity", "⚠️ Nombre del remitente inválido, usando: $formattedPhone")
                    cb(formattedPhone)
                }
            }
            .addOnFailureListener { e ->
                // ❌ Error, usar número formateado
                val formattedPhone = if (senderPhoneDigits.length == 10) {
                    "+57 ${senderPhoneDigits.substring(0,3)} ${senderPhoneDigits.substring(3,6)} ${senderPhoneDigits.substring(6)}"
                } else {
                    senderPhoneDigits
                }
                android.util.Log.e("ComprobanteActivity", "❌ Error buscando nombre del remitente: ${e.message}, usando: $formattedPhone")
                cb(formattedPhone)
            }
    }
    
    
    /**
     * Obtiene el nombre del receptor para la notificación del remitente
     */
    private fun getRecipientNameForNotification(recipientPhoneDigits: String, callback: (String) -> Unit) {
        db.collection("users").document(recipientPhoneDigits)
            .get()
            .addOnSuccessListener { userDoc ->
                val realName = userDoc.getString("name")?.trim().orEmpty()
                if (realName.isNotBlank() && 
                    !realName.equals("NEQUIXOFFICIAL", ignoreCase = true) && 
                    !realName.equals("USUARIO NEQUI", ignoreCase = true)) {
                    
                    callback(toTitleCase(realName))
                } else {
                    // Fallback a número formateado
                    val formattedPhone = "+57 ${recipientPhoneDigits.substring(0,3)} ${recipientPhoneDigits.substring(3,6)} ${recipientPhoneDigits.substring(6)}"
                    callback(formattedPhone)
                }
            }
            .addOnFailureListener {
                // Error, usar número formateado
                val formattedPhone = "+57 ${recipientPhoneDigits.substring(0,3)} ${recipientPhoneDigits.substring(3,6)} ${recipientPhoneDigits.substring(6)}"
                callback(formattedPhone)
            }
    }
    
    /**
     * Actualiza el saldo del receptor cuando recibe dinero
     */
    private fun updateRecipientBalance(recipientPhoneDigits: String, amount: Double) {
        android.util.Log.d("ComprobanteActivity", "💰 Actualizando saldo del receptor: $recipientPhoneDigits (+$amount)")
        
        val userRef = db.collection("users").document(recipientPhoneDigits)
        
        // Usar transacción para actualizar saldo de forma segura
        db.runTransaction { transaction ->
            val snapshot = transaction.get(userRef)
            val currentBalance = snapshot.getDouble("saldo") ?: 0.0
            val newBalance = currentBalance + amount
            
            android.util.Log.d("ComprobanteActivity", "💰 Saldo anterior: $currentBalance, nuevo saldo: $newBalance")
            
            transaction.update(userRef, "saldo", newBalance)
            newBalance
        }.addOnSuccessListener { newBalance ->
            android.util.Log.d("ComprobanteActivity", "✅ Saldo actualizado exitosamente: $recipientPhoneDigits ahora tiene $$newBalance")
        }.addOnFailureListener { error ->
            android.util.Log.e("ComprobanteActivity", "❌ Error actualizando saldo: ${error.message}")
        }
    }
    
    /**
     * Crea una notificación persistente en Firebase que se activará cuando el receptor abra la app
     */
    private fun createPersistentNotification(recipientPhoneDigits: String, senderName: String, amount: Double) {
        android.util.Log.d("ComprobanteActivity", "🔔 Creando notificación persistente para: $recipientPhoneDigits")
        
        val notificationData = mapOf(
            "type" to "money_received",
            "sender_name" to senderName,
            "amount" to amount,
            "message" to "$senderName te envió $$amount, ¡lo mejor!",
            "timestamp" to com.google.firebase.Timestamp.now(),
            "read" to false
        )
        
        // Guardar en Firebase para que el receptor la vea cuando abra la app
        db.collection("users")
            .document(recipientPhoneDigits)
            .collection("notifications")
            .add(notificationData)
            .addOnSuccessListener {
                android.util.Log.d("ComprobanteActivity", "✅ Notificación persistente creada exitosamente")
                
                // ENVIAR PUSH NOTIFICATION REAL usando FCM
                sendRealPushNotification(recipientPhoneDigits, senderName, amount)
            }
            .addOnFailureListener { error ->
                android.util.Log.e("ComprobanteActivity", "❌ Error creando notificación persistente: ${error.message}")
            }
    }
    
    /**
     * Envía push notification real usando FCM
     */
    private fun sendRealPushNotification(recipientPhoneDigits: String, senderName: String, amount: Double) {
        android.util.Log.d("ComprobanteActivity", "📱 Enviando PUSH NOTIFICATION REAL para: $recipientPhoneDigits")
        
        // Buscar el FCM token del receptor
        db.collection("users")
            .document(recipientPhoneDigits)
            .get()
            .addOnSuccessListener { document ->
                val fcmToken = document.getString("fcm_token")
                if (!fcmToken.isNullOrEmpty()) {
                    android.util.Log.d("ComprobanteActivity", "🎯 Token FCM encontrado: ${fcmToken.take(20)}...")
                    
                    // Usar el NotificationManager existente para enviar push notification
                    lifecycleScope.launch {
                        try {
                            appNotificationManager.sendDirectFCMNotification(
                                token = fcmToken,
                                title = "Envío",
                                message = "$senderName te envió $$amount, ¡lo mejor!",
                                data = mapOf(
                                    "type" to "money_received",
                                    "sender_name" to senderName,
                                    "amount" to amount.toString()
                                )
                            )
                            android.util.Log.d("ComprobanteActivity", "📲 PUSH NOTIFICATION REAL enviada exitosamente")
                        } catch (e: Exception) {
                            android.util.Log.e("ComprobanteActivity", "❌ Error enviando push notification real: ${e.message}")
                        }
                    }
                } else {
                    android.util.Log.w("ComprobanteActivity", "⚠️ Token FCM no encontrado para: $recipientPhoneDigits")
                }
            }
            .addOnFailureListener { error ->
                android.util.Log.e("ComprobanteActivity", "❌ Error buscando token FCM: ${error.message}")
            }
    }
    
}
