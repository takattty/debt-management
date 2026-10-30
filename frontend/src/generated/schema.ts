/**
 * Generated from contracts/openapi.yaml. Do not edit directly.
 * Run npm run generate:api from frontend/.
 */

export interface paths {
    "/api/collections/{id}/actions": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Record a shared note and explicitly retained, changed or cleared next action date
         * @description ADMIN and ACCOUNTANT update independent collection state. Notes remain possible after payoff or cancellation. No payment, invoice amount or ledger fact is changed. State, immutable event and audit commit atomically.
         */
        post: operations["recordCollectionAction"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/collections/{id}/hold": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Hold follow-up with a reason while retaining the note and plan
         * @description ADMIN and ACCOUNTANT update independent collection state. Notes remain possible after payoff or cancellation. No payment, invoice amount or ledger fact is changed. State, immutable event and audit commit atomically.
         */
        post: operations["holdCollection"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/collections/{id}/release": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Release an explicit hold with a reason while retaining the note and plan
         * @description ADMIN and ACCOUNTANT update independent collection state. Notes remain possible after payoff or cancellation. No payment, invoice amount or ledger fact is changed. State, immutable event and audit commit atomically.
         */
        post: operations["releaseCollection"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/collections/{id}/draft": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        /**
         * Preview plain text for human review and copying without sending any message
         * @description Uses customer name, invoice number, due date and current real outstanding amount. Held candidates retain hold information. No recipient email master, sending endpoint or sent event is introduced.
         */
        get: operations["previewCollectionDraft"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/collections": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List overdue positive balances using an explicit Tokyo business date
         * @description Uses active cash and explicit fees from the internal ledger. ACTIONABLE excludes held records; HELD shows only held candidates; ALL includes both. Next action dates never silently hide a candidate. Date and invoice ID form the stable keyset order.
         */
        get: operations["listCollections"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/collections/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** Read current collection facts including paid or cancelled invoices */
        get: operations["getCollection"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/collections/{id}/history": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** Read append-only shared follow-up events even after payoff or cancellation */
        get: operations["listCollectionHistory"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/invoices/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** Read an invoice including its cancelled state */
        get: operations["getInvoice"];
        /**
         * Correct an active unallocated invoice with a reason and expected version
         * @description Invoice number is immutable, including after cancellation. Locks the invoice and appends revision and audit atomically; a stale version or active allocation prevents changes. Settlement dispatch is not present yet; enabling it requires a real ledger eligibility adapter.
         */
        put: operations["correctInvoice"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/invoices/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Cancel an active unallocated invoice while retaining its facts and number
         * @description Requires a reason and current version. Appends a cancellation revision and audit atomically. The original number remains permanently reserved and cancelled invoices are excluded from receivables. A repeat cancellation is rejected.
         */
        post: operations["cancelInvoice"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/invoices/{id}/history": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** Read immutable invoice revisions in increasing revision order */
        get: operations["listInvoiceHistory"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/receivables": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Browse invoices by status in stable due-date and ID order
         * @description Bounded tenant-scoped keyset page. ACTIVE is the compatible default; CANCELLED shows cancelled originals and ALL includes both states. Keep the selected status/customer/due filters when following nextCursor. Settlement is not enabled in this slice, so active outstandingAmount equals amount; cancelled outstandingAmount is zero while amount retains the original invoice value. Balances are never editable inputs.
         */
        get: operations["listReceivables"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/payer-aliases": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List active manual payer correspondences in stable ID order
         * @description ADMIN and ACCOUNTANT may read all active pairs in their authenticated tenant. Identical names may correspond to multiple customers; this endpoint does not choose a candidate or change any balance.
         */
        get: operations["listPayerAliases"];
        put?: never;
        /**
         * Register a manual payer-name to customer correspondence
         * @description ADMIN and ACCOUNTANT may register a pair for an existing customer in the authenticated tenant. Uses the payment CSV text rule, stripping surrounding whitespace only; case, width, kana and Unicode normalization are never applied. The stripped name is 1 through 200 code points; controls, line separators and malformed Unicode are rejected before stripping. An active identical pair returns 409 PAYER_ALIAS_EXISTS. A deactivated pair may be registered again with a new ID, retaining the original row and audit.
         */
        post: operations["createPayerAlias"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/payer-aliases/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        /**
         * Read the original correspondence including its deactivation history
         * @description Retains the original name, customer and registering actor/time after deactivation. Other tenants return 404.
         */
        get: operations["getPayerAlias"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/payer-aliases/{id}/deactivation": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Deactivate an incorrect correspondence with its current version and reason
         * @description ADMIN and ACCOUNTANT may deactivate once, atomically saving actor/time/reason and audit while retaining the original correspondence. A stale version or repeat returns 409 VERSION_CONFLICT. No physical deletion, reactivation or allocation is performed.
         */
        post: operations["deactivatePayerAlias"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/payments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List immutable incoming payments in stable transfer-date and ID order
         * @description Bounded keyset page of confirmed receipts. No allocation ledger exists in this slice, so unallocatedAmount equals amount only until the real ledger is introduced. Balances are never editable inputs. Date filters are inclusive; invalid dates, reversed bounds and malformed cursors return 400.
         */
        get: operations["listPayments"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/imports/customers/template": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Download the UTF-8 BOM customer CSV header
         * @description Header only. Columns are customer_code,customer_name. Surrounding field whitespace is stripped as in manual customer registration; case and Unicode are not normalized.
         */
        get: operations["downloadCustomerImportTemplate"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/imports/customers/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Store a customer CSV preview without creating any customer
         * @description New customers only. Existing or repeated customer codes invalidate the entire batch. Syntax and row errors are saved for correction; a preview expires after 24 hours. Maximum 10MiB and 10000 data records.
         */
        post: operations["previewCustomerImport"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/imports/invoices/template": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Download the UTF-8 BOM invoice CSV header
         * @description Header only. Columns are customer_code,invoice_number,issue_date,due_date,amount. Numbers strip surrounding whitespace without case or Unicode normalization. Dates are strict YYYY-MM-DD with year 0001 through 9999; amount is a positive integer no greater than 9007199254740991 yen.
         */
        get: operations["downloadInvoiceImportTemplate"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/imports/invoices/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Store an invoice CSV preview without creating any invoice
         * @description Customers must already exist in the authenticated tenant. Duplicate invoice numbers invalidate the whole batch, including numbers reserved by cancelled invoices. Due date must be on or after issue date. Stores immutable facts and customer versions for 24 hours. Confirmation revalidates the stored customer references and creates every invoice, initial revision and audit atomically. Maximum 10MiB and 10000 data records.
         */
        post: operations["previewInvoiceImport"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/imports/payments/template": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Download the UTF-8 BOM payment CSV header
         * @description Header only. Columns are transaction_id,transfer_date,payer_name,amount. Transaction IDs and payer names strip surrounding whitespace only, without case, width or Unicode normalization. Transfer dates are strict YYYY-MM-DD with year 0001 through 9999; amount is a positive integer no greater than 9007199254740991 yen. Zero, outgoing payments and refunds are outside the initial scope.
         */
        get: operations["downloadPaymentImportTemplate"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/imports/payments/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Store a payment CSV preview without creating any payment
         * @description Fictional bank receipts, with tenant-unique transaction IDs permanently reserved by confirmed payments, including after allocation reversal. Repeated IDs within the file or existing payments invalidate the entire batch. Stores an immutable snapshot for 24 hours without changing payment facts or audits. Preview does not reserve transaction IDs or semantic content. Explicit confirmation atomically registers all receipts and audits after revalidating IDs, version, expiry and duplicates. Maximum 10MiB and 10000 data records.
         */
        post: operations["previewPaymentImport"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/imports/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Server-issued batch ID */
                id: components["parameters"]["ImportId"];
            };
            cookie?: never;
        };
        /**
         * Read the stored preview or confirmed result
         * @description A different tenant or unsupported import kind returns 404. Reads remain available after preview expiry for correction and outcome recovery.
         */
        get: operations["getImportBatch"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/imports/{id}/rows": {
        parameters: {
            query?: {
                /** @description Last record or error number from the preceding page; zero starts the snapshot */
                cursor?: components["parameters"]["ImportCursor"];
                /** @description Maximum items per page */
                limit?: components["parameters"]["ImportLimit"];
            };
            header?: never;
            path: {
                /** @description Server-issued batch ID */
                id: components["parameters"]["ImportId"];
            };
            cookie?: never;
        };
        /**
         * Read an immutable customer or invoice preview page in original record order
         * @description Payment batches use payment-rows and return 409 IMPORT_KIND_UNSUPPORTED here. Other tenants return 404 before kind dispatch.
         */
        get: operations["listImportRows"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/imports/{id}/payment-rows": {
        parameters: {
            query?: {
                /** @description Last record or error number from the preceding page; zero starts the snapshot */
                cursor?: components["parameters"]["ImportCursor"];
                /** @description Maximum items per page */
                limit?: components["parameters"]["ImportLimit"];
            };
            header?: never;
            path: {
                /** @description Server-issued batch ID */
                id: components["parameters"]["ImportId"];
            };
            cookie?: never;
        };
        /**
         * Read immutable payment preview rows in original record order
         * @description Payment batches only. Other kinds and other tenants return 404. Original input is retained for invalid fields; valid amounts are canonical integer strings.
         */
        get: operations["listPaymentImportRows"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/imports/{id}/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Server-issued batch ID */
                id: components["parameters"]["ImportId"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Confirm the immutable customer, invoice or payment snapshot atomically
         * @description Accepts only the preview version, never replacement rows. Customer, invoice or immutable payment facts, initial invoice revisions, audits and batch confirmation share one transaction. Payment transaction IDs remain reserved after allocation reversal. Invoice customer versions are revalidated under row locks. Re-sending the original preview version after success returns the same result, even after expiry. A conflicting preview is invalidated only after the business transaction rolls back; re-preview the corrected CSV.
         */
        post: operations["confirmImport"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/imports/{id}/errors": {
        parameters: {
            query?: {
                /** @description Last record or error number from the preceding page; zero starts the snapshot */
                cursor?: components["parameters"]["ImportCursor"];
                /** @description Maximum items per page */
                limit?: components["parameters"]["ImportLimit"];
            };
            header?: never;
            path: {
                /** @description Server-issued batch ID */
                id: components["parameters"]["ImportId"];
            };
            cookie?: never;
        };
        /** Read all correction details through bounded pages */
        get: operations["listImportErrors"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/imports/{id}/errors.csv": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Server-issued batch ID */
                id: components["parameters"]["ImportId"];
            };
            cookie?: never;
        };
        /**
         * Download the complete spreadsheet-safe error snapshot
         * @description UTF-8 BOM CSV with row_number,field,input_value,input_truncated,reason,correction. This report is for correction guidance, not a re-import file. Formula-like values are neutralized.
         */
        get: operations["downloadImportErrors"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/session": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Read the authenticated user and issue a CSRF token
         * @description 認証済み User の DB 所属、role、現在の CSRF token を返す。session に古い所属情報だけを保持しても、その値は権限根拠にしない。
         */
        get: operations["getSession"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Invalidate the CSRF session
         * @description CSRF 検証後に session と SecurityContext を失効する。旧 cookie と token は再利用できない。
         */
        post: operations["logout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/customers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List customers in the authenticated tenant
         * @description 顧客コードの昇順。Tenant header/body によるスコープ変更はできない。
         */
        get: operations["listCustomers"];
        put?: never;
        /** Register a customer and its audit event atomically */
        post: operations["createCustomer"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/customers/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Update a customer with its expected version
         * @description id は同じ Tenant に属する必要がある。他 Tenant の id も存在しない id と同じ 404。
         *     更新・version 加算・操作履歴を同じ transaction で確定する。
         */
        put: operations["updateCustomer"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/csrf": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Issue an anonymous CSRF session token */
        get: operations["getCsrf"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/auth/login": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Authenticate persisted user and rotate the session
         * @description 成功時は204。以前の session ID と CSRF token を再利用せず、GET /api/session から新しい token を取得する。失敗は未登録emailと誤passwordで区別しない。
         */
        post: operations["login"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/invitations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Issue a one-use manual link for a member in the current tenant
         * @description ADMIN only. Tenant is fixed from the current DB membership. The fragment URL is returned once; no email is sent. It expires after24hours. Existing global email conflicts return a generic409 without identifying another tenant. This incremental API exposes issuance only; anonymous inspection and acceptance are added by the next PR.
         */
        post: operations["issueInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/invitations/inspect": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Inspect minimal invitation information using a bearer token
         * @description Anonymous accessible with CSRF session cookie/header. Token is sent only in the POST body; no token in request URL or audit. Invalid/expired/used tokens share a generic error.
         */
        post: operations["inspectInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/invitations/accept": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Accept an invitation and create a user atomically
         * @description Anonymous accessible with CSRF session cookie/header. Token is sent only in the POST body; no token in request URL or audit. Invalid/expired/used tokens share a generic error.
         */
        post: operations["acceptInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        CollectionActionRequest: {
            /** Format: int64 */
            version: number;
            /** @description Shared nonblank note; line breaks and tabs are allowed */
            note: string;
            /**
             * Format: date
             * @description Required member. Send the current date to retain it, a replacement date to change it, or explicit null to clear it. Missing is rejected without mutation.
             */
            nextActionDate: string | null;
        };
        CollectionReasonRequest: {
            /** Format: int64 */
            version: number;
            reason: string;
        };
        CollectionStateResponse: {
            /** Format: uuid */
            invoiceId: string;
            /** Format: int64 */
            version: number;
            held: boolean;
            holdReason: string | null;
            latestNote: string | null;
            /** Format: date */
            nextActionDate: string | null;
            event: components["schemas"]["CollectionHistoryEntry"];
        };
        CollectionDraftResponse: {
            /** Format: uuid */
            invoiceId: string;
            /** Format: date */
            asOf: string;
            customerName: string;
            /** Format: int64 */
            outstandingAmount: number;
            subject: string;
            body: string;
            held: boolean;
            holdReason: string | null;
        };
        CollectionCaseResponse: {
            /** Format: uuid */
            invoiceId: string;
            /** Format: uuid */
            customerId: string;
            customerCode: string;
            customerName: string;
            invoiceNumber: string;
            /** Format: date */
            issueDate: string;
            /** Format: date */
            dueDate: string;
            /** @enum {string} */
            invoiceStatus: "ACTIVE" | "CANCELLED";
            /** Format: int64 */
            invoiceVersion: number;
            /** Format: int64 */
            amount: number;
            /** Format: int64 */
            cashAllocated: number;
            /** Format: int64 */
            explicitFees: number;
            /**
             * Format: int64
             * @description Effective collection balance is zero for cancelled invoices; amount preserves the original invoice facts.
             */
            outstandingAmount: number;
            /**
             * Format: date
             * @description Explicit Asia/Tokyo business date
             */
            asOf: string;
            daysOverdue: number;
            /** @description Active and overdue with a positive real outstanding balance; held candidates remain eligible */
            eligible: boolean;
            held: boolean;
            holdReason: string | null;
            latestNote: string | null;
            /** Format: date */
            nextActionDate: string | null;
            /**
             * Format: int64
             * @description Independent collection state version
             */
            version: number;
        };
        CollectionsPage: {
            items: components["schemas"]["CollectionCaseResponse"][];
            nextCursor: string | null;
        };
        CollectionHistoryEntry: {
            /** Format: int64 */
            version: number;
            /** @enum {string} */
            kind: "NOTE" | "HOLD" | "RELEASE";
            /** @description Shared note snapshot following this event */
            note: string | null;
            reason: string | null;
            /** Format: uuid */
            actorUserId: string;
            /** @description Same-tenant human-readable actor label captured at event time */
            actorLabel: string;
            /** Format: date-time */
            occurredAt: string;
            held: boolean;
            holdReason: string | null;
            /** Format: date */
            nextActionDate: string | null;
        };
        CollectionHistoryPage: {
            items: components["schemas"]["CollectionHistoryEntry"][];
            /** Format: int64 */
            nextCursor: number | null;
        };
        InvoiceCancelRequest: {
            /** Format: int64 */
            version: number;
            reason: string;
        };
        InvoiceCorrectionRequest: {
            /** Format: int64 */
            version: number;
            /** Format: uuid */
            customerId: string;
            /** Format: date */
            issueDate: string;
            /** Format: date */
            dueDate: string;
            /** Format: int64 */
            amount: number;
            reason: string;
        };
        InvoiceResponse: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            customerId: string;
            customerCode: string;
            customerName: string;
            invoiceNumber: string;
            /** Format: date */
            issueDate: string;
            /** Format: date */
            dueDate: string;
            /** Format: int64 */
            amount: number;
            /** Format: int64 */
            outstandingAmount: number;
            /** Format: int64 */
            version: number;
            /** @enum {string} */
            status: "ACTIVE" | "CANCELLED";
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            updatedAt: string;
            /** Format: date-time */
            cancelledAt: string | null;
        };
        InvoiceHistorySnapshot: {
            /** Format: uuid */
            customerId: string;
            customerCode: string;
            customerName: string;
            invoiceNumber: string;
            /** Format: date */
            issueDate: string;
            /** Format: date */
            dueDate: string;
            /** Format: int64 */
            amount: number;
            /** @enum {string} */
            status: "ACTIVE" | "CANCELLED";
            /** Format: uuid */
            sourceBatchId: string;
            /** Format: int64 */
            version: number;
        };
        InvoiceHistoryEntry: {
            /** Format: int64 */
            revision: number;
            /** @enum {string} */
            operation: "CREATED" | "CORRECTED" | "CANCELLED";
            reason: string | null;
            /** Format: uuid */
            actorUserId: string;
            /** Format: date-time */
            occurredAt: string;
            snapshot: components["schemas"]["InvoiceHistorySnapshot"];
            /**
             * Format: email
             * @description Current email label of the actor in the same tenant, including disabled users. actorUserId remains authoritative; this display label is not a historical snapshot and may change without rewriting the revision or audit.
             */
            actorEmail: string;
        };
        InvoiceHistoryPage: {
            items: components["schemas"]["InvoiceHistoryEntry"][];
            /** Format: int64 */
            nextCursor: number | null;
        };
        ReceivableResponse: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            customerId: string;
            customerCode: string;
            customerName: string;
            invoiceNumber: string;
            /** Format: date */
            issueDate: string;
            /** Format: date */
            dueDate: string;
            /** Format: int64 */
            amount: number;
            /** Format: int64 */
            outstandingAmount: number;
            /** Format: int64 */
            version: number;
            /** @enum {string} */
            status: "ACTIVE" | "CANCELLED";
        };
        ReceivablesPage: {
            items: components["schemas"]["ReceivableResponse"][];
            nextCursor: string | null;
        };
        PayerAliasCreateRequest: {
            /** @description Surrounding whitespace only is removed; 1 through 200 code points after stripping, using the payment CSV text rule. */
            payerName: string;
            /** Format: uuid */
            customerId: string;
        };
        PayerAliasDeactivateRequest: {
            /** Format: int64 */
            version: number;
            /** @description Required explanation, 1 through 500 code points after stripping; controls and line separators are rejected before stripping. */
            reason: string;
        };
        PayerAliasResponse: {
            /** Format: uuid */
            id: string;
            payerName: string;
            /** Format: uuid */
            customerId: string;
            active: boolean;
            /** Format: int64 */
            version: number;
            /** Format: uuid */
            createdByUserId: string;
            /** Format: date-time */
            createdAt: string;
            /** Format: uuid */
            deactivatedByUserId: string | null;
            /** Format: date-time */
            deactivatedAt: string | null;
            deactivationReason: string | null;
        };
        PayerAliasesPage: {
            items: components["schemas"]["PayerAliasResponse"][];
            /** Format: uuid */
            nextCursor: string | null;
        };
        PaymentResponse: {
            /** Format: uuid */
            id: string;
            transactionId: string;
            /** Format: date */
            transferDate: string;
            payerName: string;
            /** Format: int64 */
            amount: number;
            /**
             * Format: int64
             * @description Equals amount only while allocation ledger is absent; must be derived from active cash allocations when reconciliation is implemented.
             */
            unallocatedAmount: number;
            /** Format: uuid */
            sourceBatchId: string;
            /** Format: uuid */
            createdByUserId: string;
            /** Format: date-time */
            createdAt: string;
        };
        PaymentsPage: {
            items: components["schemas"]["PaymentResponse"][];
            nextCursor: string | null;
        };
        ImportConfirmRequest: {
            /** Format: int64 */
            version: number;
        };
        ImportBatchResponse: {
            /** Format: uuid */
            id: string;
            /** @enum {string} */
            kind: "CUSTOMER" | "INVOICE" | "PAYMENT";
            /** @enum {string} */
            status: "PREVIEW_VALID" | "PREVIEW_INVALID" | "CONFIRMED";
            /** Format: int64 */
            version: number;
            rowCount: number;
            errorCount: number;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            expiresAt: string;
            /** Format: date-time */
            confirmedAt: string | null;
            confirmedCount: number;
            /**
             * Format: uuid
             * @description An already-confirmed batch in this tenant with the same semantic content; absent or null when none exists. The current confirmed batch does not refer to itself.
             */
            originalBatchId?: string | null;
        };
        ImportRowResponse: {
            recordNumber: number;
            rowNumber: number;
            customerCode: string;
            customerName: string;
            invoiceNumber?: string | null;
            issueDate?: string | null;
            dueDate?: string | null;
            /** @description Invoice preview value, canonical integer text when valid; original input when invalid. Not a mutable balance. */
            amount?: string | null;
            valid: boolean;
        };
        PaymentImportRowResponse: {
            recordNumber: number;
            rowNumber: number;
            transactionId: string;
            transferDate: string;
            payerName: string;
            /** @description Canonical positive integer JPY when valid; original input when invalid. Not a mutable balance. */
            amount: string;
            valid: boolean;
        };
        PaymentImportRowsPage: {
            items: components["schemas"]["PaymentImportRowResponse"][];
            nextCursor: number;
        };
        ImportErrorResponse: {
            errorNumber: number;
            rowNumber: number;
            field: string;
            inputValue: string;
            inputTruncated: boolean;
            reasonCode: string;
            reason: string;
            correction: string;
        };
        ImportRowsPage: {
            items: components["schemas"]["ImportRowResponse"][];
            nextCursor: number;
        };
        ImportErrorsPage: {
            items: components["schemas"]["ImportErrorResponse"][];
            nextCursor: number;
        };
        SessionResponse: {
            /** Format: uuid */
            userId: string;
            /** Format: email */
            email: string;
            /** @enum {string} */
            role: "ADMIN" | "ACCOUNTANT";
            /** Format: uuid */
            tenantId: string;
            csrfToken: string;
            /** @example X-CSRF-TOKEN */
            csrfHeaderName: string;
        };
        CustomerResponse: {
            /** Format: uuid */
            id: string;
            code: string;
            name: string;
            /** Format: int64 */
            version: number;
        };
        /** @description Surrounding whitespace is stripped; blank/control characters are rejected */
        CustomerCreateRequest: {
            code: string;
            name: string;
        };
        CustomerUpdateRequest: {
            code: string;
            name: string;
            /** Format: int64 */
            version: number;
        };
        ApiError: {
            code: string;
            message: string;
            errors: components["schemas"]["FieldError"][];
        };
        FieldError: {
            field: string;
            message: string;
        };
        CsrfResponse: {
            csrfToken: string;
            /** @example X-CSRF-TOKEN */
            csrfHeaderName: string;
        };
        AuthLoginRequest: {
            /** @description ASCII email; surrounding whitespace stripped and lowercased for lookup */
            email: string;
            /**
             * Format: password
             * @description No trim/normalization; maximum512UTF-8bytes. Creation policy is separate from login verification.
             */
            password: string;
        };
        InvitationIssueRequest: {
            /** @description ASCII email; surrounding whitespace stripped and lowercased by the domain */
            email: string;
            /** @enum {string} */
            role: "ADMIN" | "ACCOUNTANT";
        };
        InvitationIssuedResponse: {
            /** Format: uuid */
            id: string;
            /** Format: date-time */
            expiresAt: string;
            /**
             * Format: password
             * @description Sensitive relative fragment URL /accept-invitation#token=...; returned once. Password format redacts generated DTO diagnostics; response value remains readable for manual sharing.
             */
            acceptanceUrl: string;
        };
        InvitationInspectRequest: {
            /**
             * Format: password
             * @description Secret canonical32-byte base64url bearer token, unpadded43characters. Never log or put in a request URL.
             */
            token: string;
        };
        InvitationInspectionResponse: {
            tenantDisplayName: string;
            /** @description Entire local part hidden; full invited email is not returned */
            maskedEmail: string;
            /** @enum {string} */
            role: "ADMIN" | "ACCOUNTANT";
            /** Format: date-time */
            expiresAt: string;
        };
        InvitationAcceptRequest: {
            /**
             * Format: password
             * @description Secret canonical32-byte base64url bearer token, unpadded43characters. Never log or put in a request URL.
             */
            token: string;
            /** @description ASCII email; surrounding whitespace stripped and lowercased by the domain */
            email: string;
            /**
             * Format: password
             * @description 12..128 Unicode code points, at most512UTF-8bytes; no trim/normalization
             */
            password: string;
        };
        InvitationAcceptedResponse: {
            /** Format: uuid */
            userId: string;
            /** Format: email */
            email: string;
            /** @enum {string} */
            role: "ADMIN" | "ACCOUNTANT";
        };
    };
    responses: {
        /** @description Invoice not found in the authenticated tenant, including deliberately hidden cross-tenant IDs */
        InvoiceNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ApiError"];
            };
        };
        /** @description Batch not found in the authenticated tenant */
        ImportNotFound: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ApiError"];
            };
        };
        /** @description Invalid fields or malformed JSON (VALIDATION_ERROR or INVALID_REQUEST) */
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ApiError"];
            };
        };
        /** @description Missing/expired session or invalid login credentials (UNAUTHENTICATED); login never distinguishes unknown email from wrong password */
        Unauthorized: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ApiError"];
            };
        };
        /** @description CSRF token/session mismatch (CSRF_INVALID) or authenticated permission/API access denial (ACCESS_DENIED). These codes distinguish stale session handling from role restrictions. */
        Forbidden: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ApiError"];
            };
        };
    };
    parameters: {
        /** @description Server-issued batch ID */
        ImportId: string;
        /** @description Last record or error number from the preceding page; zero starts the snapshot */
        ImportCursor: number;
        /** @description Maximum items per page */
        ImportLimit: number;
    };
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    recordCollectionAction: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CollectionActionRequest"];
            };
        };
        responses: {
            /** @description New collection state and its immutable event */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CollectionStateResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["InvoiceNotFound"];
            /** @description VERSION_CONFLICT, COLLECTION_ALREADY_HELD or COLLECTION_NOT_HELD */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    holdCollection: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CollectionReasonRequest"];
            };
        };
        responses: {
            /** @description New collection state and its immutable event */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CollectionStateResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["InvoiceNotFound"];
            /** @description VERSION_CONFLICT, COLLECTION_ALREADY_HELD or COLLECTION_NOT_HELD */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    releaseCollection: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CollectionReasonRequest"];
            };
        };
        responses: {
            /** @description New collection state and its immutable event */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CollectionStateResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["InvoiceNotFound"];
            /** @description VERSION_CONFLICT, COLLECTION_ALREADY_HELD or COLLECTION_NOT_HELD */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    previewCollectionDraft: {
        parameters: {
            query: {
                asOf: string;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Copyable subject and body; a human must check the recipient and content */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CollectionDraftResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["InvoiceNotFound"];
            /** @description COLLECTION_NOT_ELIGIBLE for non-overdue, paid or cancelled invoices */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    listCollections: {
        parameters: {
            query: {
                asOf: string;
                view?: "ACTIONABLE" | "HELD" | "ALL";
                cursor?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description At most limit active, overdue, positive balances */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CollectionsPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    getCollection: {
        parameters: {
            query: {
                asOf: string;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current invoice, real ledger balance and retained follow-up state */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CollectionCaseResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["InvoiceNotFound"];
        };
    };
    listCollectionHistory: {
        parameters: {
            query?: {
                cursor?: number;
                limit?: number;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Follow-up events ordered by their independent version */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CollectionHistoryPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["InvoiceNotFound"];
        };
    };
    getInvoice: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current invoice facts and derived outstanding amount */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InvoiceResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["InvoiceNotFound"];
        };
    };
    correctInvoice: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InvoiceCorrectionRequest"];
            };
        };
        responses: {
            /** @description Corrected invoice and incremented version */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InvoiceResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["InvoiceNotFound"];
            /** @description VERSION_CONFLICT, INVOICE_NOT_ACTIVE or INVOICE_ALLOCATED; reload or reverse the active settlement first. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    cancelInvoice: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InvoiceCancelRequest"];
            };
        };
        responses: {
            /** @description Cancelled invoice with zero outstanding amount and retained original amount */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InvoiceResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["InvoiceNotFound"];
            /** @description VERSION_CONFLICT, INVOICE_NOT_ACTIVE or INVOICE_ALLOCATED */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    listInvoiceHistory: {
        parameters: {
            query?: {
                cursor?: number;
                limit?: number;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description At most limit revisions; null nextCursor indicates the end */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InvoiceHistoryPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["InvoiceNotFound"];
        };
    };
    listReceivables: {
        parameters: {
            query?: {
                customerId?: string;
                dueFrom?: string;
                dueTo?: string;
                /** @description Exact nextCursor from the preceding page; omit for the first page. */
                cursor?: string;
                limit?: number;
                /** @description Invoice state to browse; omitting this parameter preserves the active-only list. */
                status?: "ACTIVE" | "CANCELLED" | "ALL";
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Matching invoices with their status, original amount and outstanding amount */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReceivablesPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    listPayerAliases: {
        parameters: {
            query?: {
                cursor?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Active correspondences only; nextCursor is null at the end */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PayerAliasesPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    createPayerAlias: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PayerAliasCreateRequest"];
            };
        };
        responses: {
            /** @description Correspondence and registration audit saved atomically */
            201: {
                headers: {
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PayerAliasResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            /** @description Customer does not exist in the authenticated tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
            /** @description Active payer/customer pair already exists */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    getPayerAlias: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Active or deactivated correspondence */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PayerAliasResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            /** @description Correspondence not found in this tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    deactivatePayerAlias: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PayerAliasDeactivateRequest"];
            };
        };
        responses: {
            /** @description Deactivated correspondence */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PayerAliasResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            /** @description Correspondence not found in this tenant */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
            /** @description Stale version or already deactivated */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    listPayments: {
        parameters: {
            query?: {
                dateFrom?: string;
                dateTo?: string;
                /** @description Exact nextCursor from the preceding page; omit for the first page. */
                cursor?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Incoming payment facts and their unallocated amounts */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PaymentsPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    downloadCustomerImportTemplate: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Fixed-format CSV template */
            200: {
                headers: {
                    /** @description Attachment filename customers-template.csv */
                    "Content-Disposition"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "text/csv": string;
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    previewCustomerImport: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
                };
            };
        };
        responses: {
            /** @description Immutable valid or invalid preview; business rows remain unchanged */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ImportBatchResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            /** @description Multipart upload exceeds its bounded size */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    downloadInvoiceImportTemplate: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Fixed-format CSV template */
            200: {
                headers: {
                    /** @description Attachment filename invoices-template.csv */
                    "Content-Disposition"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "text/csv": string;
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    previewInvoiceImport: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
                };
            };
        };
        responses: {
            /** @description Immutable valid or invalid preview; business rows remain unchanged */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ImportBatchResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            /** @description Multipart upload exceeds its bounded size */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    downloadPaymentImportTemplate: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Fixed-format CSV template */
            200: {
                headers: {
                    /** @description Attachment filename payments-template.csv */
                    "Content-Disposition"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "text/csv": string;
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    previewPaymentImport: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
                };
            };
        };
        responses: {
            /** @description Immutable valid or invalid preview; business rows remain unchanged */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ImportBatchResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            /** @description Multipart upload exceeds its bounded size */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    getImportBatch: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Server-issued batch ID */
                id: components["parameters"]["ImportId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current server-side batch state */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ImportBatchResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ImportNotFound"];
        };
    };
    listImportRows: {
        parameters: {
            query?: {
                /** @description Last record or error number from the preceding page; zero starts the snapshot */
                cursor?: components["parameters"]["ImportCursor"];
                /** @description Maximum items per page */
                limit?: components["parameters"]["ImportLimit"];
            };
            header?: never;
            path: {
                /** @description Server-issued batch ID */
                id: components["parameters"]["ImportId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description At most limit rows; nextCursor is zero at the end */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ImportRowsPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ImportNotFound"];
            /** @description IMPORT_KIND_UNSUPPORTED; use payment-rows for payment batches. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    listPaymentImportRows: {
        parameters: {
            query?: {
                /** @description Last record or error number from the preceding page; zero starts the snapshot */
                cursor?: components["parameters"]["ImportCursor"];
                /** @description Maximum items per page */
                limit?: components["parameters"]["ImportLimit"];
            };
            header?: never;
            path: {
                /** @description Server-issued batch ID */
                id: components["parameters"]["ImportId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description At most limit rows; nextCursor is zero at the end */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PaymentImportRowsPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ImportNotFound"];
        };
    };
    confirmImport: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Server-issued batch ID */
                id: components["parameters"]["ImportId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ImportConfirmRequest"];
            };
        };
        responses: {
            /** @description Confirmed batch result, including idempotent retries */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ImportBatchResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ImportNotFound"];
            /** @description IMPORT_INVALID, IMPORT_EXPIRED, VERSION_CONFLICT, IMPORT_REFERENCE_CONFLICT, IMPORT_ALREADY_CONFIRMED or IMPORT_KIND_UNSUPPORTED. For confirmed content in a different batch, errors contains field batchId and message with the original UUID. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    listImportErrors: {
        parameters: {
            query?: {
                /** @description Last record or error number from the preceding page; zero starts the snapshot */
                cursor?: components["parameters"]["ImportCursor"];
                /** @description Maximum items per page */
                limit?: components["parameters"]["ImportLimit"];
            };
            header?: never;
            path: {
                /** @description Server-issued batch ID */
                id: components["parameters"]["ImportId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description At most limit errors; nextCursor is zero at the end */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ImportErrorsPage"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ImportNotFound"];
        };
    };
    downloadImportErrors: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Server-issued batch ID */
                id: components["parameters"]["ImportId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description All stored errors, matching the paginated error API */
            200: {
                headers: {
                    /** @description Attachment filename import-errors.csv */
                    "Content-Disposition"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "text/csv": string;
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["ImportNotFound"];
        };
    };
    getSession: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authenticated user and CSRF token */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SessionResponse"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    logout: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Session invalidated; no response body */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    listCustomers: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Tenant-scoped customer list */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CustomerResponse"][];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
        };
    };
    createCustomer: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CustomerCreateRequest"];
            };
        };
        responses: {
            /** @description Customer registered */
            201: {
                headers: {
                    /** @description Relative URI of the created customer */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CustomerResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            /** @description Customer code is already registered in this tenant (DUPLICATE_CUSTOMER_CODE) */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    updateCustomer: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CustomerUpdateRequest"];
            };
        };
        responses: {
            /** @description Updated customer with incremented version */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CustomerResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            /** @description Customer does not exist in the authenticated tenant (NOT_FOUND) */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
            /** @description Stale version (VERSION_CONFLICT) or duplicate code (DUPLICATE_CUSTOMER_CODE) */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    getCsrf: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description CSRF token and HttpOnly session cookie */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CsrfResponse"];
                };
            };
            403: components["responses"]["Forbidden"];
        };
    };
    login: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AuthLoginRequest"];
            };
        };
        responses: {
            /** @description Authenticated; session cookie rotated; no response body */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            /** @description Validated login attempts temporarily limited (RATE_LIMITED); retry after1..60seconds. Cookie replacement does not reset the budget. */
            429: {
                headers: {
                    /** @description Whole seconds until retry; bounded between1and60 */
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    issueInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InvitationIssueRequest"];
            };
        };
        responses: {
            /** @description One-time secret fragment URL; Cache-Control:no-store */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InvitationIssuedResponse"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            /** @description Invitation cannot be issued (INVITATION_UNAVAILABLE); no tenant membership details */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
    inspectInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InvitationInspectRequest"];
            };
        };
        responses: {
            /** @description Invitation information */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InvitationInspectionResponse"];
                };
            };
            /** @description Malformed input (VALIDATION_ERROR/INVALID_REQUEST) or invalid, expired, consumed, mismatched invitation (INVITATION_INVALID); token-state errors have the same public response */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
            403: components["responses"]["Forbidden"];
        };
    };
    acceptInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InvitationAcceptRequest"];
            };
        };
        responses: {
            /** @description User created; invitation consumed; not automatically logged in */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InvitationAcceptedResponse"];
                };
            };
            /** @description Malformed input (VALIDATION_ERROR/INVALID_REQUEST) or invalid, expired, consumed, mismatched invitation (INVITATION_INVALID); token-state errors have the same public response */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
            403: components["responses"]["Forbidden"];
            /** @description Cannot accept invitation (INVITATION_UNAVAILABLE); includes global email conflict without identifying another tenant */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApiError"];
                };
            };
        };
    };
}
