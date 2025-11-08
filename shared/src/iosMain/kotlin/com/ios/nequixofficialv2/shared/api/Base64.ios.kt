package com.ios.nequixofficialv2.shared.api

import platform.Foundation.NSData
import platform.Foundation.NSDataBase64DecodingOptions
import platform.Foundation.create
import kotlinx.cinterop.*
import platform.posix.memcpy

/**
 * Implementación iOS de decodificación base64
 */
@OptIn(ExperimentalForeignApi::class)
actual fun decodeBase64(input: String): ByteArray {
    val nsData = NSData.create(
        base64EncodedString = input, 
        options = NSDataBase64DecodingOptions.MIN_VALUE
    ) ?: throw IllegalArgumentException("Invalid base64 string")
    
    val length = nsData.length.toInt()
    val bytes = nsData.bytes ?: return ByteArray(0)
    
    return ByteArray(length).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length.toULong())
        }
    }
}
