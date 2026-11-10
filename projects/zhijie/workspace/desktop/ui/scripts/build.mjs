import { cpSync, mkdirSync, copyFileSync } from "node:fs";

mkdirSync("dist", { recursive: true });
cpSync("public", "dist", { recursive: true });
cpSync("src", "dist/src", { recursive: true });
copyFileSync("../../design/prototype/styles.css", "dist/styles.css");
copyFileSync("../../design/ui/ui.css", "dist/ui.css");
console.log("Desktop UI built into dist/.");
