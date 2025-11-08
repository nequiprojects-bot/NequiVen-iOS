package com.ios.nequixofficialv2

import android.content.Intent
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.view.View
import android.view.Gravity
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.firebase.auth.FirebaseAuth
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import androidx.core.content.FileProvider
import android.widget.Toast
import com.ios.nequixofficialv2.utils.AndroidCompatibilityHelper

class VoucherQrActivity : AppCompatActivity() {
    companion object { private const val TAG = "QR_FLOW" }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var loadingContainer: FrameLayout? = null
    private var loadingStartMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Aplicar barra de estado morada para evitar destellos en Android 7-11
        AndroidCompatibilityHelper.applyNequiStatusBar(this)
        
        // Root vacío, usaremos overlay con la imagen generada
        setContentView(FrameLayout(this))

        val amountStr = intent.getStringExtra("amount").orEmpty()
        val maskedNameRaw = intent.getStringExtra("maskedName").orEmpty()
        // Ofuscar nombre: primeras 3 letras + *****
        val maskedName = AndroidCompatibilityHelper.obfuscateName(maskedNameRaw, uppercase = false)
        var userPhone = intent.getStringExtra("user_phone").orEmpty()
        val bankDestination = intent.getStringExtra("bank_destination")
        val qrKey = intent.getStringExtra("qr_key")
        Log.d(TAG, "onCreate amountStr='$amountStr' maskedName(original)='$maskedNameRaw' maskedName(ofuscado)='$maskedName' userPhone(raw)='$userPhone' bank='$bankDestination' key='$qrKey'")
        if (userPhone.isBlank()) {
            // Fallback: tomar del usuario autenticado
            val authPhone = FirebaseAuth.getInstance().currentUser?.phoneNumber
            userPhone = authPhone?.filter { it.isDigit() }?.let { if (it.length > 10) it.takeLast(10) else it }.orEmpty()
        }
        // Verificar saldo y descontar antes de mostrar comprobante
        verifyAndGenerate(maskedName, amountStr, userPhone, bankDestination, qrKey)
    }

    private fun composeVoucherOverlay(
        base: Bitmap,
        recipient: String,
        amount: String,
        phone: String,
        dateText: String,
        reference: String,
        templateTag: String,
        originLabel: String = "Disponible",
        qrKey: String? = null,
        bankDestination: String? = null
    ): Bitmap {
        val w = base.width
        val h = base.height
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        // Tipografías y colores (usando AssetObfuscator)
        val manrope = try {
            val fontStream = com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, "fuentes/Manrope-Medium.ttf")
            val tempFile = File.createTempFile("font_", ".ttf", cacheDir)
            tempFile.outputStream().use { fontStream.copyTo(it) }
            Typeface.createFromFile(tempFile)
        } catch (_: Exception) {
            try {
                val fontStream = com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, "fuentes/manrope_medium.ttf")
                val tempFile = File.createTempFile("font_", ".ttf", cacheDir)
                tempFile.outputStream().use { fontStream.copyTo(it) }
                Typeface.createFromFile(tempFile)
            } catch (_: Exception) { Typeface.SANS_SERIF }
        }
        val colorValueDefault = android.graphics.Color.parseColor("#222222")
        val colorValuePlantilla = android.graphics.Color.parseColor("#2e2b33")
        val colorLabel = android.graphics.Color.parseColor("#8A8A8E")

        fun tp(size: Float, color: Int, bold: Boolean = false): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = manrope
            isFakeBoldText = bold
            this.color = color
        }

        // Configuración por ratios (independiente de DPI/tamaño). Ajustado a tu referencia.
        data class FieldSpec(
            val xRel: Float,
            val yRelTop: Float,
            val sizeRatio: Float,
            val color: Int,
            val bold: Boolean = false,
            val widthRel: Float? = null,
            val isLabel: Boolean = false
        )

        // Elegir ratios o píxeles absolutos según la plantilla real usada
        val usingPlantillaQr = templateTag.contains("plantilla", ignoreCase = true) && templateTag.contains("qr", ignoreCase = true)
        val marginXRel = if (usingPlantillaQr) 0.090f else 0.085f
        val valueSizeRatio = 0.027f

        val specs = if (usingPlantillaQr) listOf(
            // Plantilla: plantillaqr.jpg – COORDENADAS ACTUALIZADAS con 22px
            "recipient"       to FieldSpec(45f, 530f, 22f, android.graphics.Color.parseColor("#200021"), false),  // Para
            "qr_key"          to FieldSpec(45f, 605f, 22f, android.graphics.Color.parseColor("#200021"), false),  // Llave
            "bank_destination" to FieldSpec(45f, 675f, 22f, android.graphics.Color.parseColor("#200021"), false),  // Banco Destino
            "date"            to FieldSpec(45f, 750f, 22f, android.graphics.Color.parseColor("#200021"), false, null),  // Fecha
            "amount"          to FieldSpec(45f, 830f, 22f, android.graphics.Color.parseColor("#200021"), false),  // Cuánto
            "reference"       to FieldSpec(45f, 900f, 22f, android.graphics.Color.parseColor("#200021"), false),  // Referencia
            "phone"           to FieldSpec(45f, 975f, 22f, android.graphics.Color.parseColor("#200021"), false),  // Desde (movido abajo)
            "origin"          to FieldSpec(45f, 1050f, 22f, android.graphics.Color.parseColor("#200021"), false)  // Disponible
        ) else listOf(
            // Plantilla: images/qr/qr_voucher.(jpg|png)
            "recipient" to FieldSpec(marginXRel, 0.605f, valueSizeRatio + 0.002f, colorValueDefault, true),
            "amount"    to FieldSpec(marginXRel, 0.672f, valueSizeRatio + 0.006f, colorValueDefault, true),
            "date"      to FieldSpec(marginXRel, 0.797f, valueSizeRatio - 0.004f, colorValueDefault, false, 0.82f),
            "reference" to FieldSpec(marginXRel, 0.868f, valueSizeRatio - 0.002f, colorValueDefault, false),
            "origin"    to FieldSpec(marginXRel, 0.939f, valueSizeRatio - 0.004f, colorValueDefault, false)
        )

        fun drawSingleLine(text: String, fs: FieldSpec) {
            if (usingPlantillaQr) {
                // Coordenadas provistas en píxeles del propio bitmap de plantilla; no escalar
                val paint = tp(fs.sizeRatio.coerceAtLeast(22f), fs.color, fs.bold)
                val x = fs.xRel
                val top = fs.yRelTop
                val baseline = top - paint.ascent()
                canvas.drawText(text, x, baseline, paint)
            } else {
                val paint = tp((h * fs.sizeRatio).coerceAtLeast(if (fs.isLabel) 14f else 18f), fs.color, fs.bold)
                val x = w * fs.xRel
                val top = h * fs.yRelTop
                val baseline = top - paint.ascent()
                canvas.drawText(text, x, baseline, paint)
            }
        }

        fun drawWrapped(text: String, fs: FieldSpec) {
            if (usingPlantillaQr) {
                val paint = tp(fs.sizeRatio.coerceAtLeast(22f), fs.color, fs.bold)
                val x = fs.xRel
                val top = fs.yRelTop
                val maxWidth = (w * 0.86f).toInt()
                val layout = if (android.os.Build.VERSION.SDK_INT >= 23) {
                    StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    StaticLayout(text, paint, maxWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false)
                }
                canvas.save()
                canvas.translate(x, top)
                layout.draw(canvas)
                canvas.restore()
            } else {
                val paint = tp((h * fs.sizeRatio).coerceAtLeast(16f), fs.color, fs.bold)
                val x = w * fs.xRel
                val top = h * fs.yRelTop
                val maxWidth = ((fs.widthRel ?: 0.80f) * w).toInt()
                val layout = if (android.os.Build.VERSION.SDK_INT >= 23) {
                    StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    StaticLayout(text, paint, maxWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false)
                }
                canvas.save()
                canvas.translate(x, top)
                layout.draw(canvas)
                canvas.restore()
            }
        }

        // Formatear teléfono con espacios
        val formattedPhone = if (phone.length == 10) {
            "${phone.substring(0, 3)} ${phone.substring(3, 6)} ${phone.substring(6)}"
        } else {
            phone
        }

        // Mapea y dibuja según specs
        val valuesByKey = mapOf(
            "recipient" to recipient,
            "amount" to amount,
            "date" to dateText,
            "reference" to reference,
            "origin" to originLabel,
            "phone" to formattedPhone,
            "qr_key" to (qrKey ?: ""),
            "bank_destination" to (bankDestination ?: "")
        )

        specs.forEach { (key, fs) ->
            val text = valuesByKey[key]
            // Solo dibujar si hay texto
            if (text.isNullOrBlank()) return@forEach
            
            if (fs.widthRel != null && (key == "date")) {
                drawWrapped(text, fs)
            } else {
                drawSingleLine(text, fs)
            }
        }

        return out
    }

    private fun generateReference(): String {
        // M + 8 dígitos
        val n = (10000000..99999999).random()
        return "M$n"
    }

    private fun formatDateEs(date: java.util.Date = java.util.Date()): String {
        val locale = java.util.Locale("es", "CO")
        val sdf = java.text.SimpleDateFormat("d 'de' MMMM 'de' yyyy 'a las' hh:mm a", locale)
        val raw = sdf.format(date)
        return raw.replace("AM", "a.m.").replace("PM", "p.m.")
    }


    private fun applyQrVoucherCoordinates(base: Bitmap, values: Map<String, String>): Bitmap? {
        // Lee coordenadas normalizadas de assets/cordenadas/pociciones_textos_qr_vouch.json (usando AssetObfuscator)
        val json = try {
            com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, "cordenadas/pociciones_textos_qr_vouch.json").use { inp ->
                inp.bufferedReader().use { it.readText() }
            }
        } catch (_: Exception) { null } ?: return null

        return try {
            val obj = JSONObject(json)
            val w = base.width.toFloat()
            val h = base.height.toFloat()
            val bmp = base.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(bmp)

            fun getPos(key: String): Pair<Float, Float>? {
                if (!obj.has(key)) return null
                val o = obj.getJSONObject(key)
                val x = (o.optDouble("x", -1.0)).toFloat()
                val y = (o.optDouble("y", -1.0)).toFloat()
                if (x < 0 || y < 0) return null
                return Pair(x * w, y * h)
            }

            // Config de texto base (escalado según alto del comprobante) - usando AssetObfuscator
            val baseSize = (h * 0.022f).coerceAtLeast(16f)
            val manrope = try {
                val fontStream = com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this@VoucherQrActivity, "fuentes/manrope_medium.ttf")
                val tempFile = File.createTempFile("font_qr_", ".ttf", cacheDir)
                tempFile.outputStream().use { fontStream.copyTo(it) }
                Typeface.createFromFile(tempFile)
            } catch (_: Exception) { Typeface.SANS_SERIF }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#222222")
                textSize = baseSize
                typeface = manrope
                alpha = 255
                setShadowLayer(0.5f, 0f, 0f, 0x33000000)
            }

            fun drawKey(key: String, text: String?, defaultFactor: Float = 1.0f, bold: Boolean = false) {
                val pos = getPos(key) ?: return
                val t = text?.takeIf { it.isNotBlank() } ?: return
                val cfg = obj.optJSONObject(key)
                Log.d(TAG, "drawKey '$key' at pos=${pos.first/w},${pos.second/h} text='${t.take(12)}...'")

                // Tamaño: soporta sizeRatio (relativo a alto) o sizePx
                val sizeRatio = cfg?.optDouble("sizeRatio", Double.NaN)?.toFloat()
                val sizePx = cfg?.optDouble("sizePx", Double.NaN)?.toFloat()
                paint.textSize = when {
                    sizePx != null && !sizePx.isNaN() -> sizePx
                    sizeRatio != null && !sizeRatio.isNaN() -> (h * sizeRatio)
                    else -> baseSize * defaultFactor
                }

                // Color opcional
                cfg?.optString("color")?.takeIf { it.startsWith("#") }?.let {
                    try { paint.color = android.graphics.Color.parseColor(it) } catch (_: Exception) { }
                }

                // Peso y letterSpacing opcionales
                paint.typeface = manrope
                paint.isFakeBoldText = bold || cfg?.optBoolean("bold", false) == true
                paint.letterSpacing = cfg?.optDouble("ls", Double.NaN)?.toFloat() ?: paint.letterSpacing

                // Alineación
                val align = (cfg?.optString("align") ?: "left").lowercase()
                paint.textAlign = when (align) {
                    "center" -> Paint.Align.CENTER
                    "right" -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }

                // Ancho para wrapping (width normalizado)
                val widthNorm = cfg?.optDouble("width", Double.NaN)?.toFloat()
                val maxWidth = if (widthNorm != null && !widthNorm.isNaN()) (widthNorm * w).toInt() else 0

                // Modo de Y: por defecto interpretamos JSON.y como TOP, no baseline
                val yMode = (cfg?.optString("yMode") ?: "top").lowercase()

                if (maxWidth > 0) {
                    // Multilínea con StaticLayout
                    val x = when (paint.textAlign) {
                        Paint.Align.CENTER -> pos.first
                        Paint.Align.RIGHT -> pos.first
                        else -> pos.first
                    }
                    val layout = if (android.os.Build.VERSION.SDK_INT >= 23) {
                        StaticLayout.Builder.obtain(t, 0, t.length, paint, maxWidth)
                            .setAlignment(
                                when (paint.textAlign) {
                                    Paint.Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                                    Paint.Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                                    else -> Layout.Alignment.ALIGN_NORMAL
                                }
                            )
                            .setLineSpacing(0f, 1.0f)
                            .build()
                    } else {
                        @Suppress("DEPRECATION")
                        StaticLayout(t, paint, maxWidth, when (paint.textAlign) {
                            Paint.Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                            Paint.Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                            else -> Layout.Alignment.ALIGN_NORMAL
                        }, 1.0f, 0f, false)
                    }
                    canvas.save()
                    // Para RIGHT, movemos origen para que el borde derecho coincida; para CENTER centramos
                    val translateX = when (paint.textAlign) {
                        Paint.Align.CENTER -> x - maxWidth / 2f
                        Paint.Align.RIGHT -> x - maxWidth
                        else -> x
                    }
                    // Alinear vertical según yMode
                    val firstBaseline = layout.getLineBaseline(0).toFloat()
                    val translateY = if (yMode == "baseline") {
                        pos.second - firstBaseline
                    } else {
                        // y = top → ubicamos la parte superior del bloque en pos.second
                        pos.second
                    }
                    canvas.translate(translateX, translateY)
                    layout.draw(canvas)
                    canvas.restore()
                } else {
                    // Línea única
                    val yDraw = if (yMode == "baseline") {
                        pos.second
                    } else {
                        // y = top → convertir a baseline: top - ascent
                        pos.second - paint.ascent()
                    }
                    canvas.drawText(t, pos.first, yDraw, paint)
                }
            }

            // Pintar campos principales del QR voucher
            drawKey("recipient", values["recipient"], defaultFactor = 1.0f, bold = true)
            drawKey("amount", values["amount"], defaultFactor = 1.12f, bold = true)
            drawKey("phone", values["phone"], defaultFactor = 0.96f)
            drawKey("date", values["date"], defaultFactor = 0.92f)

            // "mvalue" y "disponible" no se pintan si no hay valor definido; se podrían mapear luego si hace falta

            bmp
        } catch (e: Exception) {
            Log.d(TAG, "applyQrVoucherCoordinates error: ${e.message}")
            // Nunca retornar null para que al menos se muestre la plantilla
            base
        }
    }

    private fun verifyAndGenerate(name: String, amountStr: String, userPhone: String, bankDestination: String?, qrKey: String?) {
        val required = parseAmountToLong(amountStr) ?: 0L
        Log.d(TAG, "verifyAndGenerate required=$required userPhone='$userPhone' bank='$bankDestination' key='$qrKey'")
        if (required <= 0L) {
            Log.d(TAG, "required <= 0, mostrando voucher sin débito de saldo")
            showStaticVoucher(name, amountStr, bankDestination, qrKey, userPhone)
            return
        }
        // UX: si no hay user_phone, no se puede verificar saldo → mostrar insuficiente QR
        if (userPhone.isBlank()) {
            Log.d(TAG, "userPhone blank -> insuficiente QR")
            goSaldoInsuficiente("")
            return
        }
        val normalizedDocId = userPhone.filter { it.isDigit() }.let { if (it.length > 10) it.takeLast(10) else it }
        Log.d(TAG, "docId(normalized)='$normalizedDocId'")
        val userRef = db.collection("users").document(normalizedDocId)

        // Paso 1: Leer saldo como en Nequi
        userRef.get().addOnSuccessListener { snap ->
            val current = readBalanceFlexible(snap, "saldo")
            Log.d(TAG, "read saldo(current)=$current required=$required")
            if (current == null) {
                goSaldoInsuficiente(userPhone)
                return@addOnSuccessListener
            }
            if (current < required) {
                Log.d(TAG, "saldo < required -> insuficiente QR")
                goSaldoInsuficiente(userPhone)
                return@addOnSuccessListener
            }

            // Paso 2: Transacción para descontar
            db.runTransaction { transaction ->
                val s = transaction.get(userRef)
                val cur = readBalanceFlexible(s, "saldo") ?: 0L
                Log.d(TAG, "tx saldo(cur)=$cur required=$required")
                if (cur < required) {
                    throw com.google.firebase.firestore.FirebaseFirestoreException(
                        "Saldo insuficiente",
                        com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED
                    )
                }
                transaction.update(userRef, "saldo", cur - required)
            }.addOnSuccessListener {
                Log.d(TAG, "tx success -> mostrar comprobante QR (XML)")
                showStaticVoucher(name, amountStr, bankDestination, qrKey, userPhone)
            }.addOnFailureListener {
                Log.d(TAG, "tx failure -> insuficiente QR")
                goSaldoInsuficiente(userPhone)
            }
        }.addOnFailureListener { e ->
            Log.d(TAG, "read saldo failure: ${e.message}")
            // Error leyendo saldo → tratar como no verificable: insuficiente QR
            goSaldoInsuficiente(userPhone)
        }
    }

    private fun generateVoucherQr(name: String, amountStr: String) {
        // Método legado ya no usado: mantenido por compatibilidad si hay referencias.
        // Redirigir a la versión estática por XML.
        showStaticVoucher(name, amountStr, null, null, "")
    }

    private fun showStaticVoucher(name: String, amountStr: String, bankDestination: String?, qrKey: String?, userPhone: String) {
        // Usar layout de alta calidad
        hideLoadingOverlay()
        setContentView(R.layout.activity_voucher_qr_hq)

        val iv = findViewById<ImageView>(R.id.ivVoucherQrHQ)
        val loading = findViewById<ImageView>(R.id.loadingCircleVoucherQr)
        val btnListo = findViewById<View>(R.id.btnListoInvisible)
        val btnShare = findViewById<View>(R.id.btnCompartirInvisible)

        // Elegir plantilla robusta: preferir plantilla específica y luego los fallback
        val voucherCandidates = arrayOf(
            "plantillaqr.jpg",  // Nueva plantilla QR con llave guardada
            "img/plantilla_qr.jpg",
            "images/qr/qr_voucher.jpg",
            "images/qr/qr_voucher.png"
        )
        
        // Decodificar con opciones de alta calidad
        val (voucherPath, bmp) = decodeFirstExistingAssetHQ(voucherCandidates)
            ?: (null to Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFF8F6FC.toInt()) })

        bmp?.let {
            // Preparar textos a pintar según coordenadas del set QR
            val drawPhone = userPhone.ifBlank {
                FirebaseAuth.getInstance().currentUser?.phoneNumber?.filter { it.isDigit() }?.let { if (it.length > 10) it.takeLast(10) else it }.orEmpty()
            }
            val drawDate = formatDateEs(java.util.Date())
            val amountFormatted = try {
                val raw = amountStr.replace("$", "").replace(" ", "").replace(".", "").replace(",", ".")
                val value = raw.toDoubleOrNull() ?: 0.0
                val nf = java.text.NumberFormat.getCurrencyInstance(java.util.Locale("es", "CO")).apply {
                    maximumFractionDigits = 0
                    minimumFractionDigits = 0
                }
                nf.format(value)
            } catch (_: Exception) { amountStr }

            Log.d(TAG, "Dibujando comprobante con: qrKey='$qrKey' bank='$bankDestination' phone='$drawPhone'")

            // RECORTAR la imagen para eliminar la parte inferior duplicada
            // Cortamos después de "Disponible" (Y=1100) para quitar "¿Algún problema..."
            val croppedBase = try {
                val cutHeight = 1100 // Píxeles - cortar después del último campo visible
                if (it.height > cutHeight) {
                    Log.d(TAG, "🔪 Cortando plantilla de ${it.height}px a ${cutHeight}px para eliminar duplicación")
                    Bitmap.createBitmap(it, 0, 0, it.width, cutHeight)
                } else {
                    Log.d(TAG, "Plantilla ya es pequeña (${it.height}px), no se corta")
                    it
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error cortando bitmap: ${e.message}")
                it
            }
            
            val composed = composeVoucherOverlay(
                base = croppedBase,
                recipient = toTitleCase(name),
                amount = amountFormatted,
                phone = drawPhone,
                dateText = drawDate,
                reference = generateReference(),
                templateTag = voucherPath ?: "",
                qrKey = qrKey,
                bankDestination = bankDestination
            )
            // Mostrar la plantilla y ocultar el círculo de carga
            iv.setImageBitmap(composed ?: croppedBase)
            iv.visibility = View.VISIBLE
            loading?.visibility = View.GONE
            
            // Cargar segunda parte del comprobante (qr_2parte.png)
            val ivSecondPart = findViewById<ImageView>(R.id.ivVoucherQrSecondPart)
            try {
                com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, "qr_2parte.png").use { inp ->
                    val secondPartBmp = android.graphics.BitmapFactory.decodeStream(inp)
                    if (secondPartBmp != null) {
                        ivSecondPart?.setImageBitmap(secondPartBmp)
                        ivSecondPart?.visibility = View.VISIBLE
                        Log.d(TAG, "✅ Segunda parte del comprobante cargada (pegada sin duplicación)")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ No se encontró qr_2parte.png: ${e.message}")
                // No mostrar segunda parte si no existe el asset
            }
            btnShare?.apply {
                visibility = View.VISIBLE
                bringToFront()
                alpha = 0f
                setOnClickListener {
                    val drawable = iv.drawable
                    val shareBmp = if (drawable is android.graphics.drawable.BitmapDrawable) {
                        drawable.bitmap
                    } else {
                        captureViewBitmap(iv)
                    }
                    if (shareBmp != null) {
                        val file = saveBitmapToCachePng(shareBmp)
                        if (file != null) {
                            shareImage(file)
                        } else {
                            com.ios.nequixofficialv2.utils.NequiAlert.showError(this@VoucherQrActivity, "Error al guardar la imagen")
                        }
                    } else {
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this@VoucherQrActivity, "Error al preparar la imagen")
                    }
                }
            }
        }
        // Acción del botón Listo: volver a Home
        btnListo?.setOnClickListener { goHome() }
        // Guardar movimiento para que Movimientos pueda abrir el detalle QR con plantilla disponible
        try {
            val clean = amountStr.replace("$", "").replace(" ", "").replace(".", "").replace(",", ".")
            val amount = clean.toDoubleOrNull() ?: 0.0
            // Guardar el comprobante mostrado para abrirlo luego desde Movimientos
            val shown = (iv.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                ?: captureViewBitmap(iv)
            val savedFile = shown?.let { saveBitmapToCachePng(it) }
            val detailPath = savedFile?.absolutePath
            val movement = io.scanbot.demo.barcodescanner.model.Movement(
                id = "",
                name = toTitleCase(name),
                amount = amount,
                date = java.util.Date(),
                phone = "",
                type = io.scanbot.demo.barcodescanner.model.MovementType.QR_VOUCH,
                isIncoming = false,
                isQrPayment = true,
                qrDetailImagePath = detailPath
            )
            io.scanbot.demo.barcodescanner.e.saveMovement(this, movement) { _, _ -> }
        } catch (_: Exception) { }
    }

    private fun showLoadingOverlay() {
        val root = findViewById<FrameLayout>(android.R.id.content)
        loadingContainer = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val spinner = ImageView(this).apply {
            setImageResource(R.drawable.loading_circle_comprobante)
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        loadingContainer?.addView(spinner)
        root.addView(loadingContainer)
        loadingStartMs = System.currentTimeMillis()
        val d = spinner.drawable
        when (d) {
            is AnimationDrawable -> { d.isOneShot = false; d.start() }
            is AnimatedVectorDrawable -> d.start()
            is AnimatedVectorDrawableCompat -> d.start()
        }
    }

    private fun hideLoadingOverlay() {
        val root = findViewById<FrameLayout>(android.R.id.content)
        loadingContainer?.let { root.removeView(it) }
        loadingContainer = null
    }

    private fun decodeFirstExistingAsset(paths: Array<String>): Pair<String, Bitmap?>? {
        for (p in paths) {
            try {
                com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, p).use { inp ->
                    val b = android.graphics.BitmapFactory.decodeStream(inp)
                    if (b != null) return p to b
                }
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    private fun decodeFirstExistingAssetHQ(paths: Array<String>): Pair<String, Bitmap?>? {
        for (p in paths) {
            try {
                com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, p).use { inp ->
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888  // Máxima calidad de color
                        inScaled = false  // No escalar automáticamente
                        inDither = false  // Sin dithering para mejor nitidez
                        inPreferQualityOverSpeed = true  // Priorizar calidad sobre velocidad
                        inMutable = true  // Permitir modificaciones posteriores
                        inSampleSize = 1  // Sin reducción de muestra
                    }
                    val b = android.graphics.BitmapFactory.decodeStream(inp, null, options)
                    if (b != null) return p to b
                }
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    private fun showOverlayImage(file: File) {
        val root = findViewById<FrameLayout>(android.R.id.content)
        val overlay = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Ajuste automático manteniendo aspecto, sin reescalar manual para mejor nitidez
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        if (bmp != null) overlay.setImageBitmap(bmp)
        root.addView(overlay)

        // Botón invisible "Listo": área transparente encima de la plantilla en la parte inferior
        val hit = View(this)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (64 * resources.displayMetrics.density).toInt()
        )
        params.gravity = android.view.Gravity.BOTTOM
        params.bottomMargin = (16 * resources.displayMetrics.density).toInt()
        hit.layoutParams = params
        hit.setBackgroundColor(0x00000000)
        hit.isClickable = true
        hit.isFocusable = true
        hit.setOnClickListener { goHome() }
        root.addView(hit)
        hit.bringToFront()
    }

    private fun copyAssetFileOverwrite(name: String, outFile: File) {
        try {
            // Sobrescribir siempre la plantilla para reflejar cambios (usando AssetObfuscator)
            com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, name).use { input ->
                FileOutputStream(outFile, false).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copiando asset '$name': ${e.message}")
        }
    }

    private fun showOverlayImageFallback() {
        // Fallback muy defensivo: pantalla en blancos con toque para volver
        val root = findViewById<FrameLayout>(android.R.id.content)
        val v = View(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { goHome() }
        }
        root.addView(v)
    }

    private fun captureViewBitmap(view: View?): Bitmap? {
        if (view == null) return null
        return try {
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0) return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            view.draw(c)
            bmp
        } catch (_: Exception) { null }
    }

    private fun saveBitmapToCachePng(bmp: Bitmap): File? {
        return try {
            val dir = File(cacheDir, "shared_images")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "qr_voucher_" + System.currentTimeMillis() + ".png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            file
        } catch (_: Exception) { null }
    }

    private fun shareImage(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                this.packageName + ".provider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Comprobante QR")
                putExtra(Intent.EXTRA_TEXT, "Detalle del comprobante de pago QR")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartir comprobante"))
        } catch (_: Exception) {
            com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error al compartir la imagen")
        }
    }

    private fun clearDir(dir: File) {
        try {
            if (dir.exists()) dir.deleteRecursively()
        } catch (_: Exception) {}
    }

    private fun copyAssetFileToCache(name: String, outFile: File) {
        if (outFile.exists()) return
        try {
            // Usar AssetObfuscator para obtener el archivo ofuscado
            com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, name).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            android.util.Log.e("VoucherQrActivity", "Error copiando asset: $name", e)
            throw e
        }
    }

    private fun copyAssetDirToCache(dirName: String, outDir: File) {
        if (!outDir.exists()) outDir.mkdirs()
        
        // Lista de archivos conocidos por directorio (ya que los assets están ofuscados)
        val knownFiles = when (dirName) {
            "fuentes" -> listOf(
                "Manrope-Bold.ttf",
                "Manrope-ExtraBold.ttf",
                "Manrope-ExtraLight.ttf",
                "Manrope-Light.ttf",
                "Manrope-Medium.ttf",
                "Manrope-Regular.ttf",
                "Manrope-SemiBold.ttf"
            )
            "img" -> listOf(
                "comprobante.png",
                "plantilla1.jpg",
                "plantilla2.jpg",
                "plantilla4.jpg",
                "plantilla_qr.jpg",
                "plantillaqr.jpg",
                "spinner.png"
            )
            else -> emptyList()
        }
        
        for (fileName in knownFiles) {
            val originalPath = "$dirName/$fileName"
            val outFile = File(outDir, fileName)
            try {
                com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, originalPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VoucherQrActivity", "Error copiando: $originalPath", e)
            }
        }
    }

    private fun copyAssetDirOverwrite(dirName: String, outDir: File) {
        if (!outDir.exists()) outDir.mkdirs()
        
        // Lista de archivos conocidos por directorio (ya que los assets están ofuscados)
        val knownFiles = when (dirName) {
            "fuentes" -> listOf(
                "Manrope-Bold.ttf",
                "Manrope-ExtraBold.ttf",
                "Manrope-ExtraLight.ttf",
                "Manrope-Light.ttf",
                "Manrope-Medium.ttf",
                "Manrope-Regular.ttf",
                "Manrope-SemiBold.ttf"
            )
            "img" -> listOf(
                "comprobante.png",
                "plantilla1.jpg",
                "plantilla2.jpg",
                "plantilla4.jpg",
                "plantilla_qr.jpg",
                "plantillaqr.jpg",
                "spinner.png"
            )
            else -> emptyList()
        }
        
        for (fileName in knownFiles) {
            val originalPath = "$dirName/$fileName"
            val outFile = File(outDir, fileName)
            try {
                com.ios.nequixofficialv2.security.AssetObfuscator.openAsset(this, originalPath).use { input ->
                    // Sobrescribir siempre
                    FileOutputStream(outFile, false).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VoucherQrActivity", "Error copiando (overwrite): $originalPath", e)
            }
        }
    }

    private fun parseAmountToLong(amount: String?): Long? {
        if (amount.isNullOrBlank()) return null
        val digits = amount.filter { it.isDigit() }
        return digits.toLongOrNull()
    }

    private fun readBalanceFlexible(snap: com.google.firebase.firestore.DocumentSnapshot, field: String): Long? {
        val anyVal = snap.get(field) ?: return null
        return when (anyVal) {
            is Number -> anyVal.toLong()
            is String -> anyVal.filter { it.isDigit() }.toLongOrNull()
            else -> null
        }
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

    private fun goHome() {
        val i = Intent(this, HomeActivity::class.java)
        startActivity(i)
        finish()
    }

    private fun goSaldoInsuficiente(userPhone: String) {
        val i = Intent(this, SaldoInsuficienteQrActivity::class.java)
        if (userPhone.isNotBlank()) i.putExtra("user_phone", userPhone)
        startActivity(i)
        finish()
    }
}
