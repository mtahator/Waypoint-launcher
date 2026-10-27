package app.olauncher.features.export.domain

object CsvSanitizer {
    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@', '\t', '\r', '|')

    /** Neutralizes spreadsheet formula injection by prefixing a quote to dangerous leading characters. */
    fun neutralize(value: String): String =
        if (value.isNotEmpty() && value[0] in FORMULA_PREFIXES) "'$value" else value

    /** RFC 4180 quoting: quote when the field contains `,` `"` CR or LF; double internal quotes. */
    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\""
        else value

    fun textField(value: String?): String = escape(neutralize(value.orEmpty()))
}
