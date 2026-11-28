import { readFileSync } from "node:fs";
import { runPaseoBridge } from "./bridge.mjs";

const request = JSON.parse(readFileSync(0, "utf8"));
const result = await runPaseoBridge(request);
if (!result.ok) {
  process.exitCode = 1;
}
