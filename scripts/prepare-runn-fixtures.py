#!/usr/bin/env python3
"""Create fictional, unique CSV fixtures for repeatable HTTP acceptance runs."""
import csv
from pathlib import Path
import uuid

root = Path(__file__).resolve().parents[1] / 'work'
root.mkdir(exist_ok=True)
rows = [[f'CSV-{uuid.uuid4()}', '架空CSV取込, A'], [f'CSV-{uuid.uuid4()}', '架空CSV取込B']]
for name, data in (
    ('valid', rows),
    ('invalid', [rows[0], [rows[1][0], '']]),
    ('reordered', list(reversed(rows))),
):
    with (root / f'runn-customer-{name}.csv').open('w', encoding='utf-8-sig', newline='') as target:
        writer = csv.writer(target, lineterminator='\r\n' if name != 'reordered' else '\n')
        writer.writerow(['customer_code', 'customer_name'])
        writer.writerows(data)
