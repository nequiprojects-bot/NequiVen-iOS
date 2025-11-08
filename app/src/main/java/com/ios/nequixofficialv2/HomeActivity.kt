package com.ios.nequixofficialv2
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import androidx.core.view.isVisible
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import com.google.android.material.textfield.TextInputEditText
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.ios.nequixofficialv2.databinding.Kihom1Binding
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.scanbot.demo.barcodescanner.e
import io.scanbot.demo.barcodescanner.model.Movement
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import com.ios.nequixofficialv2.update.UpdateManager
import com.ios.nequixofficialv2.update.UpdateDialog
import android.os.Build
import android.app.PendingIntent
import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.ios.nequixofficialv2.utils.AndroidCompatibilityHelper
import com.ios.nequixofficialv2.utils.NotificationManager as AppNotificationManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ios.nequixofficialv2.workers.NotificationWorker
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: Kihom1Binding
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var userPhone: String = ""
    private var isHomeShimmerActive: Boolean = false
    private var isSaldoShimmerActive: Boolean = false
    private var isBalanceHidden: Boolean = false
    private var lastDisponibleValue: Long = 0L
    private var lastTotalValue: Long = 0L
    // Sin flags extra: estado simple y estable
    private var refreshingHeader: Boolean = false
    
    // Constantes para permisos
    private val NOTIFICATION_REQUEST_CODE = 1001
    private enum class MovTab { HOY, MAS }
    private var currentMovTab: MovTab = MovTab.HOY
    private var saldoListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var movementsAdapter: io.scanbot.demo.barcodescanner.adapter.SectionedMovementAdapter? = null
    private var loadedMovements: List<Movement> = emptyList()
    private val updateManager: UpdateManager by lazy { UpdateManager(this) }
    private var updateDialog: UpdateDialog? = null
    private val appNotificationManager: AppNotificationManager by lazy { AppNotificationManager(this) }
    
    // Variables para prevenir clicks rápidos y bugs de navegación
    private var isNavigating = false
    private var lastClickTime = 0L
    private val CLICK_DEBOUNCE_TIME = 600L // 600ms entre clicks (aumentado para evitar superposición)
    private var currentSection: BottomSection = BottomSection.HOME
    private val pendingRunnables = mutableListOf<Runnable>()
    
    // Control anti-duplicados de notificaciones (sincronizado con MovementListenerService)
    private var incomingMovementsListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Usa ViewBinding correctamente: infla y asigna una sola vez
        binding = Kihom1Binding.inflate(layoutInflater)
        // Parche defensivo: si por alguna razón ya tiene padre, remover antes de setear
        (binding.root.parent as? ViewGroup)?.removeView(binding.root)
        setContentView(binding.root)

        // Aplicar color morado original a la barra de estado (statusBar)
        try {
            window.statusBarColor = ContextCompat.getColor(this, R.color.color_200020)
            // NO tocar navigationBarColor - respetar configuración del usuario
        } catch (_: Exception) {}

        // ✅ Aplicar SOLO padding bottom para respetar barra de navegación inferior
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, navBars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }

        userPhone = intent.getStringExtra("user_phone") ?: ""
        if (userPhone.isEmpty()) {
            // Si no hay usuario, cerramos para evitar estados inconsistentes
            finish()
            return
        }
        // Persistir teléfono para gestores que leen de SharedPreferences
        getSharedPreferences("home_prefs", MODE_PRIVATE)
            .edit()
            .putString("user_phone", userPhone)
            .apply()

        // Solicitar permisos de notificación para Android 13+
        requestNotificationPermissions()

        // Interacciones de UI (bottom bar, cash menu, ojo de saldo, etc.)
        setupInteractions()
        
        // Actualizar badge de notificaciones
        updateNotificationBadge()
        
        // Registrar token FCM para notificaciones push
        appNotificationManager.registerFCMToken(userPhone)
        
        // 🚀 INICIAR SERVICIO DE ESCUCHA DE TRANSFERENCIAS EN SEGUNDO PLANO
        // Este servicio funciona independiente de HomeActivity (app cerrada o abierta)
        com.ios.nequixofficialv2.services.MovementListenerService.start(this, userPhone)
        
        // 🚀 DESACTIVADO: Worker ya no necesario
        // triggerNotificationWorkerOnStart()
        
        // ✅ DESACTIVADO: MovementListenerService maneja TODAS las notificaciones
        // HomeActivity solo actualiza UI, no muestra notificaciones para evitar duplicados
        // startListeningForIncomingMovements()
        
        // ELIMINAR COMPLETAMENTE el SwipeRefreshLayout - no lo usamos
        binding.homeSwipe.isEnabled = false
        binding.homeSwipe.setProgressBackgroundColorSchemeColor(Color.TRANSPARENT)
        binding.homeSwipe.setColorSchemeColors(Color.TRANSPARENT)
        
        // Configurar el spinner personalizado como BLANCO desde el inicio
        val white = 0xFFFFFFFF.toInt()
        binding.pullProgressBar.apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(white)
            indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
            indeterminateDrawable?.apply {
                setTint(white)
                setTintMode(android.graphics.PorterDuff.Mode.SRC_IN)
                setTintList(android.content.res.ColorStateList.valueOf(white))
            }
        }

        // Pull-to-refresh MANUAL - SOLO desde la parte superior
        var startY = 0f
        var startScrollY = 0
        var pulling = false
        var triggered = false
        
        binding.mainScrollView.setOnTouchListener { _, event ->
            // ✅ SI ESTÁ REFRESCANDO: BLOQUEAR TODO (consumir eventos)
            if (refreshingHeader) {
                return@setOnTouchListener true // Consumir evento = no se mueve NADA
            }
            
            // Comportamiento normal de pull-to-refresh
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    startScrollY = binding.mainScrollView.scrollY
                    // SOLO permitir pull si estamos EXACTAMENTE en la parte superior
                    pulling = startScrollY == 0 && !refreshingHeader && currentSection == BottomSection.HOME
                    triggered = false
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // Verificar que seguimos en la parte superior y no hay refresh activo
                    val currentScrollY = binding.mainScrollView.scrollY
                    val deltaY = event.y - startY
                    
                    // Solo activar si:
                    // 1. Estamos en modo pulling
                    // 2. No se ha disparado aún
                    // 3. El scroll sigue en 0 (parte superior)
                    // 4. El movimiento es hacia ABAJO (deltaY positivo)
                    // 5. No hay refresh activo
                    if (pulling && !triggered && currentScrollY == 0 && deltaY > 0 && !refreshingHeader) {
                        if (deltaY > dpToPx(100)) {
                            // Trigger refresh UNA SOLA VEZ
                            pulling = false
                            triggered = true
                            triggerRefresh()
                        }
                    } else if (currentScrollY > 0) {
                        // Si el usuario empieza a scrollear hacia abajo, cancelar pulling
                        pulling = false
                    }
                    false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    pulling = false
                    triggered = false
                    false
                }
                else -> false
            }
        }

        // Shimmer inicial para Home (saludo, tarjetas y sugeridos) por 5s
        // Nota: sin barra inferior -> iconos de navegación aparecen de inmediato
        startHomeShimmer(includeBottom = false, saldoDurationMs = 2500)
        binding.root.postDelayed({ stopHomeShimmerReveal(includeBottom = false) }, 5_000)

        // Selección inicial: Inicio marcado
        selectBottom(BottomSection.HOME)
        // Asegura que la sección Movimientos esté oculta al iniciar
        hideMovements()

        // Verificar actualizaciones
        checkForUpdates()
        
        // Verificar si el usuario tiene nombre configurado
        checkUserNameRequired()
    }



    override fun onResume() {
        super.onResume()
        
        // Actualizar el badge de notificaciones cada vez que se regresa a la actividad
        updateNotificationBadge()
        
        // Recargar foto de perfil cuando vuelva (por si la cambió en ProfileActivity)
        loadUserProfilePhoto()
        
        // Refresca encabezado (nombre) por si el usuario lo cambió en ProfileActivity
        lifecycleScope.launch {
            val userKey = userPhone.filter { it.isDigit() }
            val doc = try { db.collection("users").document(userKey).get().await() } catch (_: Exception) { null }
            val name = doc?.getString("name")
            showStaticHomeUI(name)
            val saldoLong = doc?.let { readLongFlexible(it, "saldo") } ?: 0L
            val colchonLong = doc?.let { readLongFlexible(it, "colchon") } ?: 0L
            val extraLong = doc?.let { readLongFlexible(it, "saldo_extra") } ?: 0L
            val disponible = if (extraLong > 0L) extraLong else saldoLong
            val total = (disponible + colchonLong).coerceAtLeast(0L)
            renderSaldo(disponible, total)
        }
        // Si la sección de Movimientos está visible, refrescar la lista (por ejemplo al volver de comprobantes/QR)
        if (binding.tvMovimientosTitle.isShown) {
            loadMovements()
        }
    }

    override fun onStart() {
        super.onStart()
        val userKey = userPhone.filter { it.isDigit() }
        if (userKey.isBlank()) return
        saldoListener?.remove()
        saldoListener = db.collection("users").document(userKey)
            .addSnapshotListener { doc, _ ->
                if (doc == null || !doc.exists()) return@addSnapshotListener
                val saldoLong = readLongFlexible(doc, "saldo")
                val colchonLong = readLongFlexible(doc, "colchon")
                val extraLong = readLongFlexible(doc, "saldo_extra")
                val disponible = if (extraLong > 0L) extraLong else saldoLong
                val total = (disponible + colchonLong).coerceAtLeast(0L)
                renderSaldo(disponible, total)
            }
    }

    override fun onStop() {
        super.onStop()
        saldoListener?.remove()
        saldoListener = null
    }

    private fun showMovements() {
        // ✅ SÓLIDO: Limpiar estado anterior PRIMERO de forma síncrona
        hideAllSections()
        
        // ✅ SÓLIDO: Asegurar que el refresh header esté colapsado
        expandRefreshHeader(false)
        
        AndroidCompatibilityHelper.safeExecute(
            action = {
                
                // ✅ SÓLIDO: Revelar controles de Movimientos con fade in
                binding.tvMovimientosTitle.isVisible = true
                binding.tvMovimientosTitle.alpha = 0f
                binding.tvMovimientosTitle.animate().alpha(1f).setDuration(200).start()
                // Ocultar búsqueda para que coincida con la referencia visual
                binding.etSearchMovements.isVisible = false
                binding.dividerSearch.isVisible = false
                binding.movimientosButtonsContainer.isVisible = true
                // Ocultar etiqueta "Hoy" bajo los botones para evitar texto duplicado
                binding.tvHoyLabel.isVisible = false

                // Asegurar que la sección de Movimientos quede por encima
                binding.tvMovimientosTitle.bringToFront()
                binding.movimientosButtonsContainer.bringToFront()
                binding.skeletonLayout.root.bringToFront()
                binding.recyclerViewMovements.bringToFront()
                binding.ivMovimientosEmpty.bringToFront()
                binding.tvNoMovimientos.bringToFront()
                binding.root.invalidate()

                // Estilo inicial: pestaña HOY activa
                updateMovementsTab(MovTab.HOY)

                // Mostrar skeleton inicial y ocultar lista/vacío
                binding.skeletonLayout.root.isVisible = true
                binding.recyclerViewMovements.isVisible = false
                binding.ivMovimientosEmpty.isVisible = false
                binding.tvNoMovimientos.isVisible = false

                // Configurar RecyclerView si aún no está
                if (movementsAdapter == null) {
                    binding.recyclerViewMovements.layoutManager = LinearLayoutManager(this)
                    movementsAdapter = io.scanbot.demo.barcodescanner.adapter.SectionedMovementAdapter()
                    binding.recyclerViewMovements.adapter = movementsAdapter
                }

                // Cargar datos de Firestore
                loadMovements()

                // Simular carga breve y mostrar estado vacío si no hay datos cargados
                val skeletonRunnable = Runnable {
                    if (currentSection == BottomSection.MOVEMENTS) { // Solo si aún estamos en movimientos
                        binding.skeletonLayout.root.isVisible = false
                        if (!binding.recyclerViewMovements.isVisible) {
                            binding.ivMovimientosEmpty.isVisible = true
                            binding.tvNoMovimientos.isVisible = true
                        }
                    }
                }
                binding.root.postDelayed(skeletonRunnable, 600)
                pendingRunnables.add(skeletonRunnable)

                // Desplazar scroll a la cabecera de Movimientos
                scrollToView(binding.tvMovimientosTitle)
            },
            fallback = Unit,
            errorMessage = "Error showing movements section"
        )

        // Configurar búsqueda de movimientos
        setupSearchMovements()
        
        // Listeners de pestañas con protección anti-spam
        binding.btnHoy.setOnClickListener {
            if (!isValidClick()) return@setOnClickListener
            if (currentMovTab == MovTab.HOY) return@setOnClickListener
            
            // Ocultar búsqueda en pestaña HOY
            binding.etSearchMovements.isVisible = false
            binding.dividerSearch.isVisible = false
            binding.etSearchMovements.text?.clear()
            
            updateMovementsTab(MovTab.HOY)
            if (loadedMovements.isEmpty()) {
                showEmptyMovements()
            } else {
                if (hasToday(loadedMovements)) {
                    movementsAdapter?.setToday(loadedMovements)
                    binding.skeletonLayout.root.isVisible = false
                    binding.ivMovimientosEmpty.isVisible = false
                    binding.tvNoMovimientos.isVisible = false
                    binding.recyclerViewMovements.isVisible = true
                } else {
                    // Sin movimientos de hoy: mostrar vacío
                    showEmptyMovements()
                }
            }
        }
        
        binding.btnMasMovimientos.setOnClickListener {
            if (!isValidClick()) return@setOnClickListener
            if (currentMovTab == MovTab.MAS) return@setOnClickListener
            
            // Mostrar búsqueda en pestaña MÁS MOVIMIENTOS
            binding.etSearchMovements.isVisible = true
            binding.dividerSearch.isVisible = true
            binding.etSearchMovements.bringToFront()
            binding.dividerSearch.bringToFront()
            
            updateMovementsTab(MovTab.MAS)
            if (loadedMovements.isEmpty()) {
                showEmptyMovements()
            } else {
                movementsAdapter?.setGrouped(loadedMovements)
                binding.skeletonLayout.root.isVisible = false
                binding.ivMovimientosEmpty.isVisible = false
                binding.tvNoMovimientos.isVisible = false
                binding.recyclerViewMovements.isVisible = true
            }
        }
    }

    private fun setupSearchMovements() {
        binding.etSearchMovements.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                filterMovements(query)
            }
        })
    }
    
    private fun filterMovements(query: String) {
        if (query.isEmpty()) {
            // Sin filtro: mostrar todos los movimientos agrupados
            if (currentMovTab == MovTab.MAS) {
                movementsAdapter?.setGrouped(loadedMovements)
            } else {
                movementsAdapter?.setToday(loadedMovements)
            }
        } else {
            // Filtrar movimientos por nombre o teléfono
            val filtered = loadedMovements.filter { movement ->
                movement.name.contains(query, ignoreCase = true) ||
                movement.phone.contains(query, ignoreCase = true)
            }
            
            if (filtered.isEmpty()) {
                showEmptyMovements()
            } else {
                binding.ivMovimientosEmpty.isVisible = false
                binding.tvNoMovimientos.isVisible = false
                binding.recyclerViewMovements.isVisible = true
                
                if (currentMovTab == MovTab.MAS) {
                    movementsAdapter?.setGrouped(filtered)
                } else {
                    movementsAdapter?.setToday(filtered)
                }
            }
        }
    }

    private fun updateMovementsTab(tab: MovTab) {
        currentMovTab = tab
        val pink = runCatching { ContextCompat.getColor(this, R.color.nequi_pink) }.getOrNull() ?: Color.MAGENTA
        val white = ContextCompat.getColor(this, android.R.color.white)
        val black = ContextCompat.getColor(this, android.R.color.black)
        val softBorder = Color.parseColor("#33000000") // borde delgado y suave

        // Rectángulo con esquinas redondeadas (no óvalo): radio fijo 8dp
        val radius = dpToPx(8).toFloat()

        val hoyActive = tab == MovTab.HOY
        // Hoy
        if (hoyActive) {
            binding.btnHoy.background = roundedFilled(pink, radius)
            binding.btnHoy.setTextColor(ColorStateList.valueOf(white))
            // Más movimientos: fondo blanco con borde suave, misma forma ovalada
            binding.btnMasMovimientos.background = roundedStroke(white, softBorder, dpToPx(1), radius)
            binding.btnMasMovimientos.setTextColor(ColorStateList.valueOf(black))
        } else {
            // Inverso: Más movimientos activo en rosa
            binding.btnHoy.background = roundedStroke(white, softBorder, dpToPx(1), radius)
            binding.btnHoy.setTextColor(ColorStateList.valueOf(black))
            binding.btnMasMovimientos.background = roundedFilled(pink, radius)
            binding.btnMasMovimientos.setTextColor(ColorStateList.valueOf(white))
        }
        binding.btnHoy.isSelected = hoyActive
        binding.btnMasMovimientos.isSelected = !hoyActive
    }

    private fun roundedFilled(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    private fun roundedStroke(fillColor: Int, strokeColor: Int, strokeWidthPx: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fillColor)
            setStroke(strokeWidthPx, strokeColor)
        }
    }

    private fun cargarSaldo() {
        lifecycleScope.launch {
            val userKey = userPhone.filter { it.isDigit() }
            val doc = try { db.collection("users").document(userKey).get().await() } catch (_: Exception) { null }
            val saldoLong = doc?.let { readLongFlexible(it, "saldo") } ?: 0L
            val colchonLong = doc?.let { readLongFlexible(it, "colchon") } ?: 0L
            val extraLong = doc?.let { readLongFlexible(it, "saldo_extra") } ?: 0L
            val disponible = if (extraLong > 0L) extraLong else saldoLong
            val total = (disponible + colchonLong).coerceAtLeast(0L)
            renderSaldo(disponible, total)

            // Configura UI estática (nombre, cards, sugeridos) una vez cargado el doc
            val name = doc?.getString("name")
            showStaticHomeUI(name)
        }
    }

    private fun renderSaldo(disponible: Long, total: Long) {
        lastDisponibleValue = disponible
        lastTotalValue = total
        val localeCO = Locale("es", "CO")
        val nf = NumberFormat.getCurrencyInstance(localeCO)
        val textoDisp = nf.format(disponible)
        val textoTotal = nf.format(total)
        // Texto suele salir como $ 15.000,00. Separamos parte entera/decimal para los 2 bloques
        fun splitMoney(t: String): Pair<String, String> {
            val partes = t.replace("\u00A0", " ").trim()
            val sinSimbolo = partes.replace("$", "").trim()
            val entero = sinSimbolo.substringBefore(",")
            val decimal = "," + sinSimbolo.substringAfter(",", "00")
            return entero to decimal
        }
        val (entDisp, decDisp) = splitMoney(textoDisp)
        val (entTot, decTot) = splitMoney(textoTotal)

        // ✅ NO TOCAR EL SHIMMER AQUÍ - el timer de triggerRefresh() lo controla
        // Solo actualizar los TEXTOS para que estén listos cuando termine el shimmer
        
        if (isBalanceHidden) {
            val starCount = entDisp.filter { it.isDigit() }.length.coerceAtLeast(1)
            val stars = "*".repeat(starCount)
            binding.tvSaldoEntero.text = stars
            binding.tvSaldoDecimal.text = ""
            binding.tvTotalSaldoEntero.text = stars
            binding.tvTotalSaldoDecimal.text = ""
        } else {
            binding.tvSaldoEntero.text = entDisp
            binding.tvSaldoDecimal.text = decDisp
            binding.tvTotalSaldoEntero.text = entTot
            binding.tvTotalSaldoDecimal.text = decTot
        }

        // ✅ NO cerrar el shimmer aquí - dejar que el timer lo controle
        // Los datos ya están cargados en las variables, solo esperamos a que termine el tiempo
    }

    private fun triggerRefresh() {
        if (refreshingHeader) return // Evitar múltiples refreshes
        
        // Scroll a la parte superior
        binding.mainScrollView.post {
            binding.mainScrollView.smoothScrollTo(0, 0)
        }
        
        // ✅ ACTIVAR FLAG - el listener de mainScrollView bloqueará automáticamente todos los toques
        // Ver línea 157-160: cuando refreshingHeader = true, consume todos los eventos
        refreshingHeader = true
        
        // Expandir header morado con spinner inmediatamente
        expandRefreshHeader(true)

        // Shimmer en SALDO y TOTAL
        setSaldoLoading(true)
        // Cargar datos
        cargarSaldo()

        // Duración: 2-5 segundos rápido y eficiente
        val refreshDuration = getRefreshDuration()
        binding.root.postDelayed({
            // Forzar cierre incluso si hay alguna condición extraña
            setSaldoLoading(false)
            expandRefreshHeader(false)
            
            // ✅ DESACTIVAR FLAG - el listener de mainScrollView volverá a funcionar normalmente
            refreshingHeader = false
        }, refreshDuration)
    }

    private fun getRefreshDuration(): Long {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        
        if (connectivityManager == null) {
            // Si no podemos detectar, usar duración media
            return 3500L // 3.5 segundos
        }
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                
                if (capabilities != null) {
                    // WiFi o Ethernet = rápido
                    if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        
                        // Verificar velocidad de descarga si está disponible
                        val downSpeed = capabilities.linkDownstreamBandwidthKbps
                        
                        return when {
                            downSpeed > 5000 -> (2000..3000).random().toLong() // Internet rápido: 2-3s
                            downSpeed > 1000 -> (3000..5000).random().toLong() // Internet medio: 3-5s
                            else -> (5000..7000).random().toLong() // WiFi lento: 5-7s
                        }
                    }
                    // Datos móviles
                    else if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return (4000..6000).random().toLong() // Datos móviles: 4-6s
                    }
                }
            } else {
                // Android < M: usar método legacy
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                
                if (networkInfo != null && networkInfo.isConnected) {
                    return when (networkInfo.type) {
                        android.net.ConnectivityManager.TYPE_WIFI,
                        android.net.ConnectivityManager.TYPE_ETHERNET -> (2000..4000).random().toLong()
                        android.net.ConnectivityManager.TYPE_MOBILE -> (4000..6000).random().toLong()
                        else -> (3000..5000).random().toLong()
                    }
                }
            }
        } catch (e: Exception) {
            // Error al detectar red, usar duración por defecto
            return 3500L
        }
        
        // Sin conexión o no detectada: usar duración larga
        return (5000..7000).random().toLong()
    }
    
    private fun expandRefreshHeader(expand: Boolean) {
        val header = binding.refreshHeader
        val spinner = binding.pullProgressBar
        
        if (expand) {
            // Animar la altura del header de 0 a 55dp para empujar el contenido
            val targetHeight = dpToPx(55)
            val currentHeight = if (header.layoutParams.height > 0) header.layoutParams.height else 0
            val layoutParams = header.layoutParams
            
            android.animation.ValueAnimator.ofInt(currentHeight, targetHeight).apply {
                duration = 300
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { animator ->
                    val height = animator.animatedValue as Int
                    layoutParams.height = height
                    header.layoutParams = layoutParams
                    
                    // Hacer visible en el primer frame (cuando height > 0) para evitar destellos
                    if (height > 0 && header.visibility != View.VISIBLE) {
                        header.visibility = View.VISIBLE
                        spinner.visibility = View.VISIBLE
                    }
                }
                start()
            }
        } else {
            // CONTRAER: Animar la altura del header de actual a 0
            val layoutParams = header.layoutParams
            val currentHeight = layoutParams.height
            
            // Si ya está en 0, solo ocultar y salir
            if (currentHeight <= 0) {
                header.visibility = View.GONE
                spinner.visibility = View.GONE
                layoutParams.height = 0
                header.layoutParams = layoutParams
                return
            }
            
            // Animar contracción de altura actual a 0
            android.animation.ValueAnimator.ofInt(currentHeight, 0).apply {
                duration = 300
                interpolator = android.view.animation.AccelerateInterpolator()
                addUpdateListener { animator ->
                    val height = animator.animatedValue as Int
                    layoutParams.height = height
                    header.layoutParams = layoutParams
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // Asegurar que todo esté en estado original
                        layoutParams.height = 0
                        header.layoutParams = layoutParams
                        header.visibility = View.GONE
                        spinner.visibility = View.GONE
                    }
                })
                start()
            }
        }
    }

    private fun setSaldoLoading(loading: Boolean) {
        if (loading) {
            // ✅ REINICIAR shimmer completamente para que dure los 2.5 segundos cada vez
            // Primero detener cualquier animación anterior
            binding.shimmerSaldo.stopShimmer()
            binding.shimmerTotal.stopShimmer()
            
            // Hacer visibles los shimmers
            binding.shimmerSaldo.isVisible = true
            binding.shimmerTotal.isVisible = true
            
            // Ocultar números reales
            binding.ivMoney.visibility = View.INVISIBLE
            binding.saldoContainer.visibility = View.INVISIBLE
            binding.tvTotal.visibility = View.INVISIBLE
            binding.totalSaldoContainer.visibility = View.INVISIBLE
            
            // Iniciar shimmer con un pequeño delay para asegurar que se reinicia
            binding.shimmerSaldo.post {
                binding.shimmerSaldo.startShimmer()
            }
            binding.shimmerTotal.post {
                binding.shimmerTotal.startShimmer()
            }
        } else {
            // Detener shimmers
            binding.shimmerSaldo.stopShimmer()
            binding.shimmerTotal.stopShimmer()
            binding.shimmerSaldo.isVisible = false
            binding.shimmerTotal.isVisible = false
            
            // Mostrar números reales
            binding.ivMoney.visibility = View.VISIBLE
            binding.saldoContainer.visibility = View.VISIBLE
            binding.tvTotal.visibility = View.VISIBLE
            binding.totalSaldoContainer.visibility = View.VISIBLE
        }
    }

    // Spinner central del refresh (usa ProgressBar @id/pullProgressBar)
    private fun showRefreshSpinner() {
        val spinner = binding.pullProgressBar
        // Forzar BLANCO PURO en todos los modos
        val white = 0xFFFFFFFF.toInt()
        spinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(white)
        spinner.indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        spinner.indeterminateDrawable?.apply {
            setTint(white)
            setTintMode(android.graphics.PorterDuff.Mode.SRC_IN)
            setTintList(android.content.res.ColorStateList.valueOf(white))
        }
        // Asegurar que sea completamente visible
        spinner.alpha = 1f
        spinner.isVisible = true
    }

    private fun hideRefreshSpinner() {
        // El manejo del spinner ahora está en expandRefreshHeader
    }

    private fun showStaticHomeUI(docName: String?) {
        // 1) Nombre y saludo
        val nombre = docName?.takeIf { it.isNotBlank() } ?: "Nequi Ven"
        binding.tvUserGreeting.text = "Hola,"
        binding.tvUserName.text = formatNameForDisplay(nombre)

        // Estas visibilidades también se controlan por el shimmer inicial
        binding.shimmerGreeting.isVisible = false
        binding.shimmerUserName.isVisible = false
        binding.tvUserGreeting.isVisible = true
        binding.tvUserName.isVisible = true
        
        // Cargar foto de perfil del usuario
        loadUserProfilePhoto()

        // 2) Cards debajo de Favoritos
        binding.shimmerCards.isVisible = false
        binding.cardsScrollView.isVisible = true

        // 3) Sugeridos Nequi (orden y drawables originales del layout)
        // Bre-B
        binding.ivNegocio.setImageResource(R.drawable.bre_b)
        // Claro
        binding.ivTigo.setImageResource(R.drawable.claro)
        // Otras empresas
        binding.ivKey.setImageResource(R.drawable.otrasempresas)
        // Tienda virtual
        binding.ivCredits.setImageResource(R.drawable.tiendavirtual)
        // Colchón
        binding.ivClaro.setImageResource(R.drawable.colchon)
        // Recarga de celular
        binding.ivBolsillosSugerido.setImageResource(R.drawable.recargacelular)
        // Maas tullave
        binding.image6.setImageResource(R.drawable.tuullave)

        // Se revelan por shimmer inicial
        binding.shimmerSuggested.isVisible = false
        binding.shimmerSuggestedIcons.isVisible = false
        binding.suggestedIconsScrollView.isVisible = true
    }

    private fun startHomeShimmer(includeBottom: Boolean, saldoDurationMs: Int) {
        isHomeShimmerActive = true
        isSaldoShimmerActive = true
        // Greeting y nombre
        binding.shimmerGreeting.isVisible = true
        binding.shimmerUserName.isVisible = true
        binding.tvUserGreeting.isVisible = false
        binding.tvUserName.isVisible = false

        // Cards
        binding.shimmerCards.isVisible = true
        binding.cardsScrollView.isVisible = false

        // Saldo (usar placeholders mientras shimmer activo)
        setSaldoLoading(true)

        // Programar fin del shimmer del saldo antes que el general
        binding.root.postDelayed({
            isSaldoShimmerActive = false
            setSaldoLoading(false)
            // Asegurar visibilidad de los bloques de saldo
            binding.ivMoney.isVisible = true
            binding.saldoContainer.isVisible = true
            binding.tvTotal.isVisible = true
            binding.totalSaldoContainer.isVisible = true
        }, saldoDurationMs.toLong())

        // Sugeridos
        binding.shimmerSuggested.isVisible = true
        binding.shimmerSuggestedIcons.isVisible = true
        binding.suggestedIconsScrollView.isVisible = false

        // Bottom navigation shimmer (solo cuando incluye bottom)
        if (includeBottom) {
            binding.shimmerBottomNav.isVisible = true
            binding.buttonsContainer.isVisible = false
        }
    }

    private fun stopHomeShimmerReveal(includeBottom: Boolean) {
        isHomeShimmerActive = false
        // Greeting y nombre
        binding.shimmerGreeting.isVisible = false
        binding.shimmerUserName.isVisible = false
        binding.tvUserGreeting.isVisible = true
        binding.tvUserName.isVisible = true

        // Cards
        binding.shimmerCards.isVisible = false
        binding.cardsScrollView.isVisible = true

        // Saldo (si aún quedara activo por cualquier razón, forzar fin)
        isSaldoShimmerActive = false
        setSaldoLoading(false)

        // Sugeridos
        binding.shimmerSuggested.isVisible = false
        binding.shimmerSuggestedIcons.isVisible = false
        binding.suggestedIconsScrollView.isVisible = true

        // Bottom navigation shimmer (solo cuando incluye bottom)
        if (includeBottom) {
            binding.shimmerBottomNav.isVisible = false
            binding.buttonsContainer.isVisible = true
        }
    }

    private fun setupInteractions() {
        // Bottom navigation con protección anti-spam
        binding.btnHome.setOnClickListener {
            if (!isValidClick()) return@setOnClickListener
            if (currentSection == BottomSection.HOME) return@setOnClickListener
            
            startNavigation()
            try {
                currentSection = BottomSection.HOME
                selectBottom(BottomSection.HOME)
                showMainContent()
                scrollToTop()
            } finally {
                finishNavigation()
            }
        }
        
        binding.btnMovements.setOnClickListener {
            if (!isValidClick()) return@setOnClickListener
            if (currentSection == BottomSection.MOVEMENTS) return@setOnClickListener
            
            startNavigation()
            try {
                currentSection = BottomSection.MOVEMENTS
                selectBottom(BottomSection.MOVEMENTS)
                showMovements()
            } finally {
                finishNavigation()
            }
        }
        
        binding.btnServices.setOnClickListener {
            if (!isValidClick()) return@setOnClickListener
            if (currentSection == BottomSection.SERVICES) return@setOnClickListener
            
            startNavigation()
            try {
                currentSection = BottomSection.SERVICES
                selectBottom(BottomSection.SERVICES)
                showServices()
            } finally {
                finishNavigation()
            }
        }

        // Botón flotante cash -> muestra overlay
        binding.ivSend.setOnClickListener {
            binding.sendMenuOverlay.isVisible = true
        }
        // Cerrar overlay
        binding.ivSendClose.setOnClickListener { binding.sendMenuOverlay.isVisible = false }
        binding.sendMenuBackground.setOnClickListener { binding.sendMenuOverlay.isVisible = false }

        // Acciones rápidas dentro del overlay
        binding.ivServices.setOnClickListener {
            binding.sendMenuOverlay.isVisible = false
            selectBottom(BottomSection.SERVICES)
            showServices()
        }
        binding.ivCashout.setOnClickListener { binding.sendMenuOverlay.isVisible = false }
        binding.ivRequest.setOnClickListener { binding.sendMenuOverlay.isVisible = false }
        binding.ivSendAt.setOnClickListener {
            binding.sendMenuOverlay.isVisible = false
            val dialog = BottomSheetDialog(this)
            val view = layoutInflater.inflate(R.layout.bottom_sheet_send_options, null)
            // Botón cerrar
            view.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }

            val pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse_in)
            view.findViewById<ImageView?>(R.id.ivNequiLogo)?.startAnimation(pulseAnimation)
            dialog.setOnDismissListener {
                view.findViewById<ImageView?>(R.id.ivNequiLogo)?.clearAnimation()
            }

            // 1) A Nequi -> SendMoneyActivity (enviar dinero a Nequi)
            view.findViewById<View>(R.id.btnOption1)?.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this, SendMoneyActivity::class.java)
                intent.putExtra("user_phone", userPhone)
                startActivity(intent)
            }

            // 2) A Bancolombia -> BancolombiaActivity
            view.findViewById<View>(R.id.btnOption2)?.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this, BancolombiaActivity::class.java)
                if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
                startActivity(intent)
            }

            // 3) Llaves Bre-B -> Mostrar dialog_tus_llaves primero
            view.findViewById<View>(R.id.btnOption3)?.setOnClickListener {
                dialog.dismiss()
                showDialogTusLlaves()
            }

            // 4) A otros bancos -> Animación rombo -> CuandoLlegaPlataActivity
            view.findViewById<View>(R.id.btnOption4)?.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this, AnimacionDeRomboSolamente::class.java)
                intent.putExtra("next_activity", "CuandoLlegaPlataActivity")
                if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
                startActivity(intent)
            }

            // 5) Transfiya -> PaymentActivity
            view.findViewById<View>(R.id.btnOption5)?.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this, PaymentActivity::class.java)
                intent.putExtra("mode", "transfiya")
                startActivity(intent)
            }

            dialog.setContentView(view)
            dialog.show()
        }
        binding.ivQr.setOnClickListener {
            // Cerrar overlay de Cash y mostrar opciones de QR en bottom sheet
            binding.sendMenuOverlay.isVisible = false
            val dialog = BottomSheetDialog(this)
            val view = layoutInflater.inflate(R.layout.bottom_sheet_qr_options, null)
            // Botón cerrar si existe en el layout
            view.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }
            // Escanea QR -> abrir QrScannerActivity
            view.findViewById<View>(R.id.btnOption1)?.setOnClickListener {
                dialog.dismiss()
                val i = Intent(this, QrScannerActivity::class.java)
                if (userPhone.isNotEmpty()) i.putExtra("user_phone", userPhone)
                startActivity(i)
            }
            dialog.setContentView(view)
            dialog.show()
        }
        binding.ivCashin.setOnClickListener { binding.sendMenuOverlay.isVisible = false }

        // Removidos los click listeners de ivColchon y colchonContainer 
        // porque muestran el ícono de Tigo, no del colchón

        // Atajo Colchón en la sección de sugeridos
        binding.ivClaro.setOnClickListener {
            val intent = Intent(this, ColchonActivity::class.java)
            if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
            startActivity(intent)
        }

        // Sección usuario -> navegar a ProfileActivity
        val goProfile: (View) -> Unit = {
            val intent = Intent(this, ProfileActivity::class.java)
            intent.putExtra("user_phone", userPhone)
            startActivity(intent)
        }
        binding.ivUserImage.setOnClickListener(goProfile)
        binding.tvUserGreeting.setOnClickListener(goProfile)
        binding.tvUserName.setOnClickListener(goProfile)

        // Campanita de notificaciones
        binding.ivNoti.setOnClickListener {
            if (!isValidClick()) return@setOnClickListener
            openNotifications()
        }

        // Ojo de visibilidad para ocultar/mostrar saldo
        binding.ivVisibility.setOnClickListener {
            isBalanceHidden = !isBalanceHidden
            getSharedPreferences("home_prefs", MODE_PRIVATE).edit().putBoolean("balance_hidden", isBalanceHidden).apply()
            applyVisibilityIcon()
            // Re-render del saldo actual con el nuevo estado
            renderSaldo(lastDisponibleValue, lastTotalValue)
        }

        // Botón "Tu plata" -> abrir pantalla TuPlata
        binding.btnTuPlata.setOnClickListener {
            val intent = Intent(this, TuPlataActivity::class.java)
            if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
            startActivity(intent)
        }

        // Botón "Agrega" -> mostrar bottom sheet de favoritos
        binding.ivAdd.setOnClickListener {
            showFavoritesBottomSheet()
        }
    }

    private fun applyVisibilityIcon() {
        // Cuando el saldo está VISIBLE -> mostrar ojo CON raya (ic_visibility_off)
        // Cuando el saldo está OCULTO -> mostrar ojo SIN raya (design_password_eye)
        val icon = if (isBalanceHidden) R.drawable.design_password_eye else R.drawable.ic_visibility_off
        binding.ivVisibility.setImageResource(icon)
    }

    // Sin máscara de intro: el usuario decide con el ojo

    /**
     * Oculta todas las secciones de forma segura
     */
    private fun hideAllSections() {
        try {
            // Cancelar todas las animaciones y delays pendientes para evitar superposición
            cancelPendingRunnables()
            
            // Ocultar Home y Servicios INMEDIATAMENTE (síncrono)
            binding.mainContentContainer.isVisible = false
            binding.homeSwipe.isVisible = false
            binding.serviciosContainer.root.isVisible = false
            
            // Ocultar Movimientos INMEDIATAMENTE
            hideMovements()
            
            // Forzar actualización del layout para evitar estados intermedios
            binding.root.requestLayout()
        } catch (e: Exception) {
            android.util.Log.e("HomeActivity", "Error hiding sections: ${e.message}")
        }
    }

    private fun showMainContent() {
        // ✅ SÓLIDO: Limpiar estado anterior PRIMERO de forma síncrona
        hideAllSections()
        
        // ✅ SÓLIDO: Asegurar que el refresh header esté colapsado
        expandRefreshHeader(false)
        
        AndroidCompatibilityHelper.safeExecute(
            action = {
                // ✅ SÓLIDO: Mostrar el Scroll/Home con fade in
                binding.homeSwipe.isVisible = true
                binding.homeSwipe.alpha = 0f
                binding.homeSwipe.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
                
                binding.mainContentContainer.isVisible = true
                binding.mainContentContainer.alpha = 0f
                binding.mainContentContainer.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
                
                // Asegura que Inicio quede marcado si se llama desde otro flujo
                selectBottom(BottomSection.HOME)
            },
            fallback = Unit,
            errorMessage = "Error showing main content"
        )
    }

    private fun showServices() {
        // ✅ SÓLIDO: Limpiar estado anterior PRIMERO de forma síncrona
        hideAllSections()
        
        // ✅ SÓLIDO: Asegurar que el refresh header esté colapsado
        expandRefreshHeader(false)
        
        AndroidCompatibilityHelper.safeExecute(
            action = {
                // ✅ SÓLIDO: Mostrar sección de servicios
                binding.serviciosContainer.root.isVisible = true
                binding.serviciosContainer.root.alpha = 0f
                binding.serviciosContainer.root.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()

                // ✅ SÓLIDO: Shimmer mientras "carga"
                val shimmerCat = binding.serviciosContainer.shimmerGridCategorias
                val gridCat = binding.serviciosContainer.gridCategorias
                shimmerCat.isVisible = true
                shimmerCat.startShimmer()
                gridCat.isVisible = false
                gridCat.alpha = 0f

                val shimmerBan = binding.serviciosContainer.shimmerBanner
                val ivBan = binding.serviciosContainer.ivBanner
                shimmerBan.isVisible = true
                shimmerBan.startShimmer()
                ivBan.isVisible = false
                ivBan.alpha = 0f

                // ✅ SÓLIDO: Mostrar contenido con fade in
                val servicesRunnable = Runnable {
                    if (currentSection == BottomSection.SERVICES && !isFinishing) {
                        shimmerCat.stopShimmer()
                        shimmerCat.isVisible = false
                        gridCat.isVisible = true
                        gridCat.animate().alpha(1f).setDuration(300).start()

                        shimmerBan.stopShimmer()
                        shimmerBan.isVisible = false
                        ivBan.isVisible = true
                        ivBan.animate().alpha(1f).setDuration(300).start()
                    }
                }
                binding.serviciosContainer.root.postDelayed(servicesRunnable, 700)
                pendingRunnables.add(servicesRunnable)

                // Marcar la pestaña de Servicios como activa
                selectBottom(BottomSection.SERVICES)
            },
            fallback = Unit,
            errorMessage = "Error showing services section"
        )
    }

    private fun hideMovements() {
        // Cabecera y controles
        binding.tvMovimientosTitle.isVisible = false
        binding.etSearchMovements.isVisible = false
        binding.dividerSearch.isVisible = false
        binding.movimientosButtonsContainer.isVisible = false
        binding.tvHoyLabel.isVisible = false
        // Contenidos
        binding.skeletonLayout.root.isVisible = false
        binding.recyclerViewMovements.isVisible = false
        binding.ivMovimientosEmpty.isVisible = false
        binding.tvNoMovimientos.isVisible = false
    }

    private fun scrollToTop() {
        binding.mainScrollView.post {
            binding.mainScrollView.smoothScrollTo(0, 0)
        }
    }

    private fun scrollToView(target: View) {
        binding.mainScrollView.post {
            val y = target.top
            binding.mainScrollView.smoothScrollTo(0, y)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * Cancela todos los runnables pendientes para evitar superposición
     */
    private fun cancelPendingRunnables() {
        pendingRunnables.forEach { runnable ->
            binding.root.removeCallbacks(runnable)
        }
        pendingRunnables.clear()
    }
    
    /**
     * Previene clicks rápidos y navegación enloquecida
     */
    private fun isValidClick(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < CLICK_DEBOUNCE_TIME || isNavigating) {
            android.util.Log.d("HomeActivity", "Click bloqueado - demasiado rápido o navegando")
            return false
        }
        lastClickTime = currentTime
        return true
    }

    /**
     * Marca el inicio de navegación para prevenir clicks múltiples
     */
    private fun startNavigation() {
        isNavigating = true
        // Auto-reset después de 2 segundos como medida de seguridad
        binding.root.postDelayed({
            isNavigating = false
        }, 2000)
    }

    /**
     * Finaliza la navegación
     */
    private fun finishNavigation() {
        isNavigating = false
    }

    private fun readLongFlexible(doc: com.google.firebase.firestore.DocumentSnapshot, field: String): Long {
        val anyVal = doc.get(field) ?: return 0L
        return when (anyVal) {
            is Number -> anyVal.toLong()
            is String -> anyVal.filter { it.isDigit() }.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private enum class BottomSection { HOME, MOVEMENTS, SERVICES }

    private fun selectBottom(section: BottomSection) {
        val home = section == BottomSection.HOME
        val movements = section == BottomSection.MOVEMENTS
        val services = section == BottomSection.SERVICES

        // Marcar contenedores (para backgrounds selectores)
        binding.btnHome.isSelected = home
        binding.btnMovements.isSelected = movements
        binding.btnServices.isSelected = services

        // Marcar iconos (para selectors de drawable)
        binding.imgHome.isSelected = home
        binding.imgMovements.isSelected = movements
        binding.imgServices.isSelected = services
    }

    private fun loadMovements() {
        // Limpiar estados iniciales
        binding.recyclerViewMovements.isVisible = false
        binding.ivMovimientosEmpty.isVisible = false
        binding.tvNoMovimientos.isVisible = false

        // Verificar si estamos en el hilo principal (importante para Android 13-15)
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread { loadMovements() }
            return
        }

        AndroidCompatibilityHelper.safeExecute(
            action = {
                e.j(this) { movements ->
                    // Usar helper para ejecutar en hilo principal de forma segura
                    AndroidCompatibilityHelper.runOnMainThread {
                        loadedMovements = movements
                        if (movements.isEmpty()) {
                            showEmptyMovements()
                        } else {
                            binding.ivMovimientosEmpty.isVisible = false
                            binding.tvNoMovimientos.isVisible = false
                            binding.recyclerViewMovements.isVisible = true
                            
                            if (currentMovTab == MovTab.HOY) {
                                if (hasToday(movements)) {
                                    movementsAdapter?.setToday(movements)
                                } else {
                                    showEmptyMovements()
                                    return@runOnMainThread
                                }
                            } else {
                                movementsAdapter?.setGrouped(movements)
                            }
                        }
                    }
                }
            },
            fallback = Unit,
            errorMessage = "Error loading movements in HomeActivity"
        )
    }

    private fun hasToday(movements: List<Movement>): Boolean {
        val calNow = java.util.Calendar.getInstance()
        val y = calNow.get(java.util.Calendar.YEAR)
        val d = calNow.get(java.util.Calendar.DAY_OF_YEAR)
        return movements.any { m ->
            m.date?.let { dt ->
                val c = java.util.Calendar.getInstance().apply { time = dt }
                c.get(java.util.Calendar.YEAR) == y && c.get(java.util.Calendar.DAY_OF_YEAR) == d
            } ?: false
        }
    }

    private fun showEmptyMovements() {
        binding.skeletonLayout.root.isVisible = false
        binding.recyclerViewMovements.isVisible = false
        binding.ivMovimientosEmpty.isVisible = true
        binding.tvNoMovimientos.isVisible = true
    }

    /**
     * Verifica si hay actualizaciones disponibles usando Firebase Remote Config
     */
    private fun checkForUpdates() {
        android.util.Log.d("HomeActivity", "🚀 Iniciando checkForUpdates()...")
        lifecycleScope.launch {
            try {
                android.util.Log.d("HomeActivity", "📞 Llamando a updateManager.checkForUpdate()...")
                val updateInfo = updateManager.checkForUpdate()

                android.util.Log.d("HomeActivity", "📦 UpdateInfo recibido: needsUpdate=${updateInfo.needsUpdate}, isMandatory=${updateInfo.isMandatory}")

                if (updateInfo.needsUpdate && updateDialog?.isShowing() != true) {
                    android.util.Log.d("HomeActivity", "🎉 ¡Mostrando diálogo de actualización!")
                    updateDialog = UpdateDialog(
                        context = this@HomeActivity,
                        updateInfo = updateInfo,
                        onUpdateClick = {
                            android.util.Log.d("HomeActivity", "👆 Usuario hizo clic en Actualizar")
                            // Abrir URL de descarga
                            if (updateInfo.updateUrl.isNotEmpty()) {
                                updateManager.openUpdateUrl(updateInfo.updateUrl)
                            }
                            // Si es actualización obligatoria, cerrar la app después de ir a la URL
                            if (updateInfo.isMandatory) {
                                finish()
                            }
                        },
                        onLaterClick = {
                            android.util.Log.d("HomeActivity", "👆 Usuario hizo clic en Más tarde")
                            // Usuario decidió actualizar más tarde
                            // Puedes guardar un timestamp en SharedPreferences para no molestar mucho
                        }
                    )
                    updateDialog?.show()
                } else {
                    android.util.Log.d("HomeActivity", "ℹ️ No se muestra diálogo: needsUpdate=${updateInfo.needsUpdate}, dialogShowing=${updateDialog?.isShowing()}")
                }
            } catch (e: Exception) {
                // Manejar silenciosamente errores de verificación de actualización
                android.util.Log.e("HomeActivity", "❌ Error en checkForUpdates: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }

    private fun showFavoritesBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_favorites, null)
        
        // Botón cerrar
        view.findViewById<View>(R.id.btnClose)?.setOnClickListener { 
            dialog.dismiss() 
        }
        
        // Botón "Agrega" -> abrir bottom sheet de agregar favoritos
        view.findViewById<View>(R.id.btnAgrega)?.setOnClickListener {
            dialog.dismiss()
            showAddFavoritesBottomSheet()
        }
        
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showAddFavoritesBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_favorites, null)
        
        // Botón atrás
        view.findViewById<View>(R.id.btnBack)?.setOnClickListener { 
            dialog.dismiss()
            showFavoritesBottomSheet() // Volver al bottom sheet anterior
        }
        
        dialog.setContentView(view)
        dialog.show()
    }

    // ===== SISTEMA DE NOTIFICACIONES =====
    
    private fun openNotifications() {
        val intent = Intent(this, NotificationsActivity::class.java)
        startActivityForResult(intent, NOTIFICATION_REQUEST_CODE)
    }
    
    private fun updateNotificationBadge() {
        val prefs = getSharedPreferences("notifications_prefs", MODE_PRIVATE)
        val isRead = prefs.getBoolean("notification_read", false)
        
        // Mostrar/ocultar el punto rojo de notificación
        binding.notificationDot.visibility = if (isRead) View.GONE else View.VISIBLE
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == NOTIFICATION_REQUEST_CODE && resultCode == RESULT_OK) {
            // La notificación fue leída, actualizar el badge
            updateNotificationBadge()
        }
    }
    
    // ===== SPLASH OBLIGATORIO DE NOMBRE =====
    
    /**
     * Verifica si el usuario tiene nombre configurado y muestra splash si es necesario
     */
    private fun checkUserNameRequired() {
        lifecycleScope.launch {
            try {
                val userKey = userPhone.filter { it.isDigit() }
                val doc = db.collection("users").document(userKey).get().await()
                val userName = doc.getString("name")
                
                // Si no tiene nombre o es el nombre por defecto, mostrar splash obligatorio
                if (userName.isNullOrEmpty() || userName.equals("NEQUIXOFFICIAL", ignoreCase = true) || 
                    userName.equals("USUARIO NEQUI", ignoreCase = true)) {
                    showRequiredNameDialog()
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeActivity", "Error verificando nombre: ${e.message}")
                // Si hay error, asumir que necesita nombre
                showRequiredNameDialog()
            }
        }
    }
    
    /**
     * Muestra el dialog obligatorio para configurar nombre
     */
    private fun showRequiredNameDialog() {
        // Mostrar el dialog incluido en el layout
        val nameDialogView = findViewById<View>(R.id.nameDialog)
        nameDialogView?.visibility = View.VISIBLE
        
        val etName = findViewById<TextInputEditText>(R.id.etName)
        val btnSaveName = findViewById<Button>(R.id.btnSaveName)
        
        // Validación en tiempo real
        etName?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val name = s?.toString()?.trim() ?: ""
                val isValid = name.length >= 2 && name.split(" ").size >= 2
                
                btnSaveName?.isEnabled = isValid
                btnSaveName?.alpha = if (isValid) 1f else 0.6f
            }
        })
        
        // Botón guardar
        btnSaveName?.setOnClickListener {
            val name = etName?.text?.toString()?.trim() ?: ""
            android.util.Log.d("HomeActivity", "🔵 Click en guardar nombre. Texto ingresado: '$name'")
            
            if (name.length >= 2 && name.split(" ").size >= 2) {
                android.util.Log.d("HomeActivity", "🔵 Validación OK. Llamando a saveUserName()...")
                
                // Deshabilitar botón mientras guarda
                btnSaveName?.isEnabled = false
                btnSaveName?.alpha = 0.6f
                
                saveUserName(name) { success ->
                    // Rehabilitar botón
                    btnSaveName?.isEnabled = true
                    btnSaveName?.alpha = 1f
                    
                    if (success) {
                        android.util.Log.d("HomeActivity", "✅ Callback SUCCESS. Cerrando dialog...")
                        nameDialogView?.visibility = View.GONE
                        // Actualizar UI con el nuevo nombre
                        showStaticHomeUI(name)
                    } else {
                        android.util.Log.e("HomeActivity", "❌ Callback FAILED. Mostrando error...")
                        // Mostrar error
                        etName?.error = "Error guardando nombre, intenta de nuevo"
                        com.ios.nequixofficialv2.utils.NequiAlert.showError(this, "Error guardando nombre. Verifica tu conexión")
                    }
                }
            } else {
                android.util.Log.w("HomeActivity", "⚠️ Validación falló. Nombre: '$name', Longitud: ${name.length}, Palabras: ${name.split(" ").size}")
                etName?.error = "Ingresa tu nombre completo (nombre y apellido)"
            }
        }
    }
    
    /**
     * Guarda el nombre del usuario en Firebase
     */
    private fun saveUserName(name: String, callback: (Boolean) -> Unit) {
        android.util.Log.d("HomeActivity", "🟢 saveUserName() iniciado. Nombre: '$name'")
        
        lifecycleScope.launch {
            try {
                val userKey = userPhone.filter { it.isDigit() }
                android.util.Log.d("HomeActivity", "🟢 UserKey: $userKey")
                android.util.Log.d("HomeActivity", "🟢 Guardando en Firestore: users/$userKey")
                
                // Usar set con merge para crear el campo si no existe
                val data = mapOf("name" to name.uppercase())
                android.util.Log.d("HomeActivity", "🟢 Data a guardar: $data")
                
                db.collection("users").document(userKey)
                    .set(data, SetOptions.merge())
                    .await()
                
                android.util.Log.d("HomeActivity", "✅ ÉXITO: Nombre guardado en Firebase: ${name.uppercase()}")
                android.util.Log.d("HomeActivity", "✅ Llamando callback(true)...")
                callback(true)
            } catch (e: Exception) {
                android.util.Log.e("HomeActivity", "❌ ERROR guardando nombre", e)
                android.util.Log.e("HomeActivity", "❌ Exception: ${e.javaClass.simpleName}")
                android.util.Log.e("HomeActivity", "❌ Message: ${e.message}")
                android.util.Log.e("HomeActivity", "❌ StackTrace: ${e.stackTraceToString()}")
                callback(false)
            }
        }
    }
    
    /**
     * Muestra el diálogo de registro de llaves Bre-B
     */
    private fun showDialogTusLlaves() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tus_llaves, null)
        
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // Botón "Enviar" -> ir a KeySendActivity
        dialogView.findViewById<View>(R.id.btnEnviar)?.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, KeySendActivity::class.java)
            if (userPhone.isNotEmpty()) intent.putExtra("user_phone", userPhone)
            startActivity(intent)
        }
        
        // Botón "Registrar" -> cerrar diálogo y quedarse en HomeActivity
        dialogView.findViewById<View>(R.id.btnRegistrar)?.setOnClickListener {
            dialog.dismiss()
            // El usuario se queda en HomeActivity
        }
        
        dialog.show()
    }
    
    /**
     * Formatea nombres para mostrar en Title Case (Roberto Miranda)
     */
    private fun formatNameForDisplay(name: String): String {
        if (name.isBlank() || name.equals("NEQUIXOFFICIAL", ignoreCase = true)) {
            return name
        }
        
        return name.lowercase(java.util.Locale.getDefault())
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { c -> 
                    if (c.isLowerCase()) c.titlecase(java.util.Locale.getDefault()) else c.toString() 
                }
            }
    }
    
    /**
     * Solicita permisos de notificación para Android 13+
     * CRÍTICO: Sin este permiso, las notificaciones NO funcionarán en Android 13+
     */
    private fun requestNotificationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                
                android.util.Log.d("HomeActivity", "⚠️ Solicitando permisos de notificación para Android 13+ - OBLIGATORIO")
                
                // Verificar si ya se preguntó antes y el usuario denegó
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    // Usuario denegó antes, mostrar explicación
                    android.util.Log.w("HomeActivity", "Usuario denegó permisos antes, volviendo a solicitar")
                }
                
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_REQUEST_CODE
                )
            } else {
                android.util.Log.d("HomeActivity", "✅ Permisos de notificación ya concedidos")
            }
        } else {
            android.util.Log.d("HomeActivity", "ℹ️ Android < 13, no se requieren permisos de notificación")
        }
    }
    
    /**
     * Maneja la respuesta de la solicitud de permisos
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            NOTIFICATION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    android.util.Log.d("HomeActivity", "✅ Permisos de notificación concedidos - notificaciones activadas")
                    
                    // Reiniciar servicio para asegurar que funcione con el permiso nuevo
                    com.ios.nequixofficialv2.services.MovementListenerService.stop(this)
                    com.ios.nequixofficialv2.services.MovementListenerService.start(this, userPhone)
                } else {
                    android.util.Log.w("HomeActivity", "❌ Permisos de notificación denegados - las notificaciones NO funcionarán")
                    
                    // Mostrar advertencia al usuario
                    com.ios.nequixofficialv2.utils.NequiAlert.showError(
                        this,
                        "Sin permisos, no recibirás notificaciones de dinero"
                    )
                }
            }
        }
    }
    
    /**
     * Escucha movimientos INCOMING en tiempo real para notificar al usuario
     * ✅ OPTIMIZADO: Detecta primera carga y luego notifica todos los nuevos
     */
    private fun startListeningForIncomingMovements() {
        val userPhoneDigits = userPhone.filter { it.isDigit() }
        if (userPhoneDigits.length != 10) return
        
        android.util.Log.d("HomeActivity", "🔊 Iniciando listener para movimientos INCOMING del usuario: $userPhoneDigits")
        
        // Cancelar listener anterior si existe
        incomingMovementsListener?.remove()
        
        // Flag para controlar la primera carga
        var isFirstLoad = true
        
        // Escuchar cambios en la colección de movimientos del usuario
        incomingMovementsListener = db.collection("users")
            .document(userPhoneDigits)
            .collection("movements")
            .whereEqualTo("type", "INCOMING")
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    android.util.Log.e("HomeActivity", "❌ Error escuchando movimientos: ${error.message}")
                    return@addSnapshotListener
                }
                
                if (snapshots == null) return@addSnapshotListener
                
                // En la PRIMERA carga, solo marcar movimientos existentes (no notificar)
                if (isFirstLoad) {
                    val prefs = getSharedPreferences("notified_movements_service", Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    snapshots.documents.forEach { doc ->
                        editor.putBoolean(doc.id, true)
                    }
                    editor.apply()
                    isFirstLoad = false
                    android.util.Log.d("HomeActivity", "📋 Primera carga: ${snapshots.documents.size} movimientos existentes marcados")
                    return@addSnapshotListener
                }
                
                // DESPUÉS de la primera carga, notificar SOLO movimientos AÑADIDOS
                snapshots.documentChanges.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val movement = change.document
                        val movementId = movement.id
                        val senderName = movement.getString("name") ?: "Alguien"
                        val amount = movement.getDouble("amount") ?: 0.0
                        
                        // 🚨 CONTROL ANTI-DUPLICADOS sincronizado con el servicio
                        val prefs = getSharedPreferences("notified_movements_service", Context.MODE_PRIVATE)
                        if (prefs.contains(movementId)) {
                            android.util.Log.d("HomeActivity", "⏭️ Movimiento $movementId ya notificado por el servicio, omitiendo")
                            return@forEach
                        }
                        
                        // Este es un movimiento NUEVO - notificar
                        android.util.Log.d("HomeActivity", "🔔 NUEVO movimiento INCOMING: $senderName envió $$amount")
                        
                        // Marcar como notificado ANTES de mostrar
                        prefs.edit().putBoolean(movementId, true).apply()
                        
                        // Mostrar notificación local inmediata
                        showIncomingMoneyNotification(senderName, "$$amount")
                        
                        // Actualizar la UI de movimientos SOLO si la sección está visible
                        if (currentSection == BottomSection.MOVEMENTS) {
                            loadMovements()
                        }
                    }
                }
            }
    }
    
    /**
     * Verifica y muestra notificaciones pendientes de Firebase
     * (para cuando la app estaba cerrada)
     */
    private fun checkPendingNotifications() {
        val userPhoneDigits = userPhone.filter { it.isDigit() }
        if (userPhoneDigits.length != 10) return
        
        android.util.Log.d("HomeActivity", "🔍 Verificando notificaciones pendientes para: $userPhoneDigits")
        
        // Buscar notificaciones pendientes en Firebase
        db.collection("pending_notifications")
            .whereEqualTo("receiver_phone", userPhoneDigits)
            .whereEqualTo("read", false)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (!querySnapshot.isEmpty) {
                    android.util.Log.d("HomeActivity", "📬 ${querySnapshot.size()} notificaciones pendientes encontradas")
                    
                    querySnapshot.documents.forEach { doc ->
                        val senderName = doc.getString("sender_name") ?: "Alguien"
                        val amount = doc.getString("amount") ?: "$0"
                        val type = doc.getString("type") ?: ""
                        
                        if (type == "money_received") {
                            android.util.Log.d("HomeActivity", "🔔 Mostrando notificación pendiente: $senderName envió $amount")
                            
                            // Mostrar notificación local
                            showIncomingMoneyNotification(senderName, amount)
                            
                            // Marcar como leída
                            doc.reference.update("read", true)
                                .addOnSuccessListener {
                                    android.util.Log.d("HomeActivity", "✅ Notificación marcada como leída")
                                }
                        }
                    }
                } else {
                    android.util.Log.d("HomeActivity", "✅ No hay notificaciones pendientes")
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.e("HomeActivity", "❌ Error verificando notificaciones pendientes: ${e.message}")
            }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Cancelar listener al cerrar la actividad
        incomingMovementsListener?.remove()
    }
    
    /**
     * Dispara Worker inmediatamente al abrir la app
     * Esto muestra notificaciones pendientes que llegaron con la app cerrada
     */
    private fun triggerNotificationWorkerOnStart() {
        try {
            val workRequest = OneTimeWorkRequestBuilder<NotificationWorker>()
                .addTag("startup_notification_check")
                .build()
            
            WorkManager.getInstance(this).enqueue(workRequest)
            
            android.util.Log.d("HomeActivity", "🚀 Worker disparado al abrir app para revisar notificaciones pendientes")
        } catch (e: Exception) {
            android.util.Log.e("HomeActivity", "❌ Error disparando Worker: ${e.message}")
        }
    }
    
    /**
     * Muestra notificación cuando llega dinero
     */
    private fun showIncomingMoneyNotification(senderName: String, amount: String) {
        try {
            val intent = Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("user_phone", userPhone)
            }

            val pendingIntent = PendingIntent.getActivity(
                this, 1001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = androidx.core.app.NotificationCompat.Builder(this, "nequi_money_transfers")
                .setSmallIcon(R.drawable.ic_notification_n)
                .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic_nequixofficial))
                .setContentTitle("Envío")
                .setContentText("$senderName te envió $amount, ¡lo mejor!")
                .setSubText("Nequi Colombia • ahora")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                    .bigText("$senderName te envió $amount, ¡lo mejor!")
                    .setBigContentTitle("Envío")
                    .setSummaryText("Nequi Colombia • ahora"))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVibrate(longArrayOf(0, 300, 100, 300))
                .setLights(resources.getColor(R.color.nequi_pink, null), 1000, 1000)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(1001, notification)
            
            android.util.Log.d("HomeActivity", "🔔 Notificación de dinero recibido mostrada exitosamente")
        } catch (e: Exception) {
            android.util.Log.e("HomeActivity", "Error mostrando notificación: ${e.message}")
        }
    }
    
    
    private fun loadUserProfilePhoto() {
        if (userPhone.isEmpty()) {
            android.util.Log.w("HomeActivity", "⚠️ userPhone vacío, no se puede cargar foto")
            return
        }
        
        android.util.Log.d("HomeActivity", "📥 Intentando cargar foto para: $userPhone")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Buscar archivo local
                val fileName = "profile_$userPhone.jpg"
                val localFile = java.io.File(filesDir, fileName)
                
                android.util.Log.d("HomeActivity", "🔍 Buscando foto local: ${localFile.absolutePath}")
                
                if (localFile.exists()) {
                    android.util.Log.d("HomeActivity", "✅ Foto local encontrada")
                    
                    withContext(Dispatchers.Main) {
                        try {
                            // Configurar el ImageView para que cubra todo el espacio CUADRADO
                            binding.ivUserImage.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            
                            com.bumptech.glide.Glide.with(this@HomeActivity)
                                .load(localFile)
                                .centerCrop()
                                .skipMemoryCache(true)
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                                .signature(com.bumptech.glide.signature.ObjectKey(System.currentTimeMillis()))
                                .placeholder(R.drawable.user_image)
                                .error(R.drawable.user_image)
                                .into(binding.ivUserImage)
                            
                            android.util.Log.d("HomeActivity", "✅ Foto de perfil cargada correctamente en esquina")
                        } catch (e: Exception) {
                            android.util.Log.e("HomeActivity", "❌ Error con Glide: ${e.message}", e)
                        }
                    }
                } else {
                    android.util.Log.d("HomeActivity", "⚠️ No hay foto local para $userPhone")
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeActivity", "❌ Error: ${e.message}", e)
            }
        }
    }
    
}
