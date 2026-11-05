import { cpSync, mkdirSync } from "node:fs";

mkdirSync("dist", { recursive: true });
cpSync("public", "dist", { recursive: true });
cpSync("src", "dist/src", { recursive: true });
console.log("Desktop UI built into dist/.");
