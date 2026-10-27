# Ledger CSV format

- UTF-8, optional BOM ("Excel compatibility" toggle), RFC 4180, `\r\n` line endings, header row.
- Columns (user may deselect any): `transaction_date, received_at, source, merchant, amount, currency, direction, status, note`
- Dates: ISO-8601 UTC with offset, e.g. `2026-10-03T12:15:00Z`. `transaction_date` falls back to `received_at`.
- `amount`: plain decimal, `.` separator, currency's own precision (`4.50` GBP, `1500` JPY, `1.234` KWD),
  locale-independent, no sign (see `direction`). Empty when unknown.
- `direction`: `debit` | `credit` | empty. `status`: `parsed` | `needs_review` | `ignored`.
- Text fields are quoted when they contain `,` `"` CR or LF; internal quotes are doubled.
- Formula-injection protection: a text field starting with `=` `+` `-` `@` TAB CR or `|` is prefixed with `'`.
  The app-generated `amount` column is exempt.
- File name: `ledger-export-YYYYMMDD.csv` (no personal data).

```csv
transaction_date,received_at,source,merchant,amount,currency,direction,status,note
2026-10-03T12:15:00Z,2026-10-03T12:15:04Z,Revolut,Coffee Shop,4.50,GBP,debit,parsed,
```
