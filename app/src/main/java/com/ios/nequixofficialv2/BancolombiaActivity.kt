package com.ios.nequixofficialv2

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import com.ios.nequixofficialv2.utils.AndroidCompatibilityHelper

class BancolombiaActivity : AppCompatActivity() {
    
    private var userPhone: String = ""
    private var isFormattingAmount = false
    
    // Views
    private lateinit var backButton: ImageView
    private lateinit var favoriteButton: ImageView
    private lateinit var moreButton: ImageView
    private lateinit var accountNumberEditText: EditText
    private lateinit var accountNumberLabel: TextView
    private lateinit var amountEditText: EditText
    private lateinit var amountLabel: TextView
    private lateinit var accountTypeContainer: androidx.cardview.widget.CardView
    private lateinit var availableButton: androidx.cardview.widget.CardView
    private lateinit var continueButton: androidx.appcompat.widget.AppCompatButton
    private lateinit var favoriteSwitch: Switch
    private lateinit var successMessage: androidx.cardview.widget.CardView
    private lateinit var accountTypeText: TextView
    private lateinit var accountTypeLabel: TextView
    
    private var selectedAccountType = "" // Sin valor inicial
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bancolombia_send)
        
        // Aplicar barra de estado morada para mantener consistencia visual
        AndroidCompatibilityHelper.applyNequiStatusBar(this)
        
        // Obtener el teléfono del usuario si se pasó como extra
        userPhone = intent.getStringExtra("user_phone") ?: ""
        
        initViews()
        setupUI()
        setupListeners()
    }
    
    private fun initViews() {
        backButton = findViewById(R.id.backButton)
        favoriteButton = findViewById(R.id.favoriteButton)
        moreButton = findViewById(R.id.moreButton)
        accountNumberEditText = findViewById(R.id.accountNumberEditText)
        accountNumberLabel = findViewById(R.id.accountNumberLabel)
        amountEditText = findViewById(R.id.amountEditText)
        amountLabel = findViewById(R.id.amountLabel)
        accountTypeContainer = findViewById(R.id.accountTypeContainer)
        availableButton = findViewById(R.id.availableButton)
        continueButton = findViewById(R.id.continueButton)
        favoriteSwitch = findViewById(R.id.favoriteSwitch)
        successMessage = findViewById(R.id.successMessage)
        accountTypeText = findViewById(R.id.accountTypeText)
        accountTypeLabel = findViewById(R.id.accountTypeLabel)
    }
    
    private fun setupUI() {
        // Ocultar mensaje de éxito inicialmente
        successMessage.isVisible = false
        
        // Configurar el estado inicial del botón continuar
        updateContinueButton()
    }
    
    private fun setupListeners() {
        // Botón de regreso
        backButton.setOnClickListener {
            finish()
        }
        
        // TextWatcher para el campo de número de cuenta
        accountNumberEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateContinueButton()
                // Mostrar/ocultar label
                accountNumberLabel.isVisible = !s.isNullOrEmpty()
            }
        })
        
        // TextWatcher para el campo de monto con formato idéntico a Nequi
        amountEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormattingAmount) return
                val raw = s?.toString().orEmpty()
                val digits = raw.filter { it.isDigit() }
                if (digits.isEmpty()) {
                    // Vaciar por completo si no hay números (sin símbolo)
                    isFormattingAmount = true
                    amountEditText.setText("")
                    amountEditText.setSelection(0)
                    isFormattingAmount = false
                    updateContinueButton()
                    // Mostrar/ocultar label
                    amountLabel.isVisible = false
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
                    amountEditText.setText(formatted)
                    amountEditText.setSelection(formatted.length)
                    isFormattingAmount = false
                }
                updateContinueButton()
                // Mostrar/ocultar label
                amountLabel.isVisible = !s.isNullOrEmpty()
            }
        })
        
        // Botón de tipo de cuenta
        accountTypeContainer.setOnClickListener {
            showAccountTypeBottomSheet()
        }
        
        // Botón de disponible (seleccionar fuente de dinero)
        availableButton.setOnClickListener {
            // TODO: Implementar selección de fuente de dinero
        }
        
        // Switch de favoritos
        favoriteSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Mostrar mensaje verde por 3 segundos cuando se enciende el switch
                showSuccessMessage()
            }
            // Cuando se apaga el switch, no hacer nada especial
            // El mensaje ya se oculta automáticamente después de 3 segundos
        }

        // Botón continuar
        continueButton.setOnClickListener {
            if (isFormValid()) {
                processBancolombiaTransfer()
            }
        }
        
        // Botones del toolbar
        favoriteButton.setOnClickListener {
            // TODO: Implementar favoritos
        }
        
        moreButton.setOnClickListener {
            // TODO: Implementar menú más opciones
        }
    }
    
    private fun updateContinueButton() {
        val isValid = isFormValid()
        continueButton.isEnabled = isValid
        
        // Cambiar el color del botón según el estado
        if (isValid) {
            continueButton.setBackgroundResource(R.drawable.button_rounded_pink_electric)
        } else {
            continueButton.setBackgroundResource(R.drawable.bg_button_pink_disabled)
        }
    }
    
    private fun isFormValid(): Boolean {
        val accountNumber = accountNumberEditText.text.toString().trim()
        val amountText = amountEditText.text.toString().trim()
        
        // Extraer solo los dígitos del monto formateado ($ 1.000 -> 1000)
        val amountDigits = amountText.filter { it.isDigit() }
        val amountValue = amountDigits.toLongOrNull() ?: 0L
        
        return accountNumber.isNotEmpty() && amountValue > 0L
    }
    
    private fun processBancolombiaTransfer() {
        val accountNumber = accountNumberEditText.text.toString().trim()
        val amount = amountEditText.text.toString().trim()
        val rememberFavorite = favoriteSwitch.isChecked
        
        // TODO: Implementar lógica de transferencia a Bancolombia
        // Por ahora solo simular proceso sin activar el switch automáticamente
        
        // Simular proceso y cerrar después de un tiempo
        continueButton.postDelayed({
            finish()
        }, 2000)
    }
    
    private fun showSuccessMessage() {
        // Mostrar el mensaje verde de éxito
        successMessage.isVisible = true
        
        // Siempre ocultar después de 3 segundos
        successMessage.postDelayed({
            successMessage.isVisible = false
        }, 3000)
    }
    
    private fun showAccountTypeBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_account_type, null)
        bottomSheetDialog.setContentView(bottomSheetView)
        
        // Configurar listeners para las opciones
        bottomSheetView.findViewById<View>(R.id.optionCorriente).setOnClickListener {
            selectedAccountType = "Corriente"
            accountTypeText.text = selectedAccountType
            accountTypeText.isVisible = true
            // Cambiar el label a rosa cuando se selecciona algo
            accountTypeLabel.setTextColor(getColor(R.color.nequi_pink))
            bottomSheetDialog.dismiss()
        }
        
        bottomSheetView.findViewById<View>(R.id.optionAhorros).setOnClickListener {
            selectedAccountType = "Ahorros"
            accountTypeText.text = selectedAccountType
            accountTypeText.isVisible = true
            // Cambiar el label a rosa cuando se selecciona algo
            accountTypeLabel.setTextColor(getColor(R.color.nequi_pink))
            bottomSheetDialog.dismiss()
        }
        
        bottomSheetDialog.show()
    }
}
