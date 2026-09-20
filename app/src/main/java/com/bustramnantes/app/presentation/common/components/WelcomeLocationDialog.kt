package com.bustramnantes.app.presentation.common.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * One-time first-launch welcome dialog that explains the (optional) location permission *before* the
 * system prompt appears, so the user actually understands what they are being asked before deciding.
 *
 * The user reads the explanation and drives the flow with its buttons:
 *  - **Autoriser** launches the system permission request; its grant/deny result (which fires even on
 *    the silent auto-deny after repeated refusals) resolves the welcome via [onChoiceMade].
 *  - **Plus tard** (and a back-press / outside-tap) declines without prompting and also resolves via
 *    [onChoiceMade] - the permission can still be granted later from the "À proximité" tab or the map.
 *
 * [onChoiceMade] lets the caller persist "seen" and dismiss this dialog forever. The dialog does *not*
 * auto-launch the OS prompt on appearance - doing so made the explanatory text flash past before the
 * user could read it, effectively showing the raw system prompt with no context.
 */
@Composable
fun WelcomeLocationDialog(onChoiceMade: () -> Unit) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Fires for both grant and deny (and even the silent auto-deny after repeated refusals), so
        // the welcome is always resolved after exactly one prompt.
        onChoiceMade()
    }

    AlertDialog(
        onDismissRequest = onChoiceMade,
        icon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
        title = { Text("Bienvenue") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Autorisez la localisation pour afficher les arrêts les plus proches de vous " +
                        "et votre position sur la carte.",
                )
                Text("C'est optionnel : vous pouvez refuser, l'application fonctionne aussi sans.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }) {
                Text("Autoriser")
            }
        },
        dismissButton = {
            TextButton(onClick = onChoiceMade) {
                Text("Plus tard")
            }
        },
    )
}
