import { closeSync, mkdirSync, openSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

export function reserveValidationOutput(output) {
  mkdirSync(dirname(output), { recursive: true });
  const paths = [output, `${output}.stdout.log`, `${output}.stderr.log`];
  const descriptors = [];
  try {
    for (const path of paths) descriptors.push(openSync(path, "wx"));
  } catch (error) {
    descriptors.forEach((descriptor, index) => {
      closeSync(descriptor);
      unlinkSync(paths[index]);
    });
    if (error.code === "EEXIST") throw new Error(`记录已存在，未启动适配器。请更换 --output 路径：${error.path}`, { cause: error });
    throw error;
  }
  return {
    evidence: paths.slice(1),
    writeLogs(stdout, stderr) {
      writeFileSync(descriptors[1], stdout ?? "");
      writeFileSync(descriptors[2], stderr ?? "");
    },
    writeReport(result) {
      writeFileSync(descriptors[0], `${JSON.stringify(result, null, 2)}\n`);
    },
    close() { descriptors.forEach(descriptor => closeSync(descriptor)); },
  };
}
