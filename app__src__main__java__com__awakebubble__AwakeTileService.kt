package com.awakebubble

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class AwakeTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (!Settings.canDrawOverlays(this)) {
            openApp()
            return
        }
        val running = Prefs.bool(this, Prefs.RUNNING, false)
        BubbleService.send(this, if (running) BubbleService.ACT_TOGGLE else BubbleService.ACT_AWAKE)
    }

    private fun refresh() {
        val t = qsTile ?: return
        val run = Prefs.bool(this, Prefs.RUNNING, false)
        val aw = Prefs.bool(this, Prefs.AWAKE, false)
        t.state = if (run && aw) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "AwakeBubble"
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = if (!run) "বন্ধ" else if (aw) "Awake" else "Sleep"
        }
        t.updateTile()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
