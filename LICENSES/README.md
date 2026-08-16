# Third-party licenses

The root `LICENSE` covers this project's original and modified source. Files
under `tools/` are third-party build tools and are not relicensed by it.

- `Apache-2.0.txt`: Android Open Source Project components, including AAPT2,
  zipalign, apksigner and Android framework code; also applies to Conscrypt
  bundled in `apksigner.jar`.
- `R8-LICENSE.txt`: the exact license inventory embedded by upstream in this
  copy of `d8.jar`; it includes R8's BSD 3-Clause terms and the licenses of
  bundled libraries.
- `EPL-2.0.txt`: Eclipse Compiler for Java (`ecj-3.45.0.jar`).
- `zlib.txt`: zlib 1.2.13 embedded in the Android Build Tools executables.
- `Bun-LICENSE.md`: Bun's upstream license and complete linked-library and
  embedded-polyfill inventory. Bun itself is MIT; its statically linked
  JavaScriptCore/WebKit portions are LGPL-2 licensed.
- `LGPL-2.1-only.txt`: the LGPL 2.1 license text for JavaScriptCore/WebKit and
  other LGPL-2.1 components identified by Bun upstream.
- `WebKit-JavaScriptCore-COPYING.LIB`: the exact license file from the
  WebKit/JavaScriptCore commit pinned by this Bun build.
- `musl-LICENSE.txt`: musl libc's complete upstream license and bundled
  third-party notices, applicable to `libld-musl.so`.
- `MIT.txt`, `BSD-3-Clause.txt`, and
  `Apache-2.0-WITH-LLVM-exception.txt`: common license texts used by Bun's
  linked third-party components.

Google's packaged Android SDK artifacts are also distributed subject to the
[Android Software Development Kit License Agreement](https://developer.android.com/studio/terms).
See the root `NOTICE` for exact versions, artifact sources and hashes.
