#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Prepare a disposable IDE bundle for the native launcher experiment."""
import argparse
from pathlib import Path
import plistlib
import re
import shlex
import shutil
import subprocess


def prepare(source, destination, catalog, icon_name, deployment_target):
    source, destination, catalog = (p.resolve() for p in (source, destination, catalog))
    if not catalog.is_file():
        raise ValueError("Expected an existing compiled Assets.car file")
    if destination.exists():
        raise ValueError("Destination must not exist; use a new disposable .app path")
    if destination.suffix != ".app":
        raise ValueError("Destination must end in .app")
    info = plistlib.loads((source / "Contents/Info.plist").read_bytes())
    token = info["CFBundleExecutable"]
    application = source / "Contents/Resources" / token
    runtime = source / "Contents/Home"
    if not (runtime / "lib/libjli.dylib").is_file():
        raise ValueError("This experiment requires a bundled JDK with libjli.dylib")
    conf = (application / "etc/netbeans.conf").read_text()
    match = re.search(r'^netbeans_default_options="(.*)"$', conf, re.MULTILINE)
    if not match:
        raise ValueError("Expected single-line IDE netbeans_default_options")
    base = "@CONTENTS@/Resources/" + token
    raw = match[1].replace('${BASEDIR}', base).replace('${HOME}', str(Path.home()))
    raw = raw.replace('\\"', '"')
    if any(s in raw for s in ('$', '`')):
        raise ValueError("Shell expressions in JVM options are unsupported by this prototype")
    defaults = shlex.split(raw)
    if any(not option.startswith('-J') for option in defaults):
        raise ValueError("Expected -J JVM options in netbeans_default_options")
    options = [option[2:] for option in defaults
               if not option.startswith(('-J-Xdock:icon=', '-J-Dapple.awt.application.icon='))]
    clusters = [line.strip() for line in (application / "etc/netbeans.clusters").read_text().splitlines()
                if line.strip() and not line.lstrip().startswith('#')]
    clusters = [base + '/' + name for name in clusters
                if name != 'platform' and (application / name).is_dir()]
    classpath = ':'.join(base + '/platform/lib/' + p.name
                         for p in sorted((application / 'platform/lib').glob('*.jar')))
    options += ['-Dnetbeans.home=' + base + '/platform',
                '-Dnetbeans.dirs=' + ':'.join(clusters),
                '-Djdk.home=@CONTENTS@/Home', '-classpath', classpath,
                'org.netbeans.Main', '--branding', 'nb',
                '--userdir', '@PROFILE@/userdir', '--cachedir', '@PROFILE@/cache']
    subprocess.run(['ditto', '--clone', str(source), str(destination)], check=True)
    contents = destination / 'Contents'
    # Remove only the copied launcher's binary; retain Resources/<token> verbatim.
    (contents / 'MacOS' / token).unlink()
    executable = contents / 'MacOS/nbpackage-native-icon'
    subprocess.run(['clang', '-fobjc-arc', '-mmacosx-version-min=' + deployment_target,
                    '-framework', 'Foundation', str(Path(__file__).with_name('launcher.m')),
                    '-o', str(executable)], check=True)
    shutil.copyfile(catalog, contents / 'Resources/Assets.car')
    (contents / 'Resources/native-args.plist').write_bytes(plistlib.dumps(options))
    info['CFBundleExecutable'] = executable.name
    info['CFBundleIconName'] = icon_name
    info['CFBundleDisplayName'] = 'NBPackage Native Icon Experiment'
    (contents / 'Info.plist').write_bytes(plistlib.dumps(info))
    subprocess.run(['codesign', '--force', '--sign', '-', str(destination)], check=True)
    subprocess.run(['codesign', '--verify', str(destination)], check=True)
    print('Prepared:', destination)
    print('Launch with: open -n', shlex.quote(str(destination)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('catalog', type=Path)
    parser.add_argument('--icon-name', default='netbeans')
    parser.add_argument('--deployment-target', default='11.0')
    args = parser.parse_args()
    prepare(args.source, args.destination, args.catalog, args.icon_name, args.deployment_target)
