#!/usr/bin/env python3
"""Regenerates the open source license notices shown in Settings > About.

Run it after any dependency change (Go module bump, rebuilt engine AAR, new or updated
Gradle dependency). From the repo root, with Go on PATH and the engine AAR built:

    python tools/update_licenses.py

What it does:
  * Engine: reads the Go modules actually linked into engine/build/ttlvpn.aar
    (`go version -m` on libgojni.so) and copies each one's license file verbatim from
    the Go module cache, plus the Go standard library's LICENSE + PATENTS.
  * Android: checks the release runtime classpath against the ANDROID table below and
    fails if a library group isn't covered, so a new dependency can't slip through.
  * Writes the texts to android/app/src/main/res/raw/license_*.txt (identical texts
    are stored once) and the component list to OpenSourceLicenses.kt.

It fails loudly on anything it can't classify, instead of guessing.
"""
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AAR = os.path.join(ROOT, 'engine', 'build', 'ttlvpn.aar')
ANDROID_DIR = os.path.join(ROOT, 'android')
RAW = os.path.join(ANDROID_DIR, 'app', 'src', 'main', 'res', 'raw')
KOTLIN_OUT = os.path.join(ANDROID_DIR, 'app', 'src', 'main', 'java', 'com', 'malikfikret', 'ttlvpn', 'ui',
                          'OpenSourceLicenses.kt')

OWN_MODULE = 'github.com/MalikFikret/ttl-vpn/engine'
APP = ('TTL VPN', '© 2026 Malik Fikret')

# Display name and copyright holder, for modules whose license file names neither
# (Apache-2.0 texts usually carry no copyright line). Everything else is read from
# the license file itself.
GO_OVERRIDES = {
    'github.com/xjasonlyu/tun2socks/v2': ('tun2socks', None),
    'gvisor.dev/gvisor': ('gVisor', 'Copyright The gVisor Authors'),
    'github.com/google/btree': ('google/btree', 'Copyright Google Inc.'),
    'github.com/google/shlex': ('google/shlex', 'Copyright Google Inc.'),
}
# Shown first, in this order; the rest follow alphabetically.
GO_FIRST = ['github.com/xjasonlyu/tun2socks/v2', 'gvisor.dev/gvisor', 'std']

# Android libraries: (Gradle group prefix, display name, SPDX id, copyright, detail).
# Every group on the release runtime classpath must match a prefix here.
ANDROID = [
    ('androidx.', 'AndroidX / Jetpack', 'Apache-2.0', 'Copyright The Android Open Source Project',
     'Activity, Compose (Runtime, UI, Foundation, Animation, Material 3), Core, DataStore, Lifecycle, '
     'Navigation Event, SavedState, Startup, Profile Installer, Tracing, Window and their support libraries'),
    ('org.jetbrains.kotlin', 'Kotlin standard library', 'Apache-2.0',
     'Copyright JetBrains s.r.o. and Kotlin Programming Language contributors', None),
    ('org.jetbrains.kotlinx:kotlinx-coroutines', 'kotlinx.coroutines', 'Apache-2.0',
     'Copyright JetBrains s.r.o. and contributors', None),
    ('org.jetbrains.kotlinx:kotlinx-serialization', 'kotlinx.serialization', 'Apache-2.0',
     'Copyright JetBrains s.r.o. and contributors', None),
    ('com.squareup.okio', 'Okio', 'Apache-2.0', 'Copyright Square, Inc.', None),
    ('org.jetbrains:annotations', 'JetBrains Java Annotations', 'Apache-2.0', 'Copyright JetBrains s.r.o.', None),
    ('org.jspecify', 'JSpecify', 'Apache-2.0', 'Copyright The JSpecify Authors', None),
    ('com.google.guava', 'Guava ListenableFuture', 'Apache-2.0', 'Copyright The Guava Authors', None),
]
# The canonical Apache-2.0 text for the Android libraries (identical to apache.org's).
APACHE_SOURCE = 'github.com/google/btree'


def run(cmd, cwd=None):
    return subprocess.run(cmd, cwd=cwd, check=True, capture_output=True, text=True).stdout


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read().replace('\r\n', '\n').strip() + '\n'


def classify(text, where):
    if 'GNU GENERAL PUBLIC LICENSE' in text and 'Version 3' in text:
        return 'GPL-3.0'
    if 'Apache License' in text and 'Version 2.0' in text:
        return 'Apache-2.0'
    if 'Permission is hereby granted, free of charge' in text:
        return 'MIT'
    if 'Redistribution and use in source and binary forms' in text:
        return 'BSD-3-Clause' if 'Neither the name' in text else 'BSD-2-Clause'
    sys.exit(f'Unrecognized license text: {where}')


def copyright_line(text):
    for line in text.splitlines():
        line = line.strip()
        # Skip the Apache appendix template ("Copyright [yyyy] [name of copyright owner]").
        if line.startswith('Copyright') and '[yyyy]' not in line:
            return line
    return None


def linked_go_modules():
    if not os.path.exists(AAR):
        sys.exit(f'Engine AAR not found: {AAR} (build the engine first)')
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(AAR) as z:
            libs = [n for n in z.namelist() if n.endswith('libgojni.so')]
            if not libs:
                sys.exit('libgojni.so not found in the AAR')
            path = z.extract(libs[0], tmp)
        out = run(['go', 'version', '-m', path])
    modules = []
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[0] == 'dep':
            modules.append((parts[1], parts[2]))
    return [(m, v) for m, v in modules if m != OWN_MODULE]


