import { Agent, request } from "node:http";

export class LoadHttpTransport {
  #agent = new Agent({ keepAlive: true });

  send(address, { method, headers, body, signal }) {
    return new Promise((accept, reject) => {
      const outgoing = request(address, { method, headers, signal, agent: this.#agent }, response => {
        const chunks = [];
        response.on("data", chunk => chunks.push(chunk));
        response.on("error", reject);
        response.on("end", () => accept({ status: response.statusCode, text: Buffer.concat(chunks).toString("utf8") }));
      });
      outgoing.on("error", reject);
      outgoing.end(body);
    });
  }

  close() { this.#agent.destroy(); }
}
