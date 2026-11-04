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

  join(roomId, deviceId, password, operationId) {
    return this.invoke("activity_join", { roomId, deviceId, password, operationId });
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

  selectAudioDevice(index, output = false) {
    return this.invoke("audio_select", { index, output });
  }

  voiceSnapshot() {
    return this.invoke("voice_snapshot");
  }

  snapshot() {
    return this.invoke("state_snapshot");
  }
}

export class ProductionStore extends EventTarget {
  constructor() {
    super();
    this.state = { userId: null, activity: null, service: "DISCONNECTED", lastErrorCode: null, voice: null };
  }

  apply(snapshot) {
    if (!snapshot || typeof snapshot !== "object") throw new Error("INVALID_SERVER_RESPONSE");
    this.state = snapshot;
    this.dispatchEvent(new Event("changed"));
  }

  applyVoice(snapshot) {
    if (!snapshot || typeof snapshot !== "object") throw new Error("INVALID_SERVER_RESPONSE");
    this.state = { ...this.state, voice: snapshot };
    this.dispatchEvent(new Event("changed"));
  }
}
