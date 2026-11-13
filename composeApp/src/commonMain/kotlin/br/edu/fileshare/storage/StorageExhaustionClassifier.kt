package br.edu.fileshare.storage

import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException

/**
 * Classifica falhas de I/O de armazenamento e provê helpers uniformes para cota e overflow (`TASK-SEC-003`).
 */
internal object StorageExhaustionClassifier {

    private val SPACE_ERROR_TOKENS = listOf(
        "no space",
        "space left",
        "not enough space",
        "enospc",
        "disk full",
        "edquot",
        "quota",
        "insufficient storage",
        "espaco insuficiente",
        "sem espaco",
        "disco cheio",
    )

    fun isOutOfSpaceThrowable(throwable: Throwable): Boolean {
        return generateSequence(throwable) { it.cause }.any { current ->
            val message = current.message?.lowercase().orEmpty()
            SPACE_ERROR_TOKENS.any { token -> token in message }
        }
    }

    fun mapWriteOrCommitFailure(
        throwable: Throwable,
        fallbackErrorCode: FsapErrorCode,
        transferId: String,
        fileId: String,
        actionDescription: String,
    ): FsapProtocolException {
        if (throwable is FsapProtocolException) return throwable
        val errorCode = if (isOutOfSpaceThrowable(throwable)) {
            FsapErrorCode.INSUFFICIENT_STORAGE
        } else {
            fallbackErrorCode
        }
        return FsapProtocolException(
            errorCode = errorCode,
            message = "$actionDescription para '$fileId': ${throwable.message ?: throwable::class.simpleName}",
            transferId = transferId,
            fileId = fileId,
            cause = throwable,
        )
    }

    fun saturatingAdd(a: Long, b: Long): Long {
        if (a <= 0L) return b.coerceAtLeast(0L)
        if (b <= 0L) return a
        return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }
}
