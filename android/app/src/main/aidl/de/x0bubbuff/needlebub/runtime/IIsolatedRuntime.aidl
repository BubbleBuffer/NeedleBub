package de.x0bubbuff.needlebub.runtime;

import android.os.ParcelFileDescriptor;
import de.x0bubbuff.needlebub.runtime.RuntimeRequest;
import de.x0bubbuff.needlebub.runtime.IRuntimeCallback;

interface IIsolatedRuntime {
    oneway void infer(in RuntimeRequest request, in ParcelFileDescriptor model, String toolsJson, IRuntimeCallback callback);
    // One way on purpose: a frozen runtime must never block the broker's watchdog while
    // it asks for cancellation. The broker recycles the generation regardless.
    oneway void cancel(String requestId);
    void reset();
}
