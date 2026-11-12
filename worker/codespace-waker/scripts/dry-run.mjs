import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const outDir = join(root, ".wrangler", "dry-run");
const configPath = join(outDir, "wrangler.toml");

mkdirSync(outDir, { recursive: true });
const exampleConfig = readFileSync(join(root, "wrangler.toml.example"), "utf8");
writeFileSync(configPath, exampleConfig.replace('main = "src/index.js"', 'main = "../../src/index.js"'));

const wrangler = join(
  root,
  "node_modules",
  ".bin",
  process.platform === "win32" ? "wrangler.cmd" : "wrangler"
);
const result = spawnSync(
  wrangler,
  ["deploy", "--dry-run", "--config", configPath, "--outdir", outDir],
  { cwd: root, stdio: "inherit", shell: process.platform === "win32" }
);

if (result.error) {
  console.error(result.error.message);
}
process.exit(result.status ?? 1);
