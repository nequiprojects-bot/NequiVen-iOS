package io.scanbot.demo.barcodescanner

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ios.nequixofficialv2.R
import io.scanbot.demo.barcodescanner.adapter.SectionedMovementAdapter

class MovementsActivity : AppCompatActivity() {
    private var recyclerView: RecyclerView? = null
    private var movementAdapter: SectionedMovementAdapter? = null
    private var textViewNoMovements: TextView? = null
    // No progress bar/back in this layout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_movements)

        // ✅ Aplicar solo barra de estado morada - respetar barra de navegación del usuario
        try {
            window.statusBarColor = android.graphics.Color.parseColor("#200020")
            // NO tocar navigationBarColor - respetar configuración del usuario
        } catch (_: Exception) {}

        // ✅ Aplicar SOLO padding bottom para respetar barra de navegación inferior
        val rootView = findViewById<android.view.View>(R.id.rootMovements)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, navBars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }

        findViewById<Toolbar?>(R.id.toolbar)?.let { setSupportActionBar(it) }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(false)

        recyclerView = findViewById(R.id.recyclerViewMovements)
        textViewNoMovements = findViewById(R.id.textViewNoMovements)

        recyclerView?.layoutManager = LinearLayoutManager(this)
        movementAdapter = SectionedMovementAdapter()
        recyclerView?.adapter = movementAdapter


        loadMovements()
    }

    private fun loadMovements() {
        recyclerView?.visibility = View.GONE
        textViewNoMovements?.visibility = View.GONE

        e.j(this) { movements ->
            if (movements.isEmpty()) {
                textViewNoMovements?.visibility = View.VISIBLE
                recyclerView?.visibility = View.GONE
            } else {
                textViewNoMovements?.visibility = View.GONE
                recyclerView?.visibility = View.VISIBLE
                movementAdapter?.a0(movements)
            }
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finish()
            true
        } else super.onOptionsItemSelected(item)
    }
}
