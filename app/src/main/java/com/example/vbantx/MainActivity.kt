package com.example.vbantx

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { AppScreen() } } }
    }
}

private val RATES = listOf(8000, 16000, 22050, 44100, 48000)

@Composable
private fun AppScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("vban", Context.MODE_PRIVATE) }
    var tab by remember { mutableStateOf(prefs.getInt("tab", 0)) }
    Column {
        TabRow(selectedTabIndex = tab) {
            listOf("Send", "Receive").forEachIndexed { i, title ->
                Tab(tab == i, { tab = i; prefs.edit().putInt("tab", i).apply() }, text = { Text(title) })
            }
        }
        if (tab == 0) TransmitterScreen() else ReceiverScreen()
    }
}

@Composable
private fun ReceiverScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("vban", Context.MODE_PRIVATE) }
    val status by ReceiveState.status.collectAsStateWithLifecycle()
    var stream by remember { mutableStateOf(prefs.getString("rx_stream", "") ?: "") }
    var port by remember { mutableStateOf(prefs.getString("rx_port", Vban.DEFAULT_PORT.toString()) ?: "") }
    var bufferMs by remember { mutableFloatStateOf(prefs.getFloat("rx_buffer", 20f)) }
    var error by remember { mutableStateOf<String?>(null) }

    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun onStart() {
        error = null
        val p = port.toIntOrNull()
        if (p == null || p !in 1..65535) { error = "Port must be 1–65535"; return }
        prefs.edit().putString("rx_stream", stream).putString("rx_port", port).putFloat("rx_buffer", bufferMs).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val i = Intent(ctx, ReceiveService::class.java).apply {
            action = ReceiveService.ACTION_START
            putExtra(ReceiveService.EXTRA_STREAM, stream.trim())
            putExtra(ReceiveService.EXTRA_PORT, p)
            putExtra(ReceiveService.EXTRA_BUFFER_MS, bufferMs.toInt())
        }
        ContextCompat.startForegroundService(ctx, i)
    }

    fun onStop() {
        ctx.startService(Intent(ctx, ReceiveService::class.java).setAction(ReceiveService.ACTION_STOP))
    }

    val locked = status.running
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("VBAN Receiver", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Plays a VBAN stream sent to this phone (from Voicemeeter, the Sotto hub or another app). " +
                "Route the stream to this phone's IP on the hub, or send to it directly.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                stream, { stream = it.take(Vban.STREAM_NAME_LEN) }, Modifier.weight(2f), enabled = !locked,
                singleLine = true, label = { Text("Stream name (empty = any)") },
            )
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) }, Modifier.weight(1f), enabled = !locked,
                singleLine = true, label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Text("Buffer: ${bufferMs.toInt()} ms (lower = less delay, higher = fewer dropouts on Wi-Fi)")
        Slider(bufferMs, { bufferMs = it }, valueRange = 5f..100f, enabled = !locked)

        Button(onClick = { if (locked) onStop() else onStart() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (locked) "Stop receiving" else "Start receiving")
        }
        if (locked) {
            Text(
                if (status.packets == 0L) "Waiting for a stream on UDP ${port}…"
                else "${status.streamRate} Hz · ${status.packets} packets · lost ${status.lost} " +
                    "(repaired ${status.repaired}) · underruns ${status.underruns} · buffer ${"%.1f".format(status.latencyMs)} ms",
            )
        }
        (error ?: status.message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun TransmitterScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("vban", Context.MODE_PRIVATE) }
    val status by StreamState.status.collectAsStateWithLifecycle()

    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var port by remember { mutableStateOf(prefs.getString("port", Vban.DEFAULT_PORT.toString()) ?: "") }
    var stream by remember { mutableStateOf(prefs.getString("stream", "Stream1") ?: "Stream1") }
    val deviceAudioSupported = Build.VERSION.SDK_INT >= 29
    var source by remember {
        val default = if (deviceAudioSupported) Source.MEDIA else Source.MIC
        mutableStateOf(Source.parse(prefs.getString("source", null), default).let {
            if (it.usesProjection && !deviceAudioSupported) Source.MIC else it
        })
    }
    var rate by remember { mutableStateOf(prefs.getInt("rate", 48000)) }
    var channels by remember { mutableStateOf(prefs.getInt("channels", 2)) }
    var gain by remember { mutableFloatStateOf(prefs.getFloat("gain", 1f)) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<DiscoveryResult?>(null) }
    val scope = rememberCoroutineScope()

    fun scan() {
        scanning = true
        found = null
        scope.launch {
            try {
                found = VbanDiscovery.discover(ctx)
            } catch (e: Exception) {
                error = "Scan failed: ${e.message}"
            } finally {
                scanning = false
            }
        }
    }

    fun save() = prefs.edit()
        .putString("host", host).putString("port", port).putString("stream", stream)
        .putString("source", source.name).putInt("rate", rate).putInt("channels", channels)
        .putFloat("gain", gain).apply()

    fun startService(projResult: Int = 0, projData: Intent? = null) {
        val i = Intent(ctx, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_START
            putExtra(AudioStreamService.EXTRA_HOST, host.trim())
            putExtra(AudioStreamService.EXTRA_PORT, port.toInt())
            putExtra(AudioStreamService.EXTRA_STREAM_NAME, stream)
            putExtra(AudioStreamService.EXTRA_SOURCE, source.name)
            putExtra(AudioStreamService.EXTRA_SAMPLE_RATE, rate)
            putExtra(AudioStreamService.EXTRA_CHANNELS, channels)
            putExtra(AudioStreamService.EXTRA_GAIN, gain)
            putExtra(AudioStreamService.EXTRA_PROJECTION_RESULT, projResult)
            putExtra(AudioStreamService.EXTRA_PROJECTION_DATA, projData)
        }
        ContextCompat.startForegroundService(ctx, i)
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK && r.data != null) {
            startService(r.resultCode, r.data)
        } else {
            error = "Screen-capture permission was denied"
        }
    }

    fun launch() {
        if (source.usesProjection) {
            val mgr = ctx.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } else {
            startService()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) launch() else error = "Microphone permission is required"
    }

    fun onStart() {
        error = null
        val p = port.toIntOrNull()
        when {
            host.isBlank() -> error = "Enter the receiver IP or hostname"
            p == null || p !in 1..65535 -> error = "Port must be 1–65535"
            else -> {
                save()
                val needed = buildList {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (needed.isEmpty()) launch() else permissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    fun onStop() {
        ctx.startService(Intent(ctx, AudioStreamService::class.java).setAction(AudioStreamService.ACTION_STOP))
    }

    val locked = status.running
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("VBAN Transmitter", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            host, { host = it }, Modifier.fillMaxWidth(), enabled = !locked, singleLine = true,
            label = { Text("Receiver IP / hostname") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) }, Modifier.weight(1f), enabled = !locked,
                singleLine = true, label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                stream, { stream = it.take(Vban.STREAM_NAME_LEN) }, Modifier.weight(2f),
                enabled = !locked, singleLine = true, label = { Text("Stream name") },
            )
        }

        OutlinedButton(onClick = { scan() }, enabled = !locked && !scanning, modifier = Modifier.fillMaxWidth()) {
            Text(if (scanning) "Scanning network…" else "Find receivers on network")
        }
        found?.let { r ->
            if (r.receivers.isEmpty() && r.activeStreams.isEmpty()) {
                Text(
                    "Nothing answered. The receiver must be on the same Wi-Fi, have VBAN enabled and allow UDP " +
                        "${Vban.DEFAULT_PORT}; some receivers don't reply to pings, so enter the IP manually.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            r.receivers.forEach { rx ->
                OutlinedButton(
                    onClick = { host = rx.ip; port = rx.port.toString() },
                    enabled = !locked, modifier = Modifier.fillMaxWidth(),
                ) {
                    val tags = (rx.roles + if (rx.isHub) listOf("Hub") else emptyList()).joinToString(", ")
                    Text("${rx.label}  (${rx.ip}:${rx.port})" + if (tags.isNotEmpty()) "  [$tags]" else "")
                }
            }
            if (r.activeStreams.isNotEmpty()) {
                Text("Active streams on the network (tap to use the name)", style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    r.activeStreams.forEach { s ->
                        val fmt = if (s.sampleRate > 0) " · ${s.sampleRate / 1000.0}k ${if (s.channels == 1) "mono" else "${s.channels}ch"}" else ""
                        FilterChip(
                            stream == s.name, { stream = s.name.take(Vban.STREAM_NAME_LEN) },
                            { Text("${s.name}  from ${s.fromIp}$fmt") }, enabled = !locked,
                        )
                    }
                }
            }
        }

        Text("Source")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                source == Source.MEDIA, { source = Source.MEDIA },
                { Text("Media audio") }, enabled = !locked && deviceAudioSupported,
            )
            FilterChip(
                source == Source.ALL_DEVICE, { source = Source.ALL_DEVICE },
                { Text("All device audio") }, enabled = !locked && deviceAudioSupported,
            )
            FilterChip(source == Source.MIC, { source = Source.MIC }, { Text("Microphone") }, enabled = !locked)
        }
        Text(
            when (source) {
                Source.MEDIA -> "Music, video and other media. Still plays on the phone."
                Source.ALL_DEVICE -> "Media, games and any other sound Android allows apps to capture. Still plays on the phone."
                Source.MIC -> "Sound picked up by the phone's microphone."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Sample rate")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RATES.forEach { r ->
                FilterChip(rate == r, { rate = r }, { Text("${r / 1000.0}".removeSuffix(".0") + "k") }, enabled = !locked)
            }
        }

        Text("Channels")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(channels == 1, { channels = 1 }, { Text("Mono") }, enabled = !locked)
            FilterChip(channels == 2, { channels = 2 }, { Text("Stereo") }, enabled = !locked)
        }

        Text("Gain: ${"%.1f".format(gain)}×")
        Slider(gain, { gain = it }, valueRange = 0.25f..4f, enabled = !locked)

        Button(
            onClick = { if (locked) onStop() else onStart() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (locked) "Stop streaming" else "Start streaming") }

        if (locked) {
            LinearProgressIndicator(progress = { status.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("Sent ${status.packets} packets, ${status.errors} send errors")
        }
        (error ?: status.message)?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
