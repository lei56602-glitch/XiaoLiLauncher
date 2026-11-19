package com.xiaoli.transfer.api

import kotlinx.coroutines.flow.Flow

interface DeviceDiscovery {
    val devices: Flow<List<DeviceInfo>>
    fun start()
    fun stop()
}

interface TransferManager {
    fun observeTasks(): Flow<List<TransferTask>>
    suspend fun send(files: List<TransferFile>, device: DeviceInfo): String
    fun cancel(taskId: String)
}

interface ReceiveManager {
    val incomingRequests: Flow<List<IncomingRequest>>
    fun startListening()
    fun stopListening()
    fun respond(requestId: String, accept: Boolean)
}

interface TransferHistory {
    val records: Flow<List<TransferTask>>
    suspend fun clear()
}

/**
 * Implemented by the Android host and injected into the feature.
 * The UI never creates sockets, NSD listeners, or foreground services.
 */
data class QuanLianRuntime(
    val discovery: DeviceDiscovery,
    val transfers: TransferManager,
    val receiver: ReceiveManager,
    val history: TransferHistory
)
