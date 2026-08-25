package de.x0bubbuff.needlebub

import android.app.Application
import de.x0bubbuff.needlebub.packs.PackStore
import de.x0bubbuff.needlebub.runtime.RuntimeBroker
import de.x0bubbuff.needlebub.gateway.UidRateLimiter
import de.x0bubbuff.needlebub.developer.DeveloperDataSettings
import de.x0bubbuff.needlebub.developer.DeveloperDataStore

class NeedleBubApplication : Application() {
    val packStore: PackStore by lazy { PackStore(this) }
    val runtime: RuntimeBroker by lazy { RuntimeBroker(this) }
    val macroLimiter: UidRateLimiter by lazy { UidRateLimiter() }
    val developerDataSettings: DeveloperDataSettings by lazy { DeveloperDataSettings(this) }
    val developerDataStore: DeveloperDataStore by lazy { DeveloperDataStore(this) }
}
