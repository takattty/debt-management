"""Validate the generated real-HTTP baseline without a server or private writes."""
import csv
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ExistingInvoiceFixturesTest(unittest.TestCase):
    def test_each_run_has_two_independent_older_multi_page_baselines(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'scripts').mkdir()
            generator = root / 'scripts/prepare-runn-fixtures.py'
            shutil.copyfile(ROOT / 'scripts/prepare-runn-fixtures.py', generator)
            seen = set()
            for _ in range(2):
                subprocess.run([sys.executable, str(generator)], check=True, capture_output=True)
                metadata = json.loads((root / 'work/runn-existing-invoices.json').read_text())
                self.assertEqual(set(metadata), {'alpha', 'beta'})
                for tenant, fixture in metadata.items():
                    with (root / f'work/runn-existing-invoices-{tenant}.csv').open(encoding='utf-8-sig', newline='') as source:
                        rows = list(csv.DictReader(source))
                    self.assertEqual(len(rows), 52)
                    self.assertEqual({row['customer_code'] for row in rows}, {fixture['customerCode']})
                    self.assertEqual([row['invoice_number'] for row in rows], fixture['invoiceNumbers'])
                    values = {fixture['customerCode'], *fixture['invoiceNumbers']}
                    self.assertEqual(len(values), 53)
                    self.assertTrue(values.isdisjoint(seen))
                    seen.update(values)
                    for row in rows:
                        self.assertEqual((row['issue_date'], row['due_date'], row['amount']),
                                         ('2020-01-01', '2020-01-31', '1000'))


if __name__ == '__main__':
    unittest.main()
