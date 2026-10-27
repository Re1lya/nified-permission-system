"""Export the latest successful Maven report, without environment dumps or login tokens.
Run after `mvn clean verify`. This does not run tests and must not stand in for verification.
"""
from pathlib import Path
from datetime import datetime, timedelta, timezone
import argparse
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--destination', default='docs/submission/evidence')
args = parser.parse_args()
destination = (root / args.destination).resolve()
if root not in destination.parents or destination == root / 'docs':
    raise SystemExit('报告必须输出到仓库内的专用子目录')
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
# Remove only the generated report subdirectories, preventing stale classes from older reports.
for name in ('jacoco', 'surefire'):
    if (destination / name).exists():
        shutil.rmtree(destination / name)
shutil.copytree(root / 'target/site/jacoco', destination / 'jacoco')
(destination / 'surefire').mkdir(exist_ok=True)
for path in (root / 'target/surefire-reports').glob('*.txt'):
    shutil.copy2(path, destination / 'surefire' / path.name)
sources = [root / 'pom.xml'] + list((root / 'src').rglob('*.java')) + list((root / 'src/main/resources').rglob('*')) + list((root / 'src/test/resources').rglob('*'))
sources += list((root / 'frontend/src').rglob('*')) + [root / 'frontend/package.json', root / 'frontend/package-lock.json', root / 'third-party/clonefactory/pom.xml'] + list((root / 'third-party/clonefactory/src').rglob('*'))
manifest = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(sources) if p.is_file()}
report = dict(exportedAt=datetime.now(timezone(timedelta(hours=8))).isoformat(),
              baseCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
              sourceIdentity='Source files are identified by sourceSha256; baseCommit is the checkout baseline before report publication, not necessarily the final submission commit.',
              workingTreeChangesIncluded=bool(subprocess.check_output(['git', 'status', '--porcelain', '--', 'src', 'frontend', 'pom.xml', 'third-party/clonefactory'], cwd=root, text=True).strip()),
              branch=subprocess.check_output(['git', 'branch', '--show-current'], cwd=root, text=True).strip(),
              command='mvn clean verify', suites=suites, coverage=counters, sourceSha256=manifest)
(destination / 'test-summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('已导出测试摘要、JaCoCo 和不含环境变量的 Surefire 文本报告：', destination)
