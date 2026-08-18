#!/usr/bin/env bun

import { chmodSync, existsSync } from "node:fs";
import { basename, dirname, extname, isAbsolute, resolve } from "node:path";
import { createInterface } from "node:readline/promises";

const rootDir = import.meta.dirname;

function fail(message) {
  console.error(`minapk: ${message}`);
  process.exit(1);
}

function parseArguments(argv) {
  const result = { elf: null, configPath: null };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--config") {
      result.configPath = argv[index + 1];
      if (!result.configPath) fail("--config requires a path to a replacement package.json file");
      index += 1;
    } else if (argument.startsWith("--config=")) {
      result.configPath = argument.slice("--config=".length);
      if (!result.configPath) fail("--config= requires a path to a replacement package.json file");
    } else if (!result.elf && !argument.startsWith("--")) {
      result.elf = argument;
    }
  }
  return result;
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

// Mirrors build.sh's own payload acquisition, so `--config` produces the same
// buninu.tgz build.sh would have produced on its own, before we append to it.
async function ensureBuninuPayload() {
  const tgzPath = resolve(rootDir, "buninu.tgz");
  const noBackupInit = resolve(rootDir, "no_backup", "bin", "init.js");

  if (existsSync(noBackupInit)) {
    console.error(`minapk: exporting Buninu payload from local no_backup: ${tgzPath}`);
    const child = Bun.spawn([process.execPath, noBackupInit, "--export", tgzPath], {
      cwd: rootDir,
      env: { ...process.env },
      stdin: "inherit",
      stdout: "inherit",
      stderr: "inherit",
    });
    if (await child.exited !== 0) fail("Local Buninu export failed");
  } else {
    const rl = createInterface({ input: process.stdin, output: process.stderr });
    let answer;
    try {
      answer = await rl.question("Local no_backup not found. Run npx buninu@latest --export? [y/N] ");
    } finally {
      rl.close();
    }
    if (!/^[yY]/.test(answer.trim())) fail("Cancelled: Buninu payload was not exported");

    const child = Bun.spawn(["npx", "buninu@latest", "--export", tgzPath], {
      cwd: rootDir,
      env: { ...process.env },
      stdin: "inherit",
      stdout: "inherit",
      stderr: "inherit",
    });
    if (await child.exited !== 0) fail("npm Buninu export failed");
  }

  if (!existsSync(tgzPath)) fail(`Buninu export did not create: ${tgzPath}`);
  return tgzPath;
}

// Appends a second <top-level dir>/package.json entry to the tail of the tar
// stream instead of rebuilding the archive: Bun.Archive's object-map API only
// exposes regular file contents (no symlinks, no directories, no unix mode),
// so reconstructing the whole archive through it would silently drop every
// symlink and reset every executable bit. Decompress, trim the existing
// end-of-archive padding, concatenate a single-entry tar chunk (built with
// Bun.Archive so the ustar header itself is correct), and recompress. Every
// original entry stays as the exact original bytes.
//
// The new entry deliberately reuses package.json's path rather than adding a
// differently-named file: bin/init.js only ever reads "../package.json", so a
// same-named duplicate is what actually takes effect. tar extracts entries in
// order and later writes win, so the appended copy silently and completely
// replaces the original on extraction -- verified against real GNU tar, not
// just Bun's own reader.
async function appendConfigToPayload(tgzPath, configPath) {
  if (!existsSync(configPath)) fail(`--config file not found: ${configPath}`);

  const replacementPackageJson = await Bun.file(configPath).text();
  try {
    JSON.parse(replacementPackageJson);
  } catch (error) {
    fail(`--config file is not valid JSON: ${configPath} (${error.message})`);
  }

  const tar = Bun.gunzipSync(new Uint8Array(await Bun.file(tgzPath).arrayBuffer()));

  const existingFiles = await new Bun.Archive(tar).files();
  const firstEntry = existingFiles.keys().next().value;
  if (!firstEntry) fail(`Buninu payload has no entries: ${tgzPath}`);
  const topDir = firstEntry.split("/")[0];
  const packageJsonKey = `${topDir}/package.json`;

  let lastNonZero = -1;
  for (let index = tar.length - 1; index >= 0; index -= 1) {
    if (tar[index] !== 0) {
      lastNonZero = index;
      break;
    }
  }
  const contentEnd = Math.ceil((lastNonZero + 1) / 512) * 512;
  const trimmedTar = tar.subarray(0, contentEnd);

  const newEntry = new Bun.Archive({ [packageJsonKey]: replacementPackageJson });
  const newEntryBytes = await newEntry.bytes();

  const combined = new Uint8Array(Bun.concatArrayBuffers([trimmedTar, newEntryBytes]));
  await Bun.write(tgzPath, Bun.gzipSync(combined));
  console.error(`minapk: appended ${configPath} -> ${tgzPath} (${packageJsonKey})`);
}

const { elf: elfArgument, configPath } = parseArguments(process.argv.slice(2));
const elfPath = elfArgument ? await copyElfAsLibmain(elfArgument) : null;

const buildScript = resolve(rootDir, "build.sh");
if (!existsSync(buildScript)) fail(`build script not found: ${buildScript}`);

const buildArguments = [];
if (configPath) {
  const tgzPath = await ensureBuninuPayload();
  await appendConfigToPayload(tgzPath, configPath);
  buildArguments.push(tgzPath);
}

const child = Bun.spawn(["/bin/sh", buildScript, ...buildArguments], {
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
