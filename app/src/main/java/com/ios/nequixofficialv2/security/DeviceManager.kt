package com.ios.nequixofficialv2.security

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.*

/**
 * Manager para controlar el acceso de dispositivos.
 * Solo permite 1 dispositivo activo por cuenta.
 */
class DeviceManager(private val context: Context) {

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /**
     * Obtiene el ID único del dispositivo actual
     */
    fun getCurrentDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            // Fallback: generar ID único y guardarlo
            val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
            var deviceId = prefs.getString("device_id", null)
            if (deviceId == null) {
                deviceId = UUID.randomUUID().toString()
                prefs.edit().putString("device_id", deviceId).apply()
            }
            deviceId
        }
    }

    /**
     * Obtiene información del dispositivo actual
     */
    fun getCurrentDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            deviceId = getCurrentDeviceId(),
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            lastAccess = System.currentTimeMillis()
        )
    }

    /**
     * Verifica si el dispositivo actual tiene acceso autorizado
     * @return DeviceCheckResult con el estado de verificación
     */
    suspend fun checkDeviceAccess(userPhone: String): DeviceCheckResult {
        return try {
            val normalizedPhone = userPhone.filter { it.isDigit() }.let { 
                if (it.length > 10) it.takeLast(10) else it 
            }
            
            if (normalizedPhone.isBlank()) {
                return DeviceCheckResult.Error("Número de teléfono inválido")
            }

            val currentDeviceId = getCurrentDeviceId()
            val userDoc = db.collection("users").document(normalizedPhone).get().await()

            if (!userDoc.exists()) {
                return DeviceCheckResult.Error("Usuario no encontrado")
            }

            val registeredDeviceId = userDoc.getString("device_id")
            val registeredDeviceModel = userDoc.getString("device_model")

            // Si no hay dispositivo registrado, registrar el actual
            if (registeredDeviceId.isNullOrBlank()) {
                registerDevice(normalizedPhone)
                return DeviceCheckResult.Authorized
            }

            // Si el dispositivo registrado es el actual, autorizar
            if (registeredDeviceId == currentDeviceId) {
                // Actualizar última vez de acceso
                updateLastAccess(normalizedPhone)
                return DeviceCheckResult.Authorized
            }

            // Dispositivo diferente detectado
            return DeviceCheckResult.Blocked(
                registeredDeviceModel = registeredDeviceModel ?: "Dispositivo desconocido"
            )

        } catch (e: Exception) {
            android.util.Log.e("DeviceManager", "Error verificando dispositivo: ${e.message}", e)
            DeviceCheckResult.Error(e.message ?: "Error desconocido")
        }
    }

    /**
     * Registra el dispositivo actual en Firestore
     */
    suspend fun registerDevice(userPhone: String): Boolean {
        return try {
            val normalizedPhone = userPhone.filter { it.isDigit() }.let { 
                if (it.length > 10) it.takeLast(10) else it 
            }
            
            val deviceInfo = getCurrentDeviceInfo()
            
            db.collection("users").document(normalizedPhone)
                .update(
                    mapOf(
                        "device_id" to deviceInfo.deviceId,
                        "device_model" to deviceInfo.model,
                        "device_android_version" to deviceInfo.androidVersion,
                        "device_last_access" to deviceInfo.lastAccess
                    )
                ).await()
            
            android.util.Log.d("DeviceManager", "✅ Dispositivo registrado: ${deviceInfo.model}")
            true
        } catch (e: Exception) {
            android.util.Log.e("DeviceManager", "❌ Error registrando dispositivo: ${e.message}", e)
            false
        }
    }

    /**
     * Actualiza la última vez de acceso del dispositivo
     */
    private suspend fun updateLastAccess(userPhone: String) {
        try {
            val normalizedPhone = userPhone.filter { it.isDigit() }.let { 
                if (it.length > 10) it.takeLast(10) else it 
            }
            
            db.collection("users").document(normalizedPhone)
                .update("device_last_access", System.currentTimeMillis())
                .await()
        } catch (e: Exception) {
            android.util.Log.e("DeviceManager", "Error actualizando último acceso: ${e.message}")
        }
    }

    /**
     * Desvincula el dispositivo actual (para cambio de dispositivo)
     */
    suspend fun unlinkDevice(userPhone: String): Boolean {
        return try {
            val normalizedPhone = userPhone.filter { it.isDigit() }.let { 
                if (it.length > 10) it.takeLast(10) else it 
            }
            
            db.collection("users").document(normalizedPhone)
                .update(
                    mapOf(
                        "device_id" to null,
                        "device_model" to null,
                        "device_android_version" to null,
                        "device_last_access" to null
                    )
                ).await()
            
            android.util.Log.d("DeviceManager", "✅ Dispositivo desvinculado")
            true
        } catch (e: Exception) {
            android.util.Log.e("DeviceManager", "❌ Error desvinculando dispositivo: ${e.message}", e)
            false
        }
    }

    /**
     * Información del dispositivo
     */
    data class DeviceInfo(
        val deviceId: String,
        val model: String,
        val androidVersion: String,
        val lastAccess: Long
    )

    /**
     * Resultado de la verificación de dispositivo
     */
    sealed class DeviceCheckResult {
        object Authorized : DeviceCheckResult()
        data class Blocked(val registeredDeviceModel: String) : DeviceCheckResult()
        data class Error(val message: String) : DeviceCheckResult()
    }
}
