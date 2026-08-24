package de.x0bubbuff.needlebub.macrodroid

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import de.x0bubbuff.needlebub.NeedleBubApplication
import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.gateway.OutputMapper
import de.x0bubbuff.needlebub.gateway.RateDecision
import java.util.UUID

class LocaleActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocaleProtocol.ACTION_FIRE_SETTING) return
        val pending = goAsync()
        val config = intent.getBundleExtra(LocaleProtocol.EXTRA_BUNDLE)
        val capabilityId = config?.getString(LocaleProtocol.KEY_CAPABILITY).orEmpty()
        val input = config?.getString(LocaleProtocol.KEY_INPUT).orEmpty()
        val app = context.applicationContext as NeedleBubApplication
        val rateDecision = app.macroLimiter.acquire(MACRO_RATE_KEY)
        if (rateDecision != RateDecision.ALLOWED) {
            finish(pending, mapOf("nb_matched" to "false", "nb_error_code" to if (rateDecision == RateDecision.BUSY) ErrorCodes.BUSY else ErrorCodes.RATE_LIMITED))
            return
        }
        val pack = app.packStore.findExternal(capabilityId)
        if (pack == null) {
            app.macroLimiter.finish(MACRO_RATE_KEY)
            finish(pending, mapOf("nb_matched" to "false", "nb_error_code" to ErrorCodes.PACK_NOT_FOUND))
            return
        }
        val requestId = "macro-${UUID.randomUUID()}"
        if (!app.runtime.infer(requestId, pack, input, MACRO_TIMEOUT_MS, surface = "macro") { response ->
            app.macroLimiter.finish(MACRO_RATE_KEY)
            val matched = response.status == "OK" && response.resultJson != null
            finish(pending, OutputMapper.map(pack.manifest, matched, response.toolName, response.resultJson, response.errorCode))
        }) {
            app.macroLimiter.finish(MACRO_RATE_KEY)
            finish(pending, mapOf("nb_matched" to "false", "nb_error_code" to ErrorCodes.BUSY))
        }
    }

    private fun finish(pending: PendingResult, values: Map<String, String>) {
        val extras = Bundle()
        val variables = Bundle()
        values.forEach { (name, value) ->
            extras.putString(name, value)
            extras.putString("%$name", value)
            variables.putString(name, value)
        }
        extras.putBundle(LocaleProtocol.EXTRA_VARIABLES, variables)
        pending.setResultCode(Activity.RESULT_OK)
        pending.setResultExtras(extras)
        pending.finish()
    }

    private companion object {
        const val MACRO_TIMEOUT_MS = 8_000L
        const val MACRO_RATE_KEY = -1
    }
}
