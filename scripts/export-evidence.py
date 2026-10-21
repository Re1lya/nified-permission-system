"""Export the latest successful Maven report, without environment dumps or login tokens.
Run after `mvn clean verify`. This does not run tests and must not stand in for verification.
"""
from pathlib import Path
from datetime import datetime, timedelta, timezone
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
destination = root / 'docs/first-check/evidence'
suites = []
for path in sorted((root / 'target/surefire-reports').glob('TEST-*.xml')):
    suite = ET.parse(path).getroot()
    suites.append({k: suite.attrib[k] for k in ('name', 'tests', 'failures', 'errors', 'skipped', 'time')})
if not suites or any(int(s['failures']) or int(s['errors']) for s in suites):
    raise SystemExit('需要一次成功的完整测试结果，请先运行 mvn clean verify')
coverage = ET.parse(root / 'target/site/jacoco/jacoco.xml').getroot()
counters = {}
for c in coverage.findall('counter'):
    passed, missed = int(c.attrib['covered']), int(c.attrib['missed'])
    counters[c.attrib['type']] = dict(covered=passed, missed=missed, percentage=round(passed * 100 / (passed + missed), 2))
destination.mkdir(parents=True, exist_ok=True)
shutil.copytree(root / 'target/site/jacoco', destination / 'jacoco', dirs_exist_ok=True)
(destination / 'surefire').mkdir(exist_ok=True)
for path in (root / 'target/surefire-reports').glob('*.txt'):
    shutil.copy2(path, destination / 'surefire' / path.name)
sources = [root / 'pom.xml'] + list((root / 'src').rglob('*.java')) + list((root / 'src/main/resources').rglob('*'))
manifest = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(sources) if p.is_file()}
report = dict(exportedAt=datetime.now(timezone(timedelta(hours=8))).isoformat(),
              baseCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
              branch=subprocess.check_output(['git', 'branch', '--show-current'], cwd=root, text=True).strip(),
              command='mvn clean verify', suites=suites, coverage=counters, sourceSha256=manifest)
(destination / 'test-summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
screens = root / 'docs/first-check/screenshots'
screens.mkdir(exist_ok=True)
for path in (root / 'output/playwright').glob('[0-9][0-9]-*.png'):
    shutil.copy2(path, screens / path.name)
print('已导出真实测试报告及已采集截图。请人工确认报告生成后源码没有变化。')
