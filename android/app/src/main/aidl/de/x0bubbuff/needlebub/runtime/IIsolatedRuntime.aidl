package de.x0bubbuff.needlebub.runtime;

import android.os.ParcelFileDescriptor;
import de.x0bubbuff.needlebub.runtime.RuntimeRequest;
import de.x0bubbuff.needlebub.runtime.IRuntimeCallback;

interface IIsolatedRuntime {
    oneway void infer(in RuntimeRequest request, in ParcelFileDescriptor model, String toolsJson, IRuntimeCallback callback);
    void cancel(String requestId);
    void reset();
}
