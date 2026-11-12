import { readFile } from "node:fs/promises";

const configPath = new URL("../wrangler.toml", import.meta.url);
let source;
try {
  source = await readFile(configPath, "utf8");
} catch (error) {
  console.error("Missing worker/codespace-waker/wrangler.toml. Copy wrangler.toml.example and set CODESPACE_NAME before deploying.");
  process.exit(1);
}

const match = source.match(/^\s*CODESPACE_NAME\s*=\s*"([^"]+)"\s*$/m);
const codespaceName = match?.[1]?.trim() || "";
const registryEnabled = /^\s*MULTI_CODESPACE_REGISTRY\s*=\s*"(?:1|true|yes)"\s*$/mi.test(source);
const kvBound = /^\s*binding\s*=\s*"WAKER_KV"\s*$/m.test(source);
if (registryEnabled && kvBound) {
  console.log("Deploy target confirmed: multi-Codespace registry in WAKER_KV");
} else {
  if (!codespaceName || codespaceName === "YOUR_CODESPACE_NAME") {
    console.error("wrangler.toml needs a real CODESPACE_NAME, or MULTI_CODESPACE_REGISTRY=true with a WAKER_KV binding.");
    process.exit(1);
  }
  if (!/^[a-z0-9][a-z0-9-]*$/i.test(codespaceName)) {
    console.error(`Invalid CODESPACE_NAME in wrangler.toml: ${codespaceName}`);
    process.exit(1);
  }
  console.log(`Deploy target confirmed: ${codespaceName}`);
}