def module_dir(modcache, module, version):
    # The module cache escapes upper-case letters as "!" + lower-case.
    escaped = re.sub(r'[A-Z]', lambda m: '!' + m.group(0).lower(), module)
    return os.path.join(modcache, *escaped.split('/')[:-1], escaped.split('/')[-1] + '@' + version)


def license_file(directory):
    for name in ('LICENSE', 'LICENSE.txt', 'LICENSE.md', 'COPYING', 'LICENCE'):
        path = os.path.join(directory, name)
        if os.path.exists(path):
            return path
    sys.exit(f'No license file in {directory}')


def with_patents(text, directory):
    patents = os.path.join(directory, 'PATENTS')
    return text + '\n' + read(patents) if os.path.exists(patents) else text


def android_groups():
    gradlew = os.path.join(ANDROID_DIR, 'gradlew.bat' if os.name == 'nt' else 'gradlew')
    out = run([gradlew, ':app:dependencies', '--configuration', 'releaseRuntimeClasspath', '-q', '--console=plain'],
              cwd=ANDROID_DIR)
    coords = set(re.findall(r'([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):', out))
    return sorted(f'{g}:{a}' for g, a in coords if not a.endswith('-bom'))


def main():
    modcache = run(['go', 'env', 'GOMODCACHE']).strip()
    goroot = run(['go', 'env', 'GOROOT']).strip()

    components = []  # (section, name, spdx, copyright, detail, text)

    app_text = read(os.path.join(ROOT, 'LICENSE'))
    components.append(('App', APP[0], classify(app_text, 'LICENSE'), APP[1], None, app_text))

    go = {}
    std_text = with_patents(read(os.path.join(goroot, 'LICENSE')), goroot)
    go['std'] = ('Go standard library and runtime', classify(std_text, 'GOROOT'), copyright_line(std_text), std_text)
    for module, version in linked_go_modules():
        directory = module_dir(modcache, module, version)
        if not os.path.isdir(directory):
            sys.exit(f'Module not in cache: {module}@{version} (run "go mod download" in engine/)')
        text = with_patents(read(license_file(directory)), directory)
        name, holder = GO_OVERRIDES.get(module, (module, None))
        holder = copyright_line(text) or holder
        if not holder:
            sys.exit(f'No copyright line for {module}; add it to GO_OVERRIDES')
        go[module] = (name, classify(text, module), holder, text)

    order = [m for m in GO_FIRST if m in go] + sorted(m for m in go if m not in GO_FIRST)
    for m in order:
        name, spdx, holder, text = go[m]
        components.append(('Engine', name, spdx, holder, None, text))

    groups = android_groups()
    uncovered = [g for g in groups if not any(g.startswith(prefix) for prefix, *_ in ANDROID)]
    if uncovered:
        sys.exit('Android libraries not covered by the ANDROID table:\n  ' + '\n  '.join(uncovered))
    apache_text = read(license_file(module_dir(modcache, APACHE_SOURCE, dict(linked_go_modules())[APACHE_SOURCE])))
    for prefix, name, spdx, holder, detail in ANDROID:
        if any(g.startswith(prefix) for g in groups):  # Only what actually ships
            components.append(('Android', name, spdx, holder, detail, apache_text))

    # Texts, deduplicated by content.
    os.makedirs(RAW, exist_ok=True)
    for old in os.listdir(RAW):
        if old.startswith('license_') and old.endswith('.txt'):
            os.remove(os.path.join(RAW, old))
    raw_names = {}
    for _, _, spdx, _, _, text in components:
        digest = hashlib.sha1(text.encode('utf-8')).hexdigest()[:8]
        if digest not in raw_names:
            raw_names[digest] = 'license_' + re.sub(r'[^a-z0-9]+', '_', spdx.lower()) + '_' + digest
            with open(os.path.join(RAW, raw_names[digest] + '.txt'), 'w', encoding='utf-8', newline='\n') as f:
                f.write(text)

    def kt(s):
        return 'null' if s is None else '"' + s.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$') + '"'

    lines = [
        '// GENERATED by tools/update_licenses.py. Do not edit by hand: re-run the script after',
        '// any dependency change (see CLAUDE.md).',
        'package com.malikfikret.ttlvpn.ui',
        '',
        'import com.malikfikret.ttlvpn.R',
        '',
        'internal enum class LicenseSection { App, Engine, Android }',
        '',
        'internal data class OssComponent(',
        '    val section: LicenseSection,',
        '    val name: String,',
        '    val license: String, // SPDX identifier',
        '    val copyright: String,',
        '    val detail: String?,',
        '    val text: Int, // R.raw resource with the full license text, verbatim (English)',
        ')',
        '',
        'internal val OSS_COMPONENTS = listOf(',
    ]
    for section, name, spdx, holder, detail, text in components:
        raw = raw_names[hashlib.sha1(text.encode('utf-8')).hexdigest()[:8]]
        lines.append(f'    OssComponent(LicenseSection.{section}, {kt(name)}, {kt(spdx)}, {kt(holder)}, {kt(detail)}, '
                     f'R.raw.{raw}),')
    lines.append(')')
    with open(KOTLIN_OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')

    print(f'{len(components)} components, {len(raw_names)} distinct license texts')
    for section, name, spdx, holder, *_ in components:
        print(f'  [{section}] {name}: {spdx} ({holder})')


if __name__ == '__main__':
    main()
