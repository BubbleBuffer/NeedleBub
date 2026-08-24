package de.x0bubbuff.needlebub.gateway;

import de.x0bubbuff.needlebub.gateway.InferenceResponse;

oneway interface IInferenceCallback {
    void onResult(in InferenceResponse response);
}
