#!/usr/bin/env python3
"""Fictional multipart bodies for the production proxy boundary."""
import csv
import json
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

# Unique payment facts let the same proxy database retain all previous executions.
payments = [[f'PROXY-PAY-{uuid.uuid4()}', '2026-10-01', '架空のNginx入金A', '100000'],
            [f'PROXY-PAY-{uuid.uuid4()}', '2026-10-02', '架空のNginx入金B', '200000']]
(directory / 'proxy-payment-metadata.json').write_text(json.dumps({
    'firstTransactionId': payments[0][0], 'secondTransactionId': payments[1][0],
}), encoding='utf-8')
for name, rows in [('valid', payments), ('invalid', [payments[0], [payments[1][0], '2026-02-30', payments[1][2], '0']])]:
    with (directory / f'proxy-payment-{name}.csv').open('w', encoding='utf-8-sig', newline='') as target:
        writer = csv.writer(target, lineterminator='\r\n')
        writer.writerow(['transaction_id', 'transfer_date', 'payer_name', 'amount'])
        writer.writerows(rows)
