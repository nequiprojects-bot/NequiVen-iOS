package com.ios.nequixofficialv2.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ios.nequixofficialv2.R
import io.scanbot.demo.barcodescanner.model.Movement
import java.text.SimpleDateFormat
import java.util.*

sealed class MovementListItem {
    data class Header(val title: String) : MovementListItem()
    object EmptyToday : MovementListItem()
    data class Item(val movement: Movement) : MovementListItem()
}

class MovementsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<MovementListItem>()

    fun submit(newItems: List<MovementListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is MovementListItem.Header -> VIEW_HEADER
        is MovementListItem.EmptyToday -> VIEW_EMPTY
        is MovementListItem.Item -> VIEW_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_HEADER -> HeaderVH(inflater.inflate(R.layout.item_header_movement_section, parent, false))
            VIEW_EMPTY -> EmptyTodayVH(inflater.inflate(R.layout.item_empty_today, parent, false))
            else -> ItemVH(inflater.inflate(R.layout.item_movement, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is MovementListItem.Header -> (holder as HeaderVH).bind(item)
            is MovementListItem.EmptyToday -> Unit
            is MovementListItem.Item -> (holder as ItemVH).bind(item.movement)
        }
    }

    override fun getItemCount(): Int = items.size

    private class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tv: TextView = view.findViewById(R.id.tvSectionTitle)
        fun bind(item: MovementListItem.Header) { tv.text = item.title }
    }

    private class EmptyTodayVH(view: View) : RecyclerView.ViewHolder(view)

    private class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.textViewMovementName)
        private val tvDate: TextView = view.findViewById(R.id.textViewMovementDate)
        private val tvSubtitle: TextView = view.findViewById(R.id.textViewMovementSubtitle)
        private val tvAmount: TextView = view.findViewById(R.id.textViewMovementAmount)
        private val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        fun bind(m: Movement) {
            // En la lista de movimientos, los nombres van en MAYÚSCULAS
            val displayName = if (m.name.isNotEmpty()) m.name.uppercase(Locale.getDefault()) else m.type.name
            tvName.text = displayName
            
            // 🔥 CORRECCIÓN: Siempre mostrar subtítulo según el tipo de movimiento
            val isQr = m.isQrPayment || m.type.name == "QR_VOUCH"
            
            // SIEMPRE mostrar subtítulo
            tvSubtitle.visibility = View.VISIBLE
            tvDate.visibility = View.GONE
            
            // Determinar subtítulo según tipo
            tvSubtitle.text = when {
                isQr -> "PAGO EN QR BRE-B"
                m.isIncoming -> "De"  // ✅ INCOMING: "De"
                else -> "Para"        // ✅ OUTGOING: "Para"
            }
            
            val sign = if (m.isIncoming) "" else "-"
            tvAmount.text = "$sign$${String.format(Locale.getDefault(), "%,.2f", m.amount)}"
            
            // ✅ Color verde para INCOMING, rojo para OUTGOING
            val amountColor = if (m.isIncoming) {
                android.graphics.Color.parseColor("#00D39C")  // Verde Nequi
            } else {
                android.graphics.Color.parseColor("#e6335a")  // Rojo Nequi
            }
            tvAmount.setTextColor(amountColor)
        }
    }

    companion object {
        private const val VIEW_HEADER = 1
        private const val VIEW_EMPTY = 2
        private const val VIEW_ITEM = 3
    }
}
