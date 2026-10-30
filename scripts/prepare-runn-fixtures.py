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

# Persist an older, multi-page baseline before browser tests; never clear existing facts.
baseline = {}
for tenant in ('alpha', 'beta'):
    customer_code = f'BASE-{uuid.uuid4()}'
    numbers = [f'BASE-{uuid.uuid4()}' for _ in range(52)]
    baseline[tenant] = {'customerCode': customer_code, 'invoiceNumbers': numbers}
    with (root / f'runn-existing-invoices-{tenant}.csv').open('w', encoding='utf-8-sig', newline='') as target:
        writer = csv.writer(target, lineterminator='\r\n')
        writer.writerow(['customer_code', 'invoice_number', 'issue_date', 'due_date', 'amount'])
        writer.writerows([customer_code, number, '2020-01-01', '2020-01-31', '1000'] for number in numbers)
(root / 'runn-existing-invoices.json').write_text(json.dumps(baseline), encoding='utf-8')

payments = [[f'PAY-{uuid.uuid4()}', '0001-01-01', '架空入金A', '100000'],
            [f'PAY-{uuid.uuid4()}', '9999-12-31', '架空入金B', '200000']]
for name, data in (('valid', payments), ('invalid', [payments[0], [payments[1][0], '2026-02-30', '架空入金B', '0']])):
    with (root / f'runn-payment-{name}.csv').open('w', encoding='utf-8-sig', newline='') as target:
        writer = csv.writer(target, lineterminator='\r\n')
        writer.writerow(['transaction_id', 'transfer_date', 'payer_name', 'amount'])
        writer.writerows(data)

candidate_id = str(uuid.uuid4())
candidate = {
    'firstCustomerCode': f'CA-{candidate_id}', 'secondCustomerCode': f'CB-{candidate_id}',
    'transactionId': f'CAND-{candidate_id}', 'payerName': f'ｶｸｳ KAKUU kakuu é é {candidate_id}',
}
(root / 'runn-candidate-metadata.json').write_text(json.dumps(candidate, ensure_ascii=False), encoding='utf-8')
for name, header, data in (
    ('invoices', ['customer_code', 'invoice_number', 'issue_date', 'due_date', 'amount'], [
        [candidate['firstCustomerCode'], f'CI-A-{candidate_id}', '0001-01-01', '9999-12-31', '100'],
        [candidate['secondCustomerCode'], f'CI-B-{candidate_id}', '0001-01-01', '0001-01-01', '100'],
    ]),
    ('payment', ['transaction_id', 'transfer_date', 'payer_name', 'amount'], [
        [candidate['transactionId'], '0001-01-01', f'　{candidate["payerName"]}　', '100'],
    ]),
):
    with (root / f'runn-candidate-{name}.csv').open('w', encoding='utf-8-sig', newline='') as target:
        writer = csv.writer(target, lineterminator='\r\n')
        writer.writerow(header)
        writer.writerows(data)

allocation_id = str(uuid.uuid4())
allocation = {'firstCustomerCode': f'AL-A-{allocation_id}', 'secondCustomerCode': f'AL-B-{allocation_id}',
              'transactionId': f'ALLOC-{allocation_id}'}
(root / 'runn-allocation-metadata.json').write_text(json.dumps(allocation), encoding='utf-8')
for name, header, data in (
    ('invoices', ['customer_code', 'invoice_number', 'issue_date', 'due_date', 'amount'], [
        [allocation['firstCustomerCode'], f'AL-I-A-{allocation_id}', '0001-01-01', '9999-12-31', '100000'],
        [allocation['secondCustomerCode'], f'AL-I-B-{allocation_id}', '0001-01-01', '0001-01-01', '50000']]),
    ('payment', ['transaction_id', 'transfer_date', 'payer_name', 'amount'], [
        [allocation['transactionId'], '0001-01-01', 'Fictional allocation receipt', '120000']]),
):
    with (root / f'runn-allocation-{name}.csv').open('w', encoding='utf-8-sig', newline='') as target:
        writer = csv.writer(target, lineterminator='\r\n')
        writer.writerow(header)
        writer.writerows(data)

collection_customer = f'COL-C-{uuid.uuid4()}'
(root / 'runn-collection-metadata.json').write_text(json.dumps({'customerCode': collection_customer}), encoding='utf-8')
with (root / 'runn-collection.csv').open('w', encoding='utf-8-sig', newline='') as target:
    writer = csv.writer(target, lineterminator='\r\n')
    writer.writerow(['customer_code', 'invoice_number', 'issue_date', 'due_date', 'amount'])
    writer.writerow([collection_customer, f'COL-{uuid.uuid4()}', '2026-10-29', '2026-11-30', '100000'])
    writer.writerow([collection_customer, f'COL-{uuid.uuid4()}', '2026-10-29', '2026-11-30', '40000'])
