export class LoadBodyTraffic {
  samples = [];

  add(elapsedMs, field, bytes) {
    const second = Math.floor(elapsedMs / 1000);
    const sample = this.samples[second] ??= { second, requestBodyBytes: 0, responseBodyBytes: 0, syncBodyBytes: 0 };
    sample[field] += bytes;
  }

  rows(durationMs) {
    return Array.from({ length: Math.ceil(durationMs / 1000) }, (_, second) =>
      this.samples[second] ?? { second, requestBodyBytes: 0, responseBodyBytes: 0, syncBodyBytes: 0 });
  }
}
