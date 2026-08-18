#!/usr/bin/env bun

import { chmodSync, existsSync } from "node:fs";
import { basename, extname, isAbsolute, resolve } from "node:path";
import { createInterface } from "node:readline/promises";

const rootDir = import.meta.dirname;

function fail(message) {
  console.error(`minapk: ${message}`);
  process.exit(1);
}

function parseArguments(argv) {
  const result = { elf: null, configPath: null, command: null, appName: null, pkgName: null };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--config") {
      result.configPath = argv[index + 1];
      if (!result.configPath) fail("--config requires a path to a replacement package.json file");
      index += 1;
    } else if (argument.startsWith("--config=")) {
      result.configPath = argument.slice("--config=".length);
      if (!result.configPath) fail("--config= requires a path to a replacement package.json file");
    } else if (argument === "-c" || argument === "--command") {
      result.command = argv[index + 1];
      if (result.command === undefined) fail("-c/--command requires a command string");
      index += 1;
    } else if (argument.startsWith("--command=")) {
      result.command = argument.slice("--command=".length);
    } else if (argument === "-n" || argument === "--appname") {
      result.appName = argv[index + 1];
      if (!result.appName) fail("-n/--appname requires a name");
      index += 1;
    } else if (argument.startsWith("--appname=")) {
      result.appName = argument.slice("--appname=".length);
      if (!result.appName) fail("--appname= requires a name");
    } else if (argument === "-p" || argument === "--pkgname") {
      result.pkgName = argv[index + 1];
      if (!result.pkgName) fail("-p/--pkgname requires a package name");
      index += 1;
    } else if (argument.startsWith("--pkgname=")) {
      result.pkgName = argument.slice("--pkgname=".length);
      if (!result.pkgName) fail("--pkgname= requires a package name");
    } else if (!result.elf && !argument.startsWith("-")) {
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

// appName is the resolved value build.sh actually built with (the -n
// override, or appname.txt's default) -- never re-read appname.txt here, it
// was never written to and may not match a -n override used for this run.
//
// Always targets the caller's cwd rather than rootDir: through real npx (not
// a local checkout), rootDir is npx's ephemeral package cache, not anywhere
// the user can find afterwards. Named after the elf (extension stripped) when
// one was given, otherwise after appName. Skipped when that destination is
// the same file as the build output (e.g. running from within rootDir with
// no elf), so exactly one copy of the apk ever exists per run.
async function copyApkToCwd(appName, elfPath) {
  const builtApk = resolve(rootDir, `${appName}.apk`);
  if (!existsSync(builtApk)) fail(`built apk not found: ${builtApk}`);

  const outputName = elfPath ? basename(elfPath, extname(elfPath)) : appName;
  const destination = resolve(process.cwd(), `${outputName}.apk`);
  if (destination === builtApk) return;

  await Bun.write(destination, Bun.file(builtApk));
  console.error(`minapk: copied ${builtApk} -> ${destination}`);
}

// Mirrors build.sh's own payload acquisition, so `--config`/`-c` produce the
// same buninu.tgz build.sh would have produced on its own, before we patch it.
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

// Builds the <top-level dir>/package.json entry to append, then writes it to
// the tail of the tar stream instead of rebuilding the archive: Bun.Archive's
// object-map API only exposes regular file contents (no symlinks, no
// directories, no unix mode), so reconstructing the whole archive through it
// would silently drop every symlink and reset every executable bit.
// Decompress, trim the existing end-of-archive padding, concatenate a
// single-entry tar chunk (built with Bun.Archive so the ustar header itself
// is correct), and recompress. Every original entry stays as the exact
// original bytes.
//
// The new entry deliberately reuses package.json's path rather than adding a
// differently-named file: bin/init.js only ever reads "../package.json", so a
// same-named duplicate is what actually takes effect. tar extracts entries in
// order and later writes win, so the appended copy silently and completely
// replaces the original on extraction -- verified against real GNU tar, not
// just Bun's own reader.
//
// The base package.json is `--config`'s file when given, otherwise the one
// already inside the payload (freshly exported above); `-c`/`--command`, when
// given, is then patched into that base's `buninu.command` before appending,
// so the two flags compose instead of being mutually exclusive.
async function applyPackagePatch(tgzPath, { configPath, command }) {
  const tar = Bun.gunzipSync(new Uint8Array(await Bun.file(tgzPath).arrayBuffer()));

  const existingFiles = await new Bun.Archive(tar).files();
  const firstEntry = existingFiles.keys().next().value;
  if (!firstEntry) fail(`Buninu payload has no entries: ${tgzPath}`);
  const topDir = firstEntry.split("/")[0];
  const packageJsonKey = `${topDir}/package.json`;

  let baseText;
  if (configPath) {
    if (!existsSync(configPath)) fail(`--config file not found: ${configPath}`);
    baseText = await Bun.file(configPath).text();
  } else {
    const original = existingFiles.get(packageJsonKey);
    if (!original) fail(`Buninu payload has no ${packageJsonKey}: ${tgzPath}`);
    baseText = await original.text();
  }

  let packageJsonText = baseText;
  if (command !== null) {
    let parsed;
    try {
      parsed = JSON.parse(baseText);
    } catch (error) {
      const source = configPath ? `--config file (${configPath})` : `${packageJsonKey} in payload`;
      fail(`${source} is not valid JSON: ${error.message}`);
    }
    parsed.buninu = { ...(parsed.buninu ?? {}), command };
    packageJsonText = `${JSON.stringify(parsed, null, 2)}\n`;
  } else if (configPath) {
    try {
      JSON.parse(baseText);
    } catch (error) {
      fail(`--config file is not valid JSON: ${configPath} (${error.message})`);
    }
  }

  let lastNonZero = -1;
  for (let index = tar.length - 1; index >= 0; index -= 1) {
    if (tar[index] !== 0) {
      lastNonZero = index;
      break;
    }
  }
  const contentEnd = Math.ceil((lastNonZero + 1) / 512) * 512;
  const trimmedTar = tar.subarray(0, contentEnd);

  const newEntry = new Bun.Archive({ [packageJsonKey]: packageJsonText });
  const newEntryBytes = await newEntry.bytes();

  const combined = new Uint8Array(Bun.concatArrayBuffers([trimmedTar, newEntryBytes]));
  await Bun.write(tgzPath, Bun.gzipSync(combined));
  console.error(
    `minapk: wrote ${packageJsonKey} in ${tgzPath}` +
      (configPath ? ` (base: ${configPath})` : " (base: payload's own package.json)") +
      (command !== null ? " [buninu.command patched]" : ""),
  );
}

const { elf: elfArgument, configPath, command, appName, pkgName } = parseArguments(process.argv.slice(2));
const elfPath = elfArgument ? await copyElfAsLibmain(elfArgument) : null;

const buildScript = resolve(rootDir, "build.sh");
if (!existsSync(buildScript)) fail(`build script not found: ${buildScript}`);

const buildArguments = [];
if (configPath || command !== null) {
  const tgzPath = await ensureBuninuPayload();
  await applyPackagePatch(tgzPath, { configPath, command });
  buildArguments.push(tgzPath);
}

// -n/-p are passed as env vars instead of being written into appname.txt/
// pkgname.txt: build.sh reads MINAPK_APPNAME/MINAPK_PKGNAME as overrides for
// this run only. Never mutating those checked-in files means a plain run
// without -n/-p always builds from the same predictable defaults, regardless
// of what any earlier invocation passed.
const childEnvironment = { ...process.env };
if (appName) childEnvironment.MINAPK_APPNAME = appName;
if (pkgName) childEnvironment.MINAPK_PKGNAME = pkgName;

const child = Bun.spawn(["/bin/sh", buildScript, ...buildArguments], {
  cwd: rootDir,
  env: childEnvironment,
  stdin: "inherit",
  stdout: "inherit",
  stderr: "inherit",
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => child.kill(signal));
}

const exitCode = await child.exited;
if (exitCode === 0) {
  const resolvedAppName = appName || (await Bun.file(resolve(rootDir, "appname.txt")).text()).replace(/[\r\n]/g, "");
  if (!resolvedAppName) fail("appname.txt is empty");
  await copyApkToCwd(resolvedAppName, elfPath);
}

process.exit(exitCode);
