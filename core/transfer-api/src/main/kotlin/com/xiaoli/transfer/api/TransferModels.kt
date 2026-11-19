package com.xiaoli.transfer.api

data class DeviceInfo(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val lastSeenEpochMillis: Long
)

data class TransferFile(
    val id: String,
    val displayName: String,
    val uri: String,
    val sizeBytes: Long,
    val mimeType: String? = null
)

enum class TransferDirection { SEND, RECEIVE }

enum class TransferState {
    WAITING,
    CONNECTING,
    WAITING_FOR_APPROVAL,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TransferTask(
    val id: String,
    val direction: TransferDirection,
    val deviceName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val transferredBytes: Long,
    val state: TransferState,
    val errorMessage: String? = null
)

data class IncomingRequest(
    val id: String,
    val deviceName: String,
    val fileNames: List<String>,
    val totalBytes: Long
)
