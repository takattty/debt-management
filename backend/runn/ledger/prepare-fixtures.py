#!/usr/bin/env python3
"""Emit unique fictional local V8 fixture SQL/IDs; never connect to or clear a database."""
from pathlib import Path
import uuid,json,hashlib
Path('work').mkdir(exist_ok=True)
ids={key:str(uuid.uuid4()) for key in ['customer','payment','feePayment','partial','feeOnly','cancelled','reversed','invoiceBatch','paymentBatch','partialOperation','feeOperation','reversedOperation','reversal']}
tenant='11111111-1111-1111-1111-111111111111';actor='11111111-1111-1111-1111-111111111102'
sql=['BEGIN;']
sql.append(f"INSERT INTO customer(id,tenant_id,code,name) VALUES ('{ids['customer']}','{tenant}','{ids['customer']}','Fictional HTTP ledger customer');")
for key,kind in [('invoiceBatch','INVOICE'),('paymentBatch','PAYMENT')]:
 sql.append(f"INSERT INTO import_batch(id,tenant_id,kind,format_version,semantic_fingerprint,status,version,created_by_user_id,created_at,expires_at,confirmed_by_user_id,confirmed_at,row_count,error_count) VALUES ('{ids[key]}','{tenant}','{kind}',1,decode('{hashlib.sha256(ids[key].encode()).hexdigest()}','hex'),'CONFIRMED',1,'{actor}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval '1 day','{actor}',CURRENT_TIMESTAMP,1,0);")
for key in ['partial','feeOnly','cancelled','reversed']:
 sql.append(f"INSERT INTO invoice(id,tenant_id,customer_id,invoice_number,issue_date,due_date,amount_jpy,source_batch_id) VALUES ('{ids[key]}','{tenant}','{ids['customer']}','{ids[key]}','0001-01-01','9999-12-31',100000,'{ids['invoiceBatch']}');")
sql.append(f"""INSERT INTO invoice_revision(tenant_id,invoice_id,revision,operation,snapshot,actor_user_id,occurred_at)
SELECT i.tenant_id,i.id,0,'CREATED',jsonb_build_object('customerId',c.id,'customerCode',c.code,'customerName',c.name,'invoiceNumber',i.invoice_number,'issueDate',i.issue_date,'dueDate',i.due_date,'amount',i.amount_jpy,'status',i.status,'sourceBatchId',i.source_batch_id,'version',0),'{actor}',CURRENT_TIMESTAMP
FROM invoice i JOIN customer c ON c.tenant_id=i.tenant_id AND c.id=i.customer_id WHERE i.tenant_id='{tenant}' AND i.customer_id='{ids['customer']}';""")
for key in ['payment','feePayment']:
 sql.append(f"INSERT INTO payment(id,tenant_id,transaction_id,transfer_date,payer_name,amount_jpy,source_batch_id,created_by_user_id,created_at) VALUES ('{ids[key]}','{tenant}','{ids[key]}','9999-12-31','Fictional HTTP ledger receipt',120000,'{ids['paymentBatch']}','{actor}',CURRENT_TIMESTAMP);")
 sql.append(f"INSERT INTO reconciliation_payment_state(tenant_id,payment_id) VALUES ('{tenant}','{ids[key]}');")
for receipt,target,operation,version,cash,fee,original in [
 ('payment','partial','partialOperation',1,60000,440,None),('feePayment','feeOnly','feeOperation',1,0,1,None),
 ('feePayment','reversed','reversedOperation',2,1,1,None),('feePayment','reversed','reversal',3,0,0,'reversedOperation')]:
 sql.append(f"UPDATE reconciliation_payment_state SET version={version} WHERE tenant_id='{tenant}' AND payment_id='{ids[receipt]}';")
 reverse='NULL' if original is None else f"'{ids[original]}'"
 kind='CONFIRM' if original is None else 'REVERSE'
 sql.append(f"INSERT INTO allocation_operation(id,tenant_id,payment_id,request_id,kind,reverses_id,payment_version,actor_user_id,reason) VALUES ('{ids[operation]}','{tenant}','{ids[receipt]}','{uuid.uuid4()}','{kind}',{reverse},{version},'{actor}','Fictional HTTP fixture');")
 if original is None:sql.append(f"INSERT INTO allocation_line VALUES ('{tenant}','{ids[operation]}','{ids[target]}',0,{cash},{fee});")
 sql.append(f"UPDATE invoice SET version=version+1 WHERE tenant_id='{tenant}' AND id='{ids[target]}';")
sql.append(f"UPDATE invoice SET status='CANCELLED',version=1,cancelled_at=CURRENT_TIMESTAMP WHERE tenant_id='{tenant}' AND id='{ids['cancelled']}';")
sql.append('COMMIT;')
Path('work/ledger-runn-seed.sql').write_text('\n'.join(sql))
Path('work/ledger-runn-ids.json').write_text(json.dumps(ids,indent=2))
env={'RUNN_LEDGER_CUSTOMER':ids['customer'],'RUNN_LEDGER_PAYMENT':ids['payment'],'RUNN_LEDGER_PARTIAL':ids['partial'],'RUNN_LEDGER_FEE_ONLY':ids['feeOnly'],'RUNN_LEDGER_CANCELLED':ids['cancelled'],'RUNN_LEDGER_REVERSED':ids['reversed']}
Path('work/ledger-runn.env').write_text('\n'.join(k+'='+v for k,v in env.items())+'\n')
