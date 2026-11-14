#!/usr/bin/env python3
"""Build the plugin ZIP, publish it as a GitHub Release, and update updatePlugins.xml.

Usage:
  python3 scripts/release.py --dry-run   # build + write build/updatePlugins.xml; no git/GitHub changes
  python3 scripts/release.py             # commit updatePlugins.xml, tag, GitHub Release, push

Before releasing, bump <version> and update <change-notes> in plugin.xml and commit.
updatePlugins.xml at the repository root is the custom plugin repository that users register in
Settings → Plugins → ⚙ → Manage Plugin Repositories:
  https://raw.githubusercontent.com/<owner>/<repo>/main/updatePlugins.xml
"""
import argparse, html, pathlib, re, subprocess, sys, zipfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
PLUGIN_XML = ROOT / 'src/main/resources/META-INF/plugin.xml'
REPO_XML = ROOT / 'updatePlugins.xml'
BRANCH = 'main'


def run(*args):
    print('$', ' '.join(map(str, args)))
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)


def out(*args):
    return subprocess.run([str(a) for a in args], cwd=ROOT, text=True,
                          capture_output=True).stdout.strip()


def repo_slug():
    url = out('git', 'remote', 'get-url', 'origin')
    m = re.search(r'github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$', url)
    if not m:
        sys.exit(f'origin is not a GitHub remote: {url!r}')
    return m.group(1)


def plugin_info():
    root = ET.parse(PLUGIN_XML).getroot()
    text = lambda tag: (root.findtext(tag) or '').strip()
    info = {k: text(k) for k in ('id', 'name', 'version', 'description', 'change-notes')}
    vendor = root.find('vendor')
    info['vendor'] = (vendor.text or '').strip() if vendor is not None else ''
    info['vendor_url'] = vendor.get('url', '') if vendor is not None else ''
    iv = root.find('idea-version')
    info['since'] = iv.get('since-build', '') if iv is not None else ''
    info['until'] = iv.get('until-build', '') if iv is not None else ''
    for k in ('id', 'name', 'version', 'since'):
        if not info[k]:
            sys.exit(f'plugin.xml is missing {k}')
    return info


def repository_xml(info, zip_url):
    plugins = ET.Element('plugins')
    p = ET.SubElement(plugins, 'plugin', id=info['id'], version=info['version'], url=zip_url)
    iv = {'since-build': info['since']}
    if info['until']:
        iv['until-build'] = info['until']
    ET.SubElement(p, 'idea-version', iv)
    ET.SubElement(p, 'name').text = info['name']
    v = ET.SubElement(p, 'vendor')
    v.text = info['vendor']
    if info['vendor_url']:
        v.set('url', info['vendor_url'])
    ET.SubElement(p, 'description').text = info['description']
    ET.SubElement(p, 'change-notes').text = info['change-notes']
    ET.indent(plugins)
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(plugins, encoding='unicode') + '\n'


def asset_url(slug, version):
    return (f'https://github.com/{slug}/releases/download/v{version}/'
            f'avplayer-preview-{version}.zip')


def notes_markdown(change_notes_html):
    s = re.sub(r'<li[^>]*>', '- ', change_notes_html)
    s = re.sub(r'<br\s*/?>|</(li|p|ul|ol)>', '\n', s)
    s = html.unescape(re.sub(r'<[^>]+>', '', s))
    return '\n'.join(line.strip() for line in s.splitlines() if line.strip())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true', help='build and preview only; no git/GitHub changes')
    args = ap.parse_args()

    info = plugin_info()
    version, tag = info['version'], 'v' + info['version']
    slug = repo_slug()
    zip_path = ROOT / 'dist' / f'avplayer-preview-{version}.zip'

    if not args.dry_run:
        if out('git', 'status', '--porcelain'):
            sys.exit('Working tree is not clean. Commit plugin.xml changes first.')
        if out('git', 'branch', '--show-current') != BRANCH:
            sys.exit(f'Release from the {BRANCH} branch.')
        if out('git', 'tag', '--list', tag) or out('git', 'ls-remote', '--tags', 'origin', tag):
            sys.exit(f'Tag {tag} already exists. Bump <version> in plugin.xml.')

    run(sys.executable, ROOT / 'scripts/build.py')
    with zipfile.ZipFile(zip_path) as z:
        if 'avplayer-preview/lib/avplayer-preview.jar' not in z.namelist():
            sys.exit(f'Unexpected ZIP layout: {zip_path}')

    xml_text = repository_xml(info, asset_url(slug, version))
    notes = notes_markdown(info['change-notes']) or f'AVPlayer Preview {version}'
    if args.dry_run:
        preview = ROOT / 'build/updatePlugins.xml'
        preview.write_text(xml_text, encoding='utf-8')
        print(f'\n[dry-run] {zip_path}\n[dry-run] {preview}\n[dry-run] release notes:\n{notes}')
        return

    REPO_XML.write_text(xml_text, encoding='utf-8')
    run('git', 'add', REPO_XML)
    run('git', 'commit', '-m', f'chore(release): {tag}')
    run('git', 'tag', '-a', tag, '-m', f'AVPlayer Preview {version}')
    run('git', 'push', 'origin', tag)
    run('gh', 'release', 'create', tag, zip_path, '--repo', slug, '--verify-tag',
        '--title', f'AVPlayer Preview {version}', '--notes', notes)
    # Push the branch last so the published updatePlugins.xml never points at a missing asset.
    run('git', 'push', 'origin', BRANCH)
    print(f'\nReleased {tag}\nPlugin repository URL: '
          f'https://raw.githubusercontent.com/{slug}/{BRANCH}/updatePlugins.xml')


if __name__ == '__main__':
    main()
