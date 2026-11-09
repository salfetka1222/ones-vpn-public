package app.onesvpn

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A user-initiated Quick Settings toggle. Never bypass Android VPN consent. */
class OnesQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked == true) {
            unlockAndRun { openApp() }
            return
        }
        val status = OnesVpnService.state.value.status
        val selectedId = getSharedPreferences("ui", 0).getString("selected", null)
        val hasConsent = if (status in setOf(VpnStatus.DISCONNECTED, VpnStatus.ERROR) &&
            !selectedId.isNullOrBlank()) {
            runCatching { VpnService.prepare(this) == null }.getOrDefault(false)
        } else false

        when (QuickTilePolicy.decide(status, !selectedId.isNullOrBlank(), hasConsent)) {
            QuickTilePolicy.Action.OPEN_APP -> openApp()
            QuickTilePolicy.Action.WAIT -> Unit
            QuickTilePolicy.Action.DISCONNECT -> {
                try {
                    startService(Intent(this, OnesVpnService::class.java)
                        .setAction(OnesVpnService.ACTION_DISCONNECT))
                } catch (_: Exception) {
                    openApp()
                }
            }
            QuickTilePolicy.Action.CONNECT -> {
                try {
                    startForegroundService(Intent(this, OnesVpnService::class.java)
                        .setAction(OnesVpnService.ACTION_CONNECT)
                        .putExtra(OnesVpnService.EXTRA_PROFILE_ID, selectedId))
                } catch (_: Exception) {
                    openApp()
                }
            }
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val snapshot = OnesVpnService.state.value
        val status = snapshot.status
        tile.label = "ONES VPN"
        tile.subtitle = QuickTilePolicy.subtitle(snapshot, SystemClock.elapsedRealtime())
        tile.state = when (status) {
            VpnStatus.CONNECTED -> Tile.STATE_ACTIVE
            VpnStatus.DISCONNECTING -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    // The deprecated Intent overload is required only on API 29–33.
    // API 34+ always uses the required PendingIntent overload.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(this, 101, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestRefresh(context: android.content.Context) {
            try {
                requestListeningState(context,
                    ComponentName(context, OnesQuickTileService::class.java))
            } catch (_: RuntimeException) {
                // Not all Android launchers expose Quick Settings tiles.
            }
        }
    }
}
