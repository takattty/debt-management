#!/usr/bin/env python3
"""Fictional multipart bodies for the production proxy boundary."""
import csv
from pathlib import Path
import uuid

directory = Path(__file__).resolve().parents[1] / 'work'
directory.mkdir(exist_ok=True)
prefix = 'P-' + uuid.uuid4().hex[:12]
with (directory / 'proxy-valid.csv').open('w', encoding='utf-8', newline='') as target:
    writer = csv.writer(target)
    writer.writerow(['customer_code', 'customer_name'])
    writer.writerows((f'{prefix}-{index:05}', '架空' * 90) for index in range(10_000))
for name, size in [('proxy-file-exact.csv', 10 * 1024 * 1024),
                   ('proxy-file-limit.csv', 10 * 1024 * 1024 + 1),
                   ('proxy-request-limit.csv', 11 * 1024 * 1024 + 1)]:
    (directory / name).write_bytes(b'x' * size)
