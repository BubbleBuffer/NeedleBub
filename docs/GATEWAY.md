# Android inference gateway

Bind explicitly to the exported service action
`de.x0bubbuff.needlebub.action.INFERENCE_GATEWAY`. The AIDL sources under
`android/app/src/main/aidl` are the public contract.

`IInferenceGateway` exposes `listCapabilities()`, asynchronous
`infer(request, callback)`, and `cancel(requestId)`. Requests contain a
caller-owned request ID, installed capability ID, input, and bounded timeout.
Responses contain status, match flag, tool name, raw result JSON, typed declared
outputs, pack/version, duration, and a stable error code.

No token or grant model is used. The service cannot return notification text,
selected-app configuration, or prior results. It enforces one in-flight request
per UID, burst three, ten requests per minute per UID, and a global queue of
eight items.

Stable errors: `NO_MATCH`, `PACK_NOT_FOUND`, `PACK_INVALID`,
`ENGINE_INCOMPATIBLE`, `INPUT_TOO_LARGE`, `BUSY`, `RATE_LIMITED`, `TIMEOUT`,
and `RUNTIME_CRASH`.
