package com.ios.nequixofficialv2.shared.repository

import kotlinx.datetime.Clock

/**
 * Implementación iOS del repositorio de pagos
 * 
 * NOTA: Esta es una implementación placeholder.
 * Para usar Firebase en iOS, necesitas configurar Firebase iOS SDK
 */
class IOSPaymentRepository : PaymentRepository {
    override suspend fun sendMoney(
        recipientPhone: String,
        amount: Double,
        message: String?
    ): Result<Transaction> {
        // TODO: Implementar con Firebase iOS SDK
        return Result.success(
            Transaction(
                id = "tx-ios-${Clock.System.now().toEpochMilliseconds()}",
                type = TransactionType.SEND,
                amount = amount,
                recipientName = "Usuario iOS",
                recipientPhone = recipientPhone,
                date = Clock.System.now(),
                reference = "REF-${Clock.System.now().toEpochMilliseconds()}",
                message = message
            )
        )
    }
    
    override suspend fun getTransactions(userId: String): Result<List<Transaction>> {
        // TODO: Implementar con Firebase iOS SDK
        return Result.success(emptyList())
    }
    
    override suspend fun generateReceipt(transaction: Transaction): Result<ByteArray> {
        // TODO: Implementar generación de comprobantes en iOS
        // Por ahora retorna un array vacío
        return Result.success(ByteArray(0))
    }
}

/**
 * Función actual para obtener el repositorio en iOS
 */
actual fun getPaymentRepository(): PaymentRepository {
    return IOSPaymentRepository()
}
