package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.data.repository.DeviceInfo
import com.example.ui.screens.AdminDialog

@Composable
fun HiddenMaintenancePanel(
    deviceInfo: DeviceInfo,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAdminMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        TripleTapDetector(
            onTripleTap = { showAdminMenu = true },
            modifier = Modifier.fillMaxSize()
        ) {
            // Opacity between 15% and 20%
            Icon(
                imageVector = Icons.Default.DesktopMac, // Used as placeholder for Vision Central logo
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showAdminMenu) {
            AdminDialog(
                deviceInfo = deviceInfo,
                onDisconnect = {
                    showAdminMenu = false
                    onDisconnect()
                },
                onRefresh = {
                    showAdminMenu = false
                    onRefresh()
                },
                onDismiss = { showAdminMenu = false }
            )
        }
    }
}
