#!/usr/bin/env bun

import { chmodSync, existsSync } from "node:fs";
import { basename, dirname, extname, isAbsolute, resolve } from "node:path";

const rootDir = import.meta.dirname;

function fail(message) {
  console.error(`minapk: ${message}`);
  process.exit(1);
}

async function copyElfAsLibmain(elfArgument) {
  const elfPath = isAbsolute(elfArgument) ? elfArgument : resolve(process.cwd(), elfArgument);
  if (!existsSync(elfPath)) fail(`elf not found: ${elfPath}`);

  const destination = resolve(rootDir, "libmain.so");
  await Bun.write(destination, Bun.file(elfPath));
  chmodSync(destination, 0o755);
  console.error(`minapk: copied ${elfPath} -> ${destination}`);
  return elfPath;
}

async function copyApkBesideElf(elfPath) {
  const appName = (await Bun.file(resolve(rootDir, "appname.txt")).text()).replace(/[\r\n]/g, "");
  if (!appName) fail("appname.txt is empty");

  const builtApk = resolve(rootDir, `${appName}.apk`);
  if (!existsSync(builtApk)) fail(`built apk not found: ${builtApk}`);

  const elfName = basename(elfPath, extname(elfPath));
  const destination = resolve(dirname(elfPath), `${elfName}.apk`);
  await Bun.write(destination, Bun.file(builtApk));
  console.error(`minapk: copied ${builtApk} -> ${destination}`);
}

const elfArgument = process.argv[2];
const elfPath = elfArgument ? await copyElfAsLibmain(elfArgument) : null;

const buildScript = resolve(rootDir, "build.sh");
if (!existsSync(buildScript)) fail(`build script not found: ${buildScript}`);

const child = Bun.spawn(["/bin/sh", buildScript], {
  cwd: rootDir,
  env: { ...process.env },
  stdin: "inherit",
  stdout: "inherit",
  stderr: "inherit",
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => child.kill(signal));
}

const exitCode = await child.exited;
if (exitCode === 0 && elfPath) await copyApkBesideElf(elfPath);

process.exit(exitCode);
