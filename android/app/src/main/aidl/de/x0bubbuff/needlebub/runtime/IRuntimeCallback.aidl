package de.x0bubbuff.needlebub.runtime;

import de.x0bubbuff.needlebub.runtime.RuntimeResponse;

oneway interface IRuntimeCallback {
    void onResult(in RuntimeResponse response);
}
