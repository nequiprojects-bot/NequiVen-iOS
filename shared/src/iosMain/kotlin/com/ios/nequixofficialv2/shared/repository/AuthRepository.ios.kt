package com.ios.nequixofficialv2.shared.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Implementación iOS del repositorio de autenticación
 * 
 * NOTA: Esta es una implementación placeholder.
 * Para usar Firebase en iOS, necesitas configurar Firebase iOS SDK
 * y usar las APIs nativas de iOS a través de expect/actual
 */
class IOSAuthRepository : AuthRepository {
    override suspend fun loginWithPhone(phoneNumber: String): Result<String> {
        // TODO: Implementar con Firebase iOS SDK
        // Por ahora retorna un ID ficticio para pruebas
        return Result.success("verification-id-ios-placeholder")
    }
    
    override suspend fun verifyCode(verificationId: String, code: String): Result<User> {
        // TODO: Implementar con Firebase iOS SDK
        // Por ahora retorna un usuario ficticio para pruebas
        return Result.success(
            User(
                id = "user-ios-test",
                phoneNumber = "3001234567",
                name = "Usuario iOS",
                balance = 0.0,
                disponible = 0.0
            )
        )
    }
    
    override fun getCurrentUser(): Flow<User?> {
        // TODO: Implementar con Firebase iOS SDK
        // Por ahora retorna null
        return flowOf(null)
    }
    
    override suspend fun logout(): Result<Unit> {
        // TODO: Implementar con Firebase iOS SDK
        return Result.success(Unit)
    }
}

/**
 * Función actual para obtener el repositorio en iOS
 */
actual fun getAuthRepository(): AuthRepository {
    return IOSAuthRepository()
}
