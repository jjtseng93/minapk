#!/usr/bin/env bun

import { basename, dirname, resolve } from "node:path";

const archivePath = resolve(process.argv[2] || resolve(import.meta.dir, "buninu.tgz"));
const archive = Bun.file(archivePath);

if (!await archive.exists()) {
  console.error(`Archive not found: ${archivePath}`);
  process.exit(1);
}

const bytes = new Uint8Array(await archive.arrayBuffer());
const sha256 = new Bun.CryptoHasher("sha256").update(bytes).digest("hex");
const mtime = Math.floor(archive.lastModified / 1000);
const stampPath = resolve(dirname(archivePath), basename(archivePath, ".tgz") + ".stamp");
const stamp = `${sha256}:${mtime}:${archive.size}\n`;

await Bun.write(stampPath, stamp);
console.log(stampPath);
console.log(stamp.trim());
