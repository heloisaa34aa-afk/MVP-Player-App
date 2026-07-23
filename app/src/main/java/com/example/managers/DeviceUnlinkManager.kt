package com.example.managers

import android.util.Log
import com.example.data.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceUnlinkManager(private val repository: AppRepository) {
    suspend fun unlinkDevice(tvId: String?, onComplete: () -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                if (tvId != null) {
                    repository.updateHeartbeat(tvId, "Offline")
                }
                
                // limpar banco Room, limpar cache de mídia, remover token
                repository.clearAllData()
                
            } catch (e: Exception) {
                Log.e("DeviceUnlinkManager", "Error during unlinking", e)
            }
            
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }
}
