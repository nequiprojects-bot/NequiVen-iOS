package com.ios.nequixofficialv2.security

import android.content.Context
import org.json.JSONObject
import java.io.InputStream

/**
 * Gestor de assets ofuscados.
 * 
 * Los assets están renombrados con nombres ofuscados (baseline.prof, etc.)
 * Esta clase mantiene el mapeo y permite acceder a ellos por su nombre original.
 */
object AssetObfuscator {

    // Mapeo de nombres originales a nombres ofuscados
    // Este mapeo será ofuscado por ProGuard en release
    private val assetMap = mutableMapOf<String, String>()
    private var initialized = false

    /**
     * Inicializa el mapeo de assets ofuscados
     */
    private fun initialize(context: Context) {
        if (initialized) return
        
        try {
            // Leer el mapeo desde el archivo ofuscado (en raíz de assets/)
            val mappingData = context.assets.open("baseline.profm").readBytes()
            
            // Desofuscar (XOR con 0x42)
            val deobfuscated = String(mappingData.map { (it.toInt() xor 0x42).toByte() }.toByteArray())
            
            // Parsear JSON
            val json = JSONObject(deobfuscated)
            
            // Construir mapeo inverso (original → ofuscado)
            json.keys().forEach { obfuscatedName ->
                val info = json.getJSONObject(obfuscatedName)
                val originalName = info.getString("original")
                assetMap[originalName] = obfuscatedName
            }
            
            android.util.Log.d("AssetObfuscator", "Mapeo cargado exitosamente: ${assetMap.size} assets")
            initialized = true
        } catch (e: Exception) {
            android.util.Log.w("AssetObfuscator", "Error cargando baseline.profm en Android ${android.os.Build.VERSION.SDK_INT}: ${e.message}")
            // Si falla, usar mapeo hardcodeado como fallback
            initializeFallback()
        }
    }

    /**
     * Mapeo hardcodeado como fallback (será ofuscado por ProGuard)
     * NOTA: Estos son ejemplos, el mapeo real está en baseline.profm
     */
    private fun initializeFallback() {
        // Fallback vacío - el mapeo real se lee desde baseline.profm
        // Si llegas aquí, hay un problema con el archivo de mapeo
        android.util.Log.e("AssetObfuscator", "⚠️ Usando fallback vacío - verifica baseline.profm")
        initialized = true
    }

    /**
     * Abre un asset usando su nombre original
     */
    fun openAsset(context: Context, originalName: String): InputStream {
        initialize(context)
        
        val obfuscatedName = assetMap[originalName]
        
        return if (obfuscatedName != null) {
            // Abrir asset ofuscado
            context.assets.open(obfuscatedName)
        } else {
            // Fallback: intentar abrir con nombre original
            context.assets.open(originalName)
        }
    }

    /**
     * Lee un asset de texto usando su nombre original
     */
    fun readTextAsset(context: Context, originalName: String): String {
        return openAsset(context, originalName).bufferedReader().use { it.readText() }
    }

    /**
     * Lee un asset binario usando su nombre original
     */
    fun readBinaryAsset(context: Context, originalName: String): ByteArray {
        return openAsset(context, originalName).readBytes()
    }

    /**
     * Verifica si un asset existe
     */
    fun assetExists(context: Context, originalName: String): Boolean {
        initialize(context)
        
        val obfuscatedName = assetMap[originalName] ?: originalName
        
        return try {
            context.assets.open(obfuscatedName).close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
