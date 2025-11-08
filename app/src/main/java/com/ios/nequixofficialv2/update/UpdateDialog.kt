package com.ios.nequixofficialv2.update

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import androidx.core.view.isVisible
import com.ios.nequixofficialv2.databinding.DialogUpdateBinding

/**
 * Diálogo personalizado para mostrar información de actualización
 */
class UpdateDialog(
    private val context: Context,
    private val updateInfo: UpdateManager.UpdateInfo,
    private val onUpdateClick: () -> Unit,
    private val onLaterClick: () -> Unit
) {

    private var dialog: Dialog? = null

    fun show() {
        val binding = DialogUpdateBinding.inflate(LayoutInflater.from(context))
        
        // Configurar textos
        binding.tvUpdateTitle.text = updateInfo.title
        binding.tvUpdateMessage.text = "${updateInfo.message}\nVersión v${updateInfo.latestVersionName}"
        
        // Si es actualización obligatoria, ocultar botón "Más tarde"
        binding.tvLater.isVisible = !updateInfo.isMandatory
        
        // Configurar listeners
        binding.btnUpdate.setOnClickListener {
            onUpdateClick()
            if (!updateInfo.isMandatory) {
                dismiss()
            }
        }
        
        binding.tvLater.setOnClickListener {
            onLaterClick()
            dismiss()
        }
        
        // Crear y configurar diálogo
        dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(binding.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setCancelable(!updateInfo.isMandatory) // No cancelable si es obligatoria
            setCanceledOnTouchOutside(!updateInfo.isMandatory)
            
            // SOLUCIÓN: Configurar el ancho del diálogo
            window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        dialog?.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    fun isShowing(): Boolean {
        return dialog?.isShowing == true
    }
}
