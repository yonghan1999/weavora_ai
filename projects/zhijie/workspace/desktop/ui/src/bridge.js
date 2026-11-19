export class CoreBridge {
  constructor(invoke = globalThis.__TAURI__?.core?.invoke) {
    if (typeof invoke !== "function") throw new Error("TAURI_UNAVAILABLE");
    this.invoke = invoke;
  }

  login(email, password, deviceId) {
    return this.invoke("session_login", { email, password, deviceId });
  }

  groups() {
    return this.invoke("group_list");
  }

  rooms(groupId) {
    return this.invoke("room_list", { groupId });
  }

  join(roomId, deviceId, password, operationId) {
    return this.invoke("activity_join", {
      roomId,
      deviceId,
      password,
      operationId,
    });
  }

  logout() {
    return this.invoke("session_logout");
  }

  connectVoice(activityId) {
    return this.invoke("voice_connect", { activityId });
  }

  setMuted(muted) {
    return this.invoke("voice_set_mute", { muted });
  }

  setReceiveEnabled(enabled) {
    return this.invoke("voice_set_receive", { enabled });
  }

  setVolume(volume, memberId = null) {
    return this.invoke("voice_set_volume", { volume, memberId });
  }

  selectAudioDevice(deviceId, output = false) {
    return this.invoke("audio_select", { deviceId, output });
  }

  audioDevices() {
    return this.invoke("audio_devices");
  }

  testMicrophone() {
    return this.invoke("audio_test_microphone");
  }

  testOutput() {
    return this.invoke("audio_test_output");
  }

  voiceSnapshot() {
    return this.invoke("voice_snapshot");
  }

  connectNetwork() { return this.invoke("network_connect"); }

  networkSnapshot() { return this.invoke("network_snapshot"); }

  snapshot() {
    return this.invoke("state_snapshot");
  }

  onProductChanged(listener) {
    return globalThis.__TAURI__?.event?.listen("product-changed", event => listener(event.payload));
  }

  request(resource, id = null, body = null, targetId = null) {
    return this.invoke("product_request", { resource, id, body, ...(targetId === null ? {} : { targetId }) });
  }

  leave() {
    return this.invoke("activity_leave");
  }
}

export class ProductionStore extends EventTarget {
  constructor() {
    super();
    this.state = {
      userId: null,
      activity: null,
      service: "DISCONNECTED",
      lastErrorCode: null,
      voice: null,
    };
  }

  apply(snapshot) {
    if (!snapshot || typeof snapshot !== "object")
      throw new Error("INVALID_SERVER_RESPONSE");
    this.state = { ...snapshot, voice: this.state.voice };
    this.dispatchEvent(new Event("changed"));
  }

  applyVoice(snapshot) {
    if (!snapshot || typeof snapshot !== "object")
      throw new Error("INVALID_SERVER_RESPONSE");
    this.state = { ...this.state, voice: snapshot };
    this.dispatchEvent(new Event("changed"));
  }
}
