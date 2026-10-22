"""Generate small fictional workbooks for repeatable classroom demonstrations."""
from pathlib import Path
import argparse
from openpyxl import Workbook

def save(root,name,rows):
    book=Workbook();sheet=book.active;sheet.title='演示数据'
    for row in rows:sheet.append(row)
    for column in sheet.columns:sheet.column_dimensions[column[0].column_letter].width=24
    book.save(root/name)
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',default='output/demo-workbooks');args=parser.parse_args();root=Path(args.output);root.mkdir(parents=True,exist_ok=True)
    save(root,'部门演示.xlsx',[['部门名称','部门经理','上级部门'],['总公司','',''],['信息中心','演示张','总公司'],['财务部','演示李','总公司']])
    save(root,'员工演示.xlsx',[['姓名','用户名','分公司','部门','岗位','电话号码'],['演示张','demo_zhang','总公司','信息中心','信息管理员','13900000001'],['演示李','demo_li','总公司','财务部','财务专员','13900000002']])
    save(root,'权限演示.xlsx',[['虚构演示清单'],['软件系统','功能模块','功能点','功能说明','系统管理员','公司领导','部门经理','项目经理','普通员工'],['财务演示系统','报销管理','查看报销','仅演示权限目录','全部','查看/审批','查看/编辑','查看','查看本人'],['','报表管理','导出汇总','仅演示导出权限','全部','查看/导出','查看','无','无']])
    print(f'Created 3 fictional workbooks in {root}')
