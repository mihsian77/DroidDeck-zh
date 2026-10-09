package com.droiddeck.launcher.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.MirrorHub

/**
 * DroidDeck-zh: a network speed-test page for the Setup screen. Probes GitHub direct and the
 * top MirrorHub nodes, measures real download speed over a 256 KiB window, and shows which
 * source the downloader will prefer. Reads only; it never changes the node list.
 */
@Composable
internal fun SpeedTestPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var directSpeed by remember { mutableStateOf(-1L) }          // bytes/sec, -1 = not tested
    var nodeResults by remember { mutableStateOf<List<NodeSpeed>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    // The same file the runtime downloader uses, so the test reflects a real 754 MB fetch.
    val target = remember {
        "https://github.com/mihsian77/DroidDeck-zh/releases/download/data-packages/linuxfs.tar.zst"
    }

    val runTest: () -> Unit = {
        running = true
        error = null
        directSpeed = -1
        nodeResults = emptyList()
        // Fire the probes from a background thread and hand the results back on the main thread.
        Thread {
            val appCtx = ctx.applicationContext
            val direct = MirrorHub.measureSpeed(appCtx, target)
            val nodes = MirrorHub.getNodes(appCtx).take(3).map { node ->
                NodeSpeed(node.name, node.domain, MirrorHub.measureSpeed(appCtx, "https://" + node.domain + "/" + target))
            }
            Handler(Looper.getMainLooper()).post {
                directSpeed = direct
                nodeResults = nodes
                running = false
            }
        }.start()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header with Back + title.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BackLink(stringResource(R.string.mode_back), compact = true, onClick = onBack)
            Text(
                stringResource(R.string.speed_test_title),
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }
        Lede(stringResource(R.string.speed_test_lede))
        Text(
            stringResource(R.string.mirrorhub_attribution),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        PrimaryButton(
            if (running) stringResource(R.string.speed_test_running) else stringResource(R.string.speed_test_start),
            enabled = !running,
            main = true,
            onClick = runTest,
        )
        error?.let {
            Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
        }
        if (directSpeed >= 0 || nodeResults.isNotEmpty()) {
            if (directSpeed >= 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.speed_test_direct),
                        fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        speedLabel(directSpeed, ctx),
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.speed_test_nodes),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            if (nodeResults.isEmpty()) {
                Text(
                    stringResource(R.string.speed_test_none),
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                nodeResults.forEach { node ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            node.name,
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (node.speedBytesPerSec > 0) speedLabel(node.speedBytesPerSec, ctx)
                            else stringResource(R.string.speed_test_failed),
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            val best = bestSource(directSpeed, nodeResults)
            if (best != null) {
                Text(
                    stringResource(R.string.speed_test_best) + " · " + best,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private data class NodeSpeed(val name: String, val domain: String, val speedBytesPerSec: Long)

private fun speedLabel(bytesPerSec: Long, ctx: Context): String {
    if (bytesPerSec <= 0) return ctx.getString(R.string.speed_test_failed)
    val mbps = bytesPerSec / 1_048_576.0
    return if (mbps >= 1) ctx.getString(R.string.speed_test_mbps, String.format("%.2f", mbps))
    else ctx.getString(R.string.speed_test_kbps, (bytesPerSec / 1024).coerceAtLeast(1))
}

private fun bestSource(direct: Long, nodes: List<NodeSpeed>): String? {
    if (direct <= 0 && nodes.none { it.speedBytesPerSec > 0 }) return null
    val bestNode = nodes.maxByOrNull { it.speedBytesPerSec }
    return if (bestNode != null && bestNode.speedBytesPerSec > direct) bestNode.name
    else "GitHub"
}
