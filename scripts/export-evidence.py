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
parser.add_argument('--build-directory', default='target',
                    help='Maven target 目录；可指向相同源码的隔离构建产物')
parser.add_argument('--workbook-directory', type=Path,
                    help='本次已执行真实三表测试时，记录输入文件摘要；不会复制员工数据')
args = parser.parse_args()
build = (root / args.build_directory).resolve()
# Separate test builds must not publish evidence for different production/test inputs.
if build != root / 'target':
    snapshot = build.parent
    inputs = [root / 'pom.xml'] + list((root / 'src').rglob('*')) + list((root / 'third-party/clonefactory/src').rglob('*')) + [root / 'third-party/clonefactory/pom.xml']
    for source in inputs:
        if source.is_file():
            copied = snapshot / source.relative_to(root)
            if not copied.is_file() or source.read_bytes() != copied.read_bytes():
                raise SystemExit(f'隔离构建源码不一致，请重新验证：{source.relative_to(root)}')
destination = (root / args.destination).resolve()
if root not in destination.parents or destination == root / 'docs':
    raise SystemExit('报告必须输出到仓库内的专用子目录')
suites = []
for path in sorted((build / 'surefire-reports').glob('TEST-*.xml')):
    suite = ET.parse(path).getroot()
    suites.append({k: suite.attrib[k] for k in ('name', 'tests', 'failures', 'errors', 'skipped', 'time')})
if not suites or any(int(s['failures']) or int(s['errors']) for s in suites):
    raise SystemExit('需要一次成功的完整测试结果，请先运行 mvn clean verify')
external_workbooks = None
if args.workbook_directory:
    actual = next((s for s in suites if s['name'].endswith('.ActualWorkbookImportTests')), None)
    if not actual or int(actual['skipped']) or int(actual['tests']) != 1:
        raise SystemExit('不能声明真实三表已验证：对应测试未通过或被跳过')
    names = ('市政公司部门信息表.xlsx', '市政公司员工信息表.xlsx', '市政公司应用软件功能权限清单(1).xlsx')
    external_workbooks = {
        name: hashlib.sha256((args.workbook_directory / name).read_bytes()).hexdigest() for name in names
    }
coverage = ET.parse(build / 'site/jacoco/jacoco.xml').getroot()
counters = {}
for c in coverage.findall('counter'):
    passed, missed = int(c.attrib['covered']), int(c.attrib['missed'])
    counters[c.attrib['type']] = dict(covered=passed, missed=missed, percentage=round(passed * 100 / (passed + missed), 2))
destination.mkdir(parents=True, exist_ok=True)
# Remove only the generated report subdirectories, preventing stale classes from older reports.
for name in ('jacoco', 'surefire'):
    if (destination / name).exists():
        shutil.rmtree(destination / name)
shutil.copytree(build / 'site/jacoco', destination / 'jacoco')
(destination / 'surefire').mkdir(exist_ok=True)
for path in (build / 'surefire-reports').glob('*.txt'):
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
              command='mvn -B clean verify', suites=suites, coverage=counters, sourceSha256=manifest)
if external_workbooks:
    report['externalWorkbooksSha256'] = external_workbooks
    report['command'] = 'COMPANY_WORKBOOK_DIR=<external-workbooks> mvn -B clean verify'
(destination / 'test-summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('已导出测试摘要、JaCoCo 和不含环境变量的 Surefire 文本报告：', destination)
