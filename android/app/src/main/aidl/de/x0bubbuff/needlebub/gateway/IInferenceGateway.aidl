package de.x0bubbuff.needlebub.gateway;

import de.x0bubbuff.needlebub.gateway.CapabilityInfo;
import de.x0bubbuff.needlebub.gateway.InferenceRequest;
import de.x0bubbuff.needlebub.gateway.IInferenceCallback;

interface IInferenceGateway {
    List<CapabilityInfo> listCapabilities();
    oneway void infer(in InferenceRequest request, IInferenceCallback callback);
    void cancel(String requestId);
}
