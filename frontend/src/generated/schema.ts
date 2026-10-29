/**
 * Generated from contracts/openapi.yaml. Do not edit directly.
 * Run npm run generate:api from frontend/.
 */

export interface paths {
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
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
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
    };
    responses: {
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
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
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
        };
    };
}
