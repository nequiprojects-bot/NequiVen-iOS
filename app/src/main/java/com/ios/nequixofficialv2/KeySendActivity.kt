package com.ios.nequixofficialv2

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.ios.nequixofficialv2.databinding.ActivityKeySendBinding
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import com.ios.nequixofficialv2.utils.AndroidCompatibilityHelper

class KeySendActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityKeySendBinding
    private var userPhone: String = ""
    private var isFormattingAmount = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeySendBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Aplicar barra de estado morada para mantener consistencia visual
        AndroidCompatibilityHelper.applyNequiStatusBar(this)
        
        // ✅ Aplicar SOLO padding bottom para respetar barra de navegación inferior
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, navBars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        
        // Obtener el teléfono del usuario si se pasó como extra
        userPhone = intent.getStringExtra("user_phone") ?: ""
        
        setupUI()
        setupListeners()
    }
    
    private fun setupUI() {
        // Configurar el estado inicial del botón continuar
        updateContinueButton()
    }
    
    private fun setupListeners() {
        // Botón de regreso
        binding.backButton.setOnClickListener {
            finish()
        }
        
        // TextWatcher para el campo de llave
        binding.keyEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateContinueButton()
                // Mostrar/ocultar label
                binding.keyLabel.isVisible = !s.isNullOrEmpty()
            }
        })
        
        // TextWatcher para el campo de monto con formato idéntico a Nequi
        binding.amountEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormattingAmount) return
                val raw = s?.toString().orEmpty()
                val digits = raw.filter { it.isDigit() }
                if (digits.isEmpty()) {
                    // Vaciar por completo si no hay números (sin símbolo)
                    isFormattingAmount = true
                    binding.amountEditText.setText("")
                    binding.amountEditText.setSelection(0)
                    isFormattingAmount = false
                    updateContinueButton()
                    // Mostrar/ocultar label
                    binding.amountLabel.isVisible = false
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
                    binding.amountEditText.setText(formatted)
                    binding.amountEditText.setSelection(formatted.length)
                    isFormattingAmount = false
                }
                updateContinueButton()
                // Mostrar/ocultar label
                binding.amountLabel.isVisible = !s.isNullOrEmpty()
            }
        })
        
        // TextWatcher para el campo de mensaje
        binding.messageEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Mostrar/ocultar label
                binding.messageLabel.isVisible = !s.isNullOrEmpty()
            }
        })
        
        // Botón de agregar contacto
        binding.addContactIcon.setOnClickListener {
            // TODO: Implementar selección de contactos
        }
        
        // Botón de disponible (seleccionar fuente de dinero)
        binding.availableButton.setOnClickListener {
            // TODO: Implementar selección de fuente de dinero
        }
        
        // Botón continuar
        binding.continueButton.setOnClickListener {
            if (isFormValid()) {
                processKeySend()
            }
        }
    }
    
    private fun updateContinueButton() {
        val isValid = isFormValid()
        binding.continueButton.isEnabled = isValid
    }
    
    private fun isFormValid(): Boolean {
        val key = binding.keyEditText.text.toString().trim()
        val amountText = binding.amountEditText.text.toString().trim()
        
        // Extraer solo los dígitos del monto formateado ($ 1.000 -> 1000)
        val amountDigits = amountText.filter { it.isDigit() }
        val amountValue = amountDigits.toLongOrNull() ?: 0L
        
        return key.isNotEmpty() && amountValue > 0L
    }
    
    private fun processKeySend() {
        // Mostrar loading
        binding.loadingScreen.isVisible = true
        
        val key = binding.keyEditText.text.toString().trim()
        val amount = binding.amountEditText.text.toString().trim()
        val message = binding.messageEditText.text.toString().trim()
        
        // TODO: Implementar lógica de envío por llaves Bre-B
        // Por ahora solo simular proceso
        binding.root.postDelayed({
            binding.loadingScreen.isVisible = false
            // TODO: Navegar a pantalla de confirmación o resultado
            finish()
        }, 2000)
    }
}
