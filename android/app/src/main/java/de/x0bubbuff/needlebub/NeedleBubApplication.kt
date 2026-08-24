package de.x0bubbuff.needlebub

import android.app.Application
import de.x0bubbuff.needlebub.packs.PackStore
import de.x0bubbuff.needlebub.runtime.RuntimeBroker
import de.x0bubbuff.needlebub.gateway.UidRateLimiter

class NeedleBubApplication : Application() {
    val packStore: PackStore by lazy { PackStore(this) }
    val runtime: RuntimeBroker by lazy { RuntimeBroker(this) }
    val macroLimiter: UidRateLimiter by lazy { UidRateLimiter() }
}
