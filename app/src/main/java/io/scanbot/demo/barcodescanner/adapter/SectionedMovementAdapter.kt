package io.scanbot.demo.barcodescanner.adapter

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.ios.nequixofficialv2.R
import io.scanbot.demo.barcodescanner.MovementDetailActivity
import io.scanbot.demo.barcodescanner.model.Movement
import io.scanbot.demo.barcodescanner.model.MovementType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

class SectionedMovementAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val TYPE_HEADER = 0
    private val TYPE_ITEM = 1

    // items puede contener String (header) o Movement (item)
    private var items: List<Any> = emptyList()

    // Compat: mantiene método existente, agrupa por defecto (Más movimientos)
    fun a0(movements: List<Movement>) = setGrouped(movements)

    fun setToday(movements: List<Movement>) {
        val today = movements.filter { isToday(it.date) }
        val list = mutableListOf<Any>()
        if (today.isNotEmpty()) {
            list.add(ctxHeader("Hoy"))
            list.addAll(today)
        }
        this.items = list
        notifyDataSetChanged()
    }

    fun setGrouped(movements: List<Movement>) {
        val hoy = mutableListOf<Movement>()
        val ayer = mutableListOf<Movement>()
        // Usar un mapa que incluya la fecha para ordenar correctamente
        val otros = mutableMapOf<Pair<String, Long>, MutableList<Movement>>()

        movements.forEach { m ->
            when {
                isToday(m.date) -> hoy += m
                isYesterday(m.date) -> ayer += m
                else -> {
                    // Mostrar la fecha exacta del movimiento con el día
                    val key = formatDayName(m.date)
                    val timestamp = m.date?.time ?: 0L
                    val list = otros.getOrPut(Pair(key, timestamp)) { mutableListOf() }
                    list += m
                }
            }
        }

        val list = mutableListOf<Any>()
        if (hoy.isNotEmpty()) {
            list.add(ctxHeader("Hoy"))
            list.addAll(hoy)
        }
        if (ayer.isNotEmpty()) {
            list.add(ctxHeader("Ayer"))
            list.addAll(ayer)
        }
        // Ordenar otros movimientos por fecha (más recientes primero)
        otros.toSortedMap(compareByDescending { it.second }).forEach { (k, v) ->
            list.add(ctxHeader(k.first))
            list.addAll(v)
        }
        this.items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = if (items[position] is String) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_movement_section_header, parent, false)
            HeaderVH(v)
        } else {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_movement, parent, false)
            MovementVH(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val obj = items[position]
        if (getItemViewType(position) == TYPE_HEADER) {
            (holder as HeaderVH).bind(obj as String)
        } else {
            (holder as MovementVH).bind(obj as Movement)
        }
    }

    private class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.tvSectionTitle)
        fun bind(t: String) { title.text = t }
    }

    private class MovementVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.imageViewMovementIcon)
        private val name: TextView = itemView.findViewById(R.id.textViewMovementName)
        private val date: TextView = itemView.findViewById(R.id.textViewMovementDate)
        private val amount: TextView = itemView.findViewById(R.id.textViewMovementAmount)

        fun bind(m: Movement) {
            val ctx = itemView.context
            val isPositive = (m.type == MovementType.RECARGA || m.type == MovementType.INCOMING)
            val sign = if (isPositive) "" else "-"

            // En la lista de movimientos, los nombres van en MAYÚSCULAS
            name.text = (m.name.ifBlank { "" }).uppercase(Locale.getDefault())

            // Subtítulo: 'PAGO EN QR BRE-B' para pagos QR; si no, 'Para' en salidas y 'De' en entradas/recargas
            val isQr = m.isQrPayment || m.type == MovementType.QR_VOUCH
            date.text = if (isQr) "PAGO EN QR BRE-B" else if (isPositive) "De" else "Para"

            // Formato de moneda local es-CO con símbolo y separadores
            val nf = java.text.NumberFormat.getCurrencyInstance(Locale("es", "CO"))
            val formatted = nf.format(m.amount)
            // Construir texto y hacer los decimales más pequeños (incluye la coma)
            run {
                val full = "$sign$formatted"
                val commaIndex = full.indexOf(',')
                if (commaIndex >= 0) {
                    val sb = android.text.SpannableStringBuilder(full)
                    sb.setSpan(
                        android.text.style.RelativeSizeSpan(0.80f),
                        commaIndex,
                        full.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    amount.text = sb
                } else {
                    amount.text = full
                }
            }

            // Color según signo (negativo: rojo nequi; positivo: verde)
            val negColor = android.graphics.Color.parseColor("#E6335A")
            val posColor = android.graphics.Color.parseColor("#00C896")
            amount.setTextColor(if (isPositive) posColor else negColor)

            // Icono según signo: verde apuntando hacia arriba en entradas/recargas, rojo hacia abajo en envíos
            try {
                icon.rotation = if (isPositive) 0f else 180f
                icon.setColorFilter(if (isPositive) posColor else negColor)
            } catch (_: Exception) { /* seguro en caso de drawables incompatibles */ }

            itemView.setOnClickListener {
                if (m.type == MovementType.RECARGA) {
                    Toast.makeText(ctx, "Recarga de $$${m.amount}", Toast.LENGTH_SHORT).show()
                } else {
                    openDetail(ctx, m, amount.text.toString())
                }
            }
        }

        private fun formatDate(raw: String?, d: Date?): String {
            val fmt = "yyyy-MM-dd HH:mm:ss"
            return try {
                when {
                    !raw.isNullOrBlank() -> {
                        val inFmt = SimpleDateFormat(fmt, Locale.getDefault())
                        val outFmt = SimpleDateFormat(fmt, Locale.getDefault())
                        val parsed = inFmt.parse(raw)
                        outFmt.format(parsed ?: Date())
                    }
                    d != null -> SimpleDateFormat(fmt, Locale.getDefault()).format(d)
                    else -> SimpleDateFormat(fmt, Locale.getDefault()).format(Date())
                }
            } catch (_: Exception) {
                SimpleDateFormat(fmt, Locale.getDefault()).format(Date())
            }
        }

        private fun openDetail(ctx: Context, m: Movement, amountLabel: String) {
            val unique = buildString {
                append(m.name)
                append('_')
                append(amountLabel)
                append('_')
                append(m.phone)
            }
                .replace(" ", "_")
                .replace(".", "")
                .replace(",", "")
                .replace("$", "")
                .replace("-", "m")
                .replace("+", "p")

            val dateMillis = m.date?.time?.toString() ?: "0"
            val isQr = m.isQrPayment || m.type == MovementType.QR_VOUCH
            
            // Log para debug
            android.util.Log.d("MovDetailQr", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            android.util.Log.d("MovDetailQr", "📱 ADAPTER: Abriendo movimiento")
            android.util.Log.d("MovDetailQr", "   Nombre: ${m.name}")
            android.util.Log.d("MovDetailQr", "   isQrPayment: ${m.isQrPayment}")
            android.util.Log.d("MovDetailQr", "   type: ${m.type}")
            android.util.Log.d("MovDetailQr", "   isQr final: $isQr")
            android.util.Log.d("MovDetailQr", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            
            val intent = Intent(ctx, MovementDetailActivity::class.java)
                .putExtra(MovementDetailActivity.EXTRA_RECIPIENT, m.name)
                .putExtra(MovementDetailActivity.EXTRA_AMOUNT, amountLabel)
                .putExtra(MovementDetailActivity.EXTRA_PHONE, m.phone)
                .putExtra(MovementDetailActivity.EXTRA_DATE, dateMillis)
                .putExtra(MovementDetailActivity.EXTRA_UNIQUE_ID, unique)
                .putExtra(MovementDetailActivity.EXTRA_IS_INCOMING, true)
                .putExtra(MovementDetailActivity.EXTRA_MVALUE, m.mvalue)
                .putExtra("IS_QR_PAYMENT", isQr)

            // Preferir comprobante guardado localmente
            when {
                !m.qrDetailImagePath.isNullOrBlank() -> {
                    val path = m.qrDetailImagePath
                    val uriStr = if (path!!.startsWith("file://")) path else "file://$path"
                    intent.putExtra(MovementDetailActivity.EXTRA_IMAGE_URL, uriStr)
                }
                !m.detailImageUrl.isNullOrBlank() -> {
                    intent.putExtra(MovementDetailActivity.EXTRA_IMAGE_URL, m.detailImageUrl)
                }
                !m.imageUrl.isNullOrBlank() -> {
                    intent.putExtra(MovementDetailActivity.EXTRA_IMAGE_URL, m.imageUrl)
                }
            }
            ctx.startActivity(intent)
        }
    }

    // Helpers de secciones
    private fun isToday(date: Date?): Boolean {
        if (date == null) return false
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply { time = date }
        return now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(date: Date?): Boolean {
        if (date == null) return false
        val cal = Calendar.getInstance().apply { time = date }
        val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return y.get(Calendar.YEAR) == cal.get(Calendar.YEAR) && y.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun formatDayName(date: Date?): String {
        if (date == null) return "Fecha desconocida"
        val loc = Locale("es", "CO")
        // Formato: "Jueves" (solo el nombre del día)
        val fmt = SimpleDateFormat("EEEE", loc)
        return fmt.format(date).replaceFirstChar { it.uppercase() }
    }

    private fun ctxHeader(t: String): String = t
}
