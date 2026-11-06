#!/usr/bin/env python3
"""Temporary Android screen source for OBS; requires scrcpy 3+ on PATH."""
import argparse
import shutil
import subprocess


def command(app=None, serial=None):
    args = ['scrcpy', '--window-title=OpenStream Screen', '--max-size=1920',
            '--max-fps=30', '--video-bit-rate=8M']
    if serial:
        args += ['--serial', serial]
    if app:
        args += ['--new-display=1080x1920', '--start-app=' + app]
    return args


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', help='Android package to open on a separate display')
    parser.add_argument('--serial', help='Device serial when multiple phones are connected')
    args = parser.parse_args()
    if not shutil.which('scrcpy'):
        parser.exit(1, 'Install scrcpy 3+ from https://github.com/Genymobile/scrcpy then retry.\n')
    print('In OBS add Window Capture and select "OpenStream Screen".\n'
          'Close the mirror window to stop sharing. Hide the OBS source when finished.', flush=True)
    try:
        return subprocess.call(command(args.app, args.serial))
    except KeyboardInterrupt:
        return 130


if __name__ == '__main__':
    raise SystemExit(main())
