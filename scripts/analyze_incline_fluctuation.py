#!/usr/bin/env python3
"""离线复算全体倾角的增量累加倍率；不连接设备，不修改原始ZIP。"""
import argparse
import collections
import csv
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import statistics
import zipfile


def quantile(values, p):
    values = sorted(values)
    index = (len(values) - 1) * p
    lo, hi = math.floor(index), math.ceil(index)
    return values[lo] + (values[hi] - values[lo]) * (index - lo)


def normal(row):
    return (row.get('runStatus') == 0 and row.get('alarmStatus') == 0 and row.get('zero') == 0
            and all(isinstance(row.get(a), (int, float)) and math.isfinite(row[a])
                    for a in ('angleX', 'angleY')))


def time(row):
    return dt.datetime.fromisoformat(row['tm'])


def analyze(archive, output, config, factor):
    if not math.isfinite(factor) or factor < 1:
        raise ValueError('放大倍率必须为大于等于1的有限数')
    root = Path(__file__).resolve().parents[1]
    with (root / 'document/秦滨高速轻量化测点编号.csv').open(encoding='utf-8-sig', newline='') as f:
        locations = {r['测点编号']: r['位置'] for r in csv.DictReader(f) if r['测点编号']}
    details = []
    record_count = 0
    with zipfile.ZipFile(archive) as z:
        manifest = json.loads(z.read('manifest.json'))
        expected = {d['deviceCode']: d for d in manifest['devices']}
        for name in sorted(n for n in z.namelist() if n.startswith('raw/') and n.endswith('.json')):
            source = json.loads(z.read(name))
            code = source['deviceCode']
            rows = sorted((r for page in source['pages'] for r in page['data']['ResultData']), key=time)
            if len(rows) != expected[code]['count'] or source['sn'] != expected[code]['sn']:
                raise ValueError(f'清单/SN/条数不一致: {code}')
            if len({r['tm'] for r in rows}) != len(rows):
                raise ValueError(f'重复采集时间: {code}')
            record_count += len(rows)
            good = [r for r in rows if normal(r)]
            if len(good) < 2:
                raise ValueError(f'正常记录不足: {code}')
            gaps = [(time(b) - time(a)).total_seconds() for a, b in zip(rows, rows[1:])]
            for axis in ('X', 'Y'):
                key = 'angle' + axis
                values = [r[key] for r in good]
                deltas = [abs(b[key] - a[key]) for a, b in zip(rows, rows[1:])
                          if normal(a) and normal(b) and 0 < (time(b) - time(a)).total_seconds() <= 5400]
                if not deltas:
                    raise ValueError(f'连续正常记录不足: {code}/{axis}')
                by_day = collections.defaultdict(list)
                for r in good:
                    by_day[r['tm'][:10]].append(r[key])
                mean = statistics.mean(values)
                first_raw = values[0]
                sd = statistics.stdev(values)
                span = max(values) - min(values)
                p95 = quantile(deltas, .95)
                details.append({
                    'deviceCode': code, 'sn': source['sn'], 'location': locations.get(code, '未映射'),
                    'axis': axis, 'count': len(rows), 'normalCount': len(good),
                    'first': rows[0]['tm'], 'last': rows[-1]['tm'],
                    'medianIntervalSeconds': statistics.median(gaps),
                    'gapsOver90Minutes': sum(g > 5400 for g in gaps),
                    'mean': mean, 'firstRaw': first_raw, 'sd': sd, 'range': span,
                    'adjacentPairCount': len(deltas), 'deltaP95': p95, 'maxDelta': max(deltas),
                    'factor': factor,
                    'daily': {day: {'count': len(v), 'sd': statistics.stdev(v) if len(v) > 1 else None}
                              for day, v in by_day.items()},
                    'candidates': [{'factor': k, 'sd': sd*k, 'range': span*k,
                                    'deltaP95': p95*k, 'maxDelta': max(deltas)*k,
                                    'meanShift': (mean-first_raw)*(k-1)} for k in (1, 2, 3, 5, 10)],
                })
    codes = sorted({d['deviceCode'] for d in details})
    if (record_count != manifest['recordCount'] or len(codes) != manifest['deviceCount']
            or set(codes) != set(expected) or len(details) != 2*len(codes)):
        raise ValueError('设备集合/总条数/轴数与清单不一致')
    metadata = {'archive': archive.name, 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
                'queryStart': manifest['queryStart'], 'queryEnd': manifest['queryEnd'],
                'timezone': manifest['timezone'], 'sourceNote': manifest['note'],
                'deviceCount': len(codes), 'recordCount': record_count,
                'normalRecordCount': sum(d['normalCount'] for d in details)//2,
                'factor': factor, 'scope': 'collected_payload',
                'statistics': '样本标准差n-1；正常状态且zero=0；相邻变化不跨90分钟断档或异常点'}
    output.mkdir(parents=True, exist_ok=True)
    (output/'assessment.json').write_text(json.dumps({'source': metadata, 'axes': details},
                                                    ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    lines = ['# 全体倾角增量累加评估', '',
             f"数据窗口：{manifest['queryStart']} 至 {manifest['queryEnd']}（北京时间）。",
             f"共{len(codes)}台、{len(details)}个轴、{record_count}条记录，其中{metadata['normalRecordCount']}条正常。",
             '约每小时一条，不能据此评价秒级/分钟级响应。', '',
             f"来源说明：{manifest['note']}", f"ZIP SHA-256：`{metadata['sha256']}`", '',
             '## 最终实施规则', '',
             f'全部INC通道统一使用{factor:g}倍，不再配置固定中心。',
             '第一条没有有效前值时：本次入库 = 当前原始角度 - 当前零点。',
             '后续：本次入库 = 上次入库 + [(当前原始角度 - 当前零点) - (上次原始角度 - 同一零点)] × 倍率。',
             '零点不变时等价于：本次入库 = 上次入库 + (当前原始角度 - 上次原始角度) × 倍率。',
             '原始值稳定后，入库值保持稳定，不用已放大值与当前原始值作差。', '',
             '按测点查询现有incline_forward_data中不晚于当前采集时间的最新一条，raw_data提供上次原始值、payload提供上次入库值。',
             '第一条、新SN或无有效历史时，从当前扣零值起算。',
             '按零点保持不变处理，不检测零点变更；重启后从现有raw_data和payload续算，不更改时间字段。',
             '只在原始采集构建payload前执行一次，不新增数据库列、报文或状态接口字段；入库失败不推进参考状态。',
             '相同采集时间重试，原始差为0，不会重复放大已有结果。', '',
             '现有angleX/Y和payload保存处理值；raw_data及rawAngleX/Y保留原始角度。温度、振动、水位和设备原始报警状态不参与放大。',
             '定时上报直接读取payload，不再次放大；历史模拟使用k*raw-payload的相邻变化检查段边界，不重放累加。',
             '配置文件incline-fluctuation.yml只含enabled和factor，可用incline.fluctuation.enabled=false关闭，修改需重启。', '',
             '以下离线算例假设：窗口首条正常原始值直接作为初始入库值，零点为0且期间不变。线上起点以实际数据库前值为准。',
             '表中的窗口首值仅供复算，不能生成或作为固定中心配置。统一倍率保持标准差/值域比例，不能恢复分辨率或证明设备有效。', '',
             '## 统一倍率对照', '',
             '各列分别取全部轴的最大值，不一定来自同一个轴。', '',
             '| 倍率 | 最大标准差（°） | 最大值域（°） | 最大相邻变化（°） |',
             '|---:|---:|---:|---:|']
    for k in (1, 2, 3, 5, 10):
        lines.append(f'| {k} | '+ ' | '.join(f'{max(d[m] for d in details)*k:.6f}'
                                            for m in ('sd', 'range', 'maxDelta'))+' |')
    lines += ['', '选择2倍作为初次放大参数，比5倍保守；不能据此确定设备的真实灵敏度修正倍率。', '',
              '按项目测点表，001为上行、002为下行，本窗口偏低主要在潮白新河上行和部分蓟运河上行，',
              '与最初文字材料的上下行描述不同。本次全体按同一倍率处理，不以原异常清单区分。', '',
              '## 全部轴与统一增量倍率结果', '',
              '| 测点 | 位置 | 轴 | 正常/总数 | 窗口首条原始值（°） | 原标准差（°） | 放大标准差（°） | 原值域（°） | 放大值域（°） |',
              '|---|---|---|---:|---:|---:|---:|---:|---:|']
    for d in details:
        lines.append(f"| {d['deviceCode']} | {d['location']} | {d['axis']} | {d['normalCount']}/{d['count']} | "
                     + ' | '.join(f'{v:.6f}' for v in (d['firstRaw'], d['sd'], d['sd']*factor,
                                                       d['range'], d['range']*factor))+' |')
    lines += ['', '完整每日标准差、相邻变化P95、断档及1/2/3/5/10倍对比见assessment.json。',
              '正常记录指runStatus=0、alarmStatus=0、zero=0且X/Y均有限；相邻变化仅使用原序列中连续正常且间隔≤90分钟的配对。',
              'DLJH-INC-P01-002-02的3条alarmStatus=2记录不参与正常统计和窗口首值计算。', '']
    (output/'assessment.md').write_text('\n'.join(lines), encoding='utf-8')
    if config:
        yaml = ['# 按前后两条原始角度的增量统一放大，只在入库前处理一次；零点按保持不变处理。',
                '# 上次原始值取raw_data，上次入库值取payload；重启从数据库续算，无固定中心。',
                'incline:', '  fluctuation:', '    enabled: true', f'    factor: {factor:g}']
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text('\n'.join(yaml)+'\n', encoding='utf-8')
    print(json.dumps(metadata, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--config', type=Path, help='可选：生成统一增量倍率配置（不含固定中心）')
    parser.add_argument('--factor', type=float, default=2.0)
    args = parser.parse_args()
    analyze(args.archive, args.output, args.config, args.factor)
