# NMK Opticals — Phase 1 MySQL Inspection Setup

**Status:** Preparation guide only; no database connection or account setup has been performed.  
**Repository:** `Elavarasany/nmk-backend`, branch `master`  
**Purpose:** Prepare a secure, read-only connection to the intended Aiven MySQL database before carrying out the inspection checklist.  
**Companion document:** `PHASE-1-MYSQL-LIVE-DATABASE-INSPECTION.md`

> **Safety boundary:** This guide does not authorize production writes, schema changes, migrations, data corrections, exports, or application configuration changes. Never paste passwords, private keys, full credential-bearing connection strings, customer records, prescription contents, or payment secrets into ChatGPT, GitHub, screenshots, or tickets. Do not commit certificates or credentials to this repository.

## 1. What must be ready before connecting

Obtain the following from the NMK database owner / Aiven service console. Record them in a private password manager or approved internal record, not in this repository:

- The exact Aiven service and environment (production, staging, or other).
- The intended database name.
- The database host and port shown by Aiven.
- The dedicated inspection username.
- The password delivered through a secure channel.
- The Aiven CA certificate required to verify the server certificate.
- Confirmation that the account is restricted to read-only access on the intended database.

Do not assume that a database name, host, port, or account from a local development environment points to the intended live database. Confirm the target with the owner before connecting.

## 2. Create a dedicated read-only inspection identity

Ask the database owner or authorized database administrator to create a separate account for inspection. Do not reuse the Spring Boot application's database account, an administrator account, or a personal account.

The account should have only the permissions required for the approved inspection:

- `SELECT` on the intended database's tables.
- `SHOW VIEW` only if views must be inspected and the server requires it.
- No `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `ALTER`, `DROP`, `INDEX`, `TRIGGER`, `EVENT`, `GRANT OPTION`, or administrative privileges.
- No access to unrelated databases.

The database administrator must choose the correct host restriction and account syntax for the Aiven service. Do not copy a generic `CREATE USER` or `GRANT` command into production without checking the provider's current MySQL guidance and the service's actual privilege model. If Aiven's managed-user controls are used, verify the resulting grants afterward.

After creation, have the administrator provide the username and password securely. The password must not be placed in a command line, shell history, source file, environment file committed to Git, or chat message.

## 3. Obtain and protect the Aiven CA certificate

1. Open the intended MySQL service in the Aiven Console.
2. Confirm the service name and environment with the database owner.
3. Locate the service's connection information and CA certificate download.
4. Download the CA certificate using the provider's current instructions.
5. Store it in a private local folder with access limited to the operator account.
6. Do not upload it to GitHub or include it in a public issue or chat.

Use the certificate supplied for the selected service. Do not disable TLS certificate verification to make a connection succeed. If certificate verification fails, stop and resolve the hostname, certificate, CA, or system-clock issue with the provider documentation or administrator.

## 4. Choose a MySQL client

Use one approved client:

- **MySQL Workbench:** suitable for a visual schema review.
- **MySQL command-line client:** suitable for carefully controlled read-only queries.
- Another organization-approved MySQL client is acceptable if it supports TLS certificate verification and does not store credentials insecurely.

Install or update the client from its official source. Do not download database tools from untrusted mirrors.

## 5. Configure a TLS-verified connection

Use the values shown for the intended Aiven service:

| Connection field | Value to obtain |
|---|---|
| Hostname | Aiven service hostname |
| Port | Aiven service port |
| Default schema | Confirmed NMK database name |
| Username | Dedicated read-only inspection account |
| Password | Enter through the client's secure password prompt |
| TLS / SSL | Required |
| CA certificate | Downloaded Aiven CA certificate |
| Certificate verification | Enabled; verify the server identity/hostname |

### MySQL Workbench

1. Create a new connection profile; give it a clear local name such as `NMK - READ ONLY INSPECTION`.
2. Enter the confirmed hostname, port, username, and default schema.
3. Configure SSL/TLS to require an encrypted connection and use the downloaded CA certificate.
4. Select certificate identity/hostname verification when the client version exposes that option.
5. Do not save the password in the connection profile unless the machine and credential store are explicitly approved for that purpose.
6. Use **Test Connection** only after confirming the target details and account grants.

Workbench labels and TLS options vary by version. If the installed version cannot verify the server certificate and hostname, use a client that can; do not downgrade to an unverified connection.

### MySQL command-line client (Windows example)

Use the Aiven hostname, port, database, username, and local CA file from the approved service details. The following is a template, not a ready-to-run command:

```powershell
mysql --host=<AIVEN_HOST> --port=<AIVEN_PORT> --user=<READ_ONLY_USER> --password --ssl-mode=VERIFY_IDENTITY --ssl-ca="<PATH_TO_AIVEN_CA.pem>" <DATABASE_NAME>
```

Replace each placeholder locally. The `--password` option with no value prompts for the password; do not append the password to the command. Confirm that the installed MySQL client supports `VERIFY_IDENTITY`. If it does not, update the client rather than disabling verification.

## 6. Verify the connection target before inspecting anything

After connecting, run only these harmless metadata queries:

```sql
SELECT
    DATABASE() AS selected_database,
    CURRENT_USER() AS authenticated_account,
    USER() AS client_identity,
    VERSION() AS mysql_version;

