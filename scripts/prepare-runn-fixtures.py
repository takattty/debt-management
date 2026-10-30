#!/usr/bin/env python3
"""Create fictional, unique CSV fixtures for repeatable HTTP acceptance runs."""
import csv
import json
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

invoice_customer = f'INV-C-{uuid.uuid4()}'
(root / 'runn-invoice-metadata.json').write_text(json.dumps({'customerCode': invoice_customer}), encoding='utf-8')
invoices = [
    [invoice_customer, f'INV-{uuid.uuid4()}', '2026-10-29', '2026-11-30', '100000'],
    [invoice_customer, f'INV-{uuid.uuid4()}', '2026-10-29', '2026-11-30', '200000'],
]
for name, data in (
    ('valid', invoices),
    ('invalid', [invoices[0], ['MISSING-CUSTOMER', *invoices[1][1:4], 'abc']]),
    ('reordered', list(reversed(invoices))),
):
    with (root / f'runn-invoice-{name}.csv').open('w', encoding='utf-8-sig', newline='') as target:
        writer = csv.writer(target, lineterminator='\r\n' if name != 'reordered' else '\n')
        writer.writerow(['customer_code', 'invoice_number', 'issue_date', 'due_date', 'amount'])
        writer.writerows(data)

collection_customer = f'COL-C-{uuid.uuid4()}'
(root / 'runn-collection-metadata.json').write_text(json.dumps({'customerCode': collection_customer}), encoding='utf-8')
with (root / 'runn-collection.csv').open('w', encoding='utf-8-sig', newline='') as target:
    writer = csv.writer(target, lineterminator='\r\n')
    writer.writerow(['customer_code', 'invoice_number', 'issue_date', 'due_date', 'amount'])
    writer.writerow([collection_customer, f'COL-{uuid.uuid4()}', '2026-10-29', '2026-11-30', '100000'])
    writer.writerow([collection_customer, f'COL-{uuid.uuid4()}', '2026-10-29', '2026-11-30', '40000'])

payments = [[f'PAY-{uuid.uuid4()}', '0001-01-01', '架空入金A', '100000'],
            [f'PAY-{uuid.uuid4()}', '9999-12-31', '架空入金B', '200000']]
for name, data in (('valid', payments), ('invalid', [payments[0], [payments[1][0], '2026-02-30', '架空入金B', '0']])):
    with (root / f'runn-payment-{name}.csv').open('w', encoding='utf-8-sig', newline='') as target:
        writer = csv.writer(target, lineterminator='\r\n')
        writer.writerow(['transaction_id', 'transfer_date', 'payer_name', 'amount'])
        writer.writerows(data)
