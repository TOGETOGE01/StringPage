# Third-party notices

The application includes third-party components. Their licenses remain applicable; local changes are identified below.

## r8brain-free-src 1.6

Sample rate converter designed by Aleksey Vaneev of Voxengo.

- Copyright (c) 2013–2014 Aleksey Vaneev. MIT license.
- Pinned source: https://github.com/avaneev/r8brain-free-src/tree/ace65a520b4d135e3e8eddeee8dccdbc4366e8bb
- Vendored C++ source and per-file hashes: `app/src/main/cpp/r8brain/`.
- Local portability change: `CDSPSincFilterGen.h` stores window-function state in separate fields instead of MSVC anonymous union/struct aggregates. Filter calculations are unchanged.
- The original license is included in the source directory and `app/src/main/assets/licenses/r8brain-MIT.txt`.

The vendored `fft4g.h` wraps Takuya OOURA's FFT code, Copyright (C) 1996–2001 Takuya OOURA. Its original attribution is retained in the source and `app/src/main/assets/licenses/Ooura-FFT-NOTICE.txt`.

This is the independent source-rate conversion stage. It does not provide the remaining RSE effects, instruments, or commercial soundbanks.

## alphaTab 1.8.4

- Project: https://alphatab.net/
- Repository: https://github.com/CoderLine/alphaTab
- License: Mozilla Public License 2.0
- Corresponding source archive: https://repo.maven.apache.org/maven2/net/alphatab/alphaTab/1.8.4/alphaTab-1.8.4-sources.jar
- License text: https://www.mozilla.org/en-US/MPL/2.0/

The alphaTab attribution in rendered music is retained. No alphaTab library source files were modified in this project.

## Bravura music font

Copyright Steinberg Media Technologies GmbH. Licensed under the SIL Open Font License 1.1.

Source and license: https://github.com/steinbergmedia/bravura

## SONiVOX SoundFont

Copyright (c) 2004–2006 Sonic Network Inc. Licensed under Apache License 2.0.

The original alphaTab bank is used only by the optional A/B instrumentation tests, not the release app. Its copyright notice is retained in the dependency notices.

## GeneralUser GS SoftSynth 1.44

Copyright S. Christian Collins. This version's License v2.0 permits use in software projects and modification of the bank or its packaging. The complete original license, including the author's statement about historical sample provenance, is included in `app/src/main/assets/licenses/GeneralUser-GS-v1.44-LICENSE.txt` and is viewable in the app.

- Author: https://www.schristiancollins.com/generaluser.php
- Original engine sample repository, fixed commit: https://github.com/CoderLine/alphaSynth/tree/7514e992fb8bf9840067ad447a4932f885fe82e2/Samples/Data
- SoundFont SHA-256: `00725296486f652ba42bdbf2098254762ca80239353d04b55a27454fc8edb8a8`

The unmodified bank replaces the dependency's `sonivox.sf2` asset through Android's application asset overlay. The filename is required by alphaTab 1.8.4's default loader; the actual bank identifies itself as GeneralUser GS SoftSynth in its RIFF INFO data. This does not include or reproduce Guitar Pro's RSE engine, commercial soundbanks, pickup models or effects.

## AndroidX, Kotlin and kotlinx.coroutines

These components are distributed under the Apache License 2.0.

- https://source.android.com/docs/setup/about/licenses
- https://github.com/JetBrains/kotlin
- https://github.com/Kotlin/kotlinx.coroutines
- https://www.apache.org/licenses/LICENSE-2.0

## AlphaSkia

Native rendering dependency provided by alphaTab. Project and corresponding sources:

https://github.com/CoderLine/AlphaSkia

Licensed under BSD-3-Clause. The bundled `app/src/main/assets/licenses/AlphaSkia-BSD.txt` also contains the notices for Skia and other native components.

## Test material

The bundled Morning Strings exercise and embedded PCM/WAV audio were generated for this project. The optional upstream GP8/OGG compatibility fixture is downloaded separately for testing and is not shipped in the APK or source archive.
