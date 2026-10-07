package com.chu.foldanim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** 재부팅/앱 업데이트 후 켜져 있던 상태라면 서비스를 다시 시작합니다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (FoldSettings.load(context).enabled && Settings.canDrawOverlays(context)) {
                    FoldAnimationService.start(context)
                }
            }
        }
    }
}
