package com.iosvm.emu.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iosvm.emu.setup.SetupPipeline
import com.iosvm.emu.setup.SetupRequest
import com.iosvm.emu.vm.QemuService

@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    val progress by SetupPipeline.progress.collectAsStateWithLifecycle()
    var ipsw by remember { mutableStateOf<Uri?>(null) }
    var url by remember { mutableStateOf("") }
    var keys by remember { mutableStateOf<Uri?>(null) }
    var sepRom by remember { mutableStateOf<Uri?>(null) }

    val pickIpsw = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { ipsw = it }
    val pickKeys = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { keys = it }
    val pickSep = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { sepRom = it }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("First-time setup")
        Text("Provide an iPhone12,1 IPSW you are entitled to use, plus your own firmware keys (JSON) and " +
            "SEP ROM. Nothing is bundled or downloaded for you except the helper VM.")
        OutlinedButton(onClick = { pickIpsw.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
            Text(ipsw?.lastPathSegment ?: "Pick IPSW file")
        }
        OutlinedTextField(url, { url = it }, label = { Text("…or IPSW https:// URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { pickKeys.launch(arrayOf("application/json", "*/*")) }, Modifier.fillMaxWidth()) {
            Text(keys?.lastPathSegment ?: "Pick keys.json")
        }
        OutlinedButton(onClick = { pickSep.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
            Text(sepRom?.lastPathSegment ?: "Pick SEP ROM")
        }
        Button(
            enabled = keys != null && sepRom != null && (ipsw != null || url.startsWith("https://")),
            onClick = {
                SetupPipeline.pending = SetupRequest(ipsw, url.takeIf { it.startsWith("https://") }, keys!!, sepRom!!)
                QemuService.send(ctx, QemuService.ACTION_SETUP)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start setup") }

        if (progress.stage != "idle") Text("${progress.stage}: ${progress.detail}")
        progress.error?.let { Text("Error: $it") }
    }
}