SHOW GRANTS;
```

Check all of the following before proceeding:

- `selected_database` is exactly the database approved for inspection.
- `authenticated_account` is the dedicated inspection identity, not the application or administrator account.
- The server version is recorded for the inspection report.
- `SHOW GRANTS` shows only the approved read-only permissions and no write, DDL, privilege-management, or administrative grants.
- The connection is using TLS with certificate verification, as shown by the client connection details.

If any value is unexpected, stop and disconnect. Do not run the inspection queries until the owner or administrator has resolved the discrepancy.

## 7. Confirm read-only scope

A successful `SELECT` query does not by itself prove that an account lacks write permissions. Review `SHOW GRANTS` with the database administrator and confirm the account's effective privileges.

Do not test permissions by attempting an `INSERT`, `UPDATE`, `DELETE`, DDL statement, or transaction that writes and rolls back. A rollback is not an acceptable safety control for a production inspection.

If the account has any unexpected privilege, do not use it. Have the administrator correct the grants and verify them again.

## 8. Harmless smoke test

Only after the target and grants have been verified, run:

```sql
SELECT 1 AS read_only_connection_test;
```

Expected result: one row containing `1`.

This confirms that a simple read query works. It does not prove that every required schema object is visible or that the account is correctly restricted; retain the separate grant review as a required check.

## 9. Stop conditions

Stop immediately and do not proceed to schema inspection if:

- The database environment or database name cannot be confirmed.
- The connection is not encrypted or certificate/hostname verification fails.
- The account is shared with the application or has unexpected privileges.
- The selected database is not the approved target.
- The account can access unrelated databases beyond the approved scope.
- The service owner has not approved the inspection window.
- A backup and restore plan has not been confirmed as required by the inspection checklist.

Do not work around a stop condition by using an administrator account, disabling TLS checks, or changing production settings.

## 10. Record setup completion without secrets

Record only non-sensitive facts in the inspection report:

- Inspection date/time and timezone.
- Aiven service/environment label (do not include credentials).
- Database name, if approved for documentation.
- MySQL client and version.
- MySQL server version.
- Read-only username (optional; omit if considered sensitive).
- TLS enabled and CA/hostname verification passed: yes/no.
- `SHOW GRANTS` reviewed by: role/name according to internal policy.
- Target database and account verified: yes/no.
- Harmless `SELECT 1` test passed: yes/no.
- Any blockers or stop conditions.

Never record the password, connection URI containing credentials, private certificate key, customer data, prescription data, or raw production dumps.

## 11. Handoff to the live inspection checklist

When setup is complete, follow `PHASE-1-MYSQL-LIVE-DATABASE-INSPECTION.md` in order. That checklist governs the permitted metadata inventory and aggregate-only checks.

The next stage remains inspection and evidence gathering only. Do not write migration SQL, alter the schema, modify application code, or reconcile stock until the findings, backup/restore evidence, and owner approvals have been reviewed.
