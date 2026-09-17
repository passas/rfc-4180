package csv.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Strict streaming parser for CSV according to RFC 4180.
 * <p>
 * The parser:
 * <p>
 * - Reads directly from Reader/InputStream.
 * - Never loads the complete CSV into memory.
 * - Supports CRLF record separators.
 * - Allows the final record to omit CRLF.
 * - Supports quoted fields.
 * - Supports commas, CR and LF inside quoted fields.
 * - Supports escaped quotes ("").
 * - Preserves spaces.
 * - Rejects quotes inside unquoted fields.
 * - Rejects bare CR as a record separator; accepts LF and CRLF.
 * - Rejects invalid TEXTDATA characters.
 * - Rejects inconsistent field counts.
 * - Optionally treats the first record as a header.
 * <p>
 * Important:
 * A single record is accumulated in memory because the Consumer
 * receives a List<String>. The entire file is never accumulated.
 */
public final class Rfc4180Parser
{

    private Rfc4180Parser() {
    }

    /**
     * Parses a CSV from a Reader.
     *
     * @param reader    source reader
     * @param hasHeader whether the first record is a header
     * @param consumer  called once for every parsed record
     * @throws IOException       if reading fails
     * @throws CsvParseException if the CSV violates the format
     */
    public static void parse(Reader reader, char delimiter, boolean hasHeader, Consumer<List<String>> consumer) throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(consumer, "consumer");

        Rfc4180Parser.validateDelimiter(delimiter);

        Parser parser = new Parser(reader, delimiter, hasHeader, consumer);
        parser.parse();
    }

    /**
     * Parses a CSV from a Reader.
     */
    public static void parse(Reader reader, boolean hasHeader, Consumer<List<String>> consumer) throws IOException
    {
        Rfc4180Parser.parse(reader, ',', hasHeader, consumer);
    }

    /**
     * Parses a CSV from an InputStream using UTF-8.
     */
    public static void parse(InputStream input, boolean hasHeader, Consumer<List<String>> consumer) throws IOException
    {
        Rfc4180Parser.parse(input, StandardCharsets.UTF_8, ',', hasHeader, consumer);
    }

    /**
     * Parses a CSV from an InputStream using the supplied charset.
     */
    public static void parse(InputStream input, Charset charset, boolean hasHeader, Consumer<List<String>> consumer) throws IOException
    {
        Rfc4180Parser.parse(input, StandardCharsets.UTF_8, ',', hasHeader, consumer);
    }

    /**
     * Parses a CSV from an InputStream using the supplied charset.
     */
    public static void parse(InputStream input, Charset charset, char delimiter, boolean hasHeader, Consumer<List<String>> consumer) throws IOException
    {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(charset, "charset");

        try (Reader reader = new BufferedReader(new InputStreamReader(input, charset)))
        {
            Rfc4180Parser.parse(reader, delimiter, hasHeader, consumer);
        }
    }

    private static void validateDelimiter(char delimiter)
    {
        if (delimiter != ',' && delimiter != ';')
        {
            throw new IllegalArgumentException("CSV delimiter must be ',' or ';'");
        }
    }

    private static final class Parser
    {
        private static final int EOF = -1;

        private final char delimiter;
        private final Reader reader;
        private final boolean hasHeader;
        private final Consumer<List<String>> consumer;

        private long position;
        private long line = 1;
        private long column = 0;

        private int expectedFieldCount = -1;
        private int recordNumber;

        Parser(Reader reader, char delimeter, boolean hasHeader, Consumer<List<String>> consumer)
        {
            this.reader = reader;
            this.delimiter = delimeter;
            this.hasHeader = hasHeader;
            this.consumer = consumer;
        }

        /**
         * RFC 4180:
         * <p>
         * TEXTDATA =
         * %x20-21
         * / %x23-2B
         * / %x2D-7E
         */
        private static boolean isTextData(int c)
        {
            return (c >= 0x20 && c <= 0x21)
                    || (c >= 0x23 && c <= 0x2B)
                    || (c >= 0x2D && c <= 0x7E);
        }

        private static String printable(int c)
        {
            if (c == EOF)
            {
                return "EOF";
            }

            return String.format("0x%04X ('%s')", c, printableChar(c));
        }

        private static String printableChar(int c)
        {
            switch (c)
            {
                case '\r':
                    return "\\r";

                case '\n':
                    return "\\n";

                case '\t':
                    return "\\t";

                default:
                    return Character.toString((char) c);
            }
        }

        void parse() throws IOException
        {
            /*
             * RFC 4180-LF-CRLF:
             *
             * file = [header CRLF]
             *        record *(CRLF record)
             *        [CRLF]
             *
             * An empty file is therefore allowed.
             */
            int first = this.peek();

            if (first == EOF)
            {
                return;
            }

            boolean firstRecord = true;

            while (true)
            {
                List<String> record = this.parseRecord();

                this.recordNumber++;

                this.validateFieldCount(record);

                /*
                 * Make sure the Consumer cannot modify our internal
                 * mutable list after this parser continues.
                 */
                List<String> immutableRecord = List.copyOf(record);

                this.consumer.accept(immutableRecord);

                firstRecord = false;

                int next = this.peek();

                if (next == EOF)
                {
                    return;
                }

                /*
                 * A record must be followed by LF or CRLF.
                 */
                this.expectCrLf();

                /*
                 * A final CRLF is allowed.
                 */
                if (this.peek() == EOF)
                {
                    return;
                }
            }
        }

        private List<String> parseRecord() throws IOException
        {
            List<String> fields = new ArrayList<>();

            fields.add(this.parseField());

            while (this.peek() == this.delimiter)
            {
                this.read();

                fields.add(this.parseField());
            }

            return fields;
        }

        private String parseField() throws IOException
        {
            int c = this.peek();

            if (c == '"') {
                return this.parseEscapedField();
            }

            return this.parseNonEscapedField();
        }

        /**
         * escaped =
         * <p>
         * DQUOTE
         * *(TEXTDATA / COMMA / CR / LF / 2DQUOTE)
         * DQUOTE
         */
        private String parseEscapedField() throws IOException
        {
            this.expect('"');

            StringBuilder value = new StringBuilder();

            while (true)
            {
                int c = read();

                if (c == EOF)
                {
                    throw error("Unterminated quoted field");
                }

                if (c == '"')
                {
                    int next = this.peek();

                    /*
                     * 2DQUOTE
                     */
                    if (next == '"')
                    {
                        this.read();

                        value.append('"');

                        continue;
                    }

                    /*
                     * Closing quote.
                     */
                    break;
                }

                /*
                 * Inside an escaped field:
                 *
                 * TEXTDATA (SEMICOLUMN)
                 * COMMA
                 * CR
                 * LF
                 *
                 * are allowed.
                 */
                if (isTextData(c)
                        || c == ','         // || c == this.delimiter
                        || c == '\r'
                        || c == '\n')
                {
                    value.append((char) c);

                    continue;
                }

                throw error("Invalid character inside quoted field: " + printable(c));
            }

            /*
             * After a closing quote, only:
             *
             *  - comma
             *  - CRLF
             *  - EOF
             *
             * are valid.
             */
            int next = this.peek();

            if (next != this.delimiter
                    && next != '\r'
                    && next != EOF) {

                throw error("Unexpected character after closing quote: " + printable(next));
            }

            return value.toString();
        }

        /**
         * non-escaped = *TEXTDATA
         */
        private String parseNonEscapedField() throws IOException
        {
            StringBuilder value = new StringBuilder();

            while (true)
            {
                int c = this.peek();

                if (c == EOF
                        || c == ','
                        || c == '\r'
                        || c == '\n') {

                    return value.toString();
                }

                /*
                 * DQUOTE is forbidden in non-escaped fields.
                 */
                if (c == '"')
                {
                    throw error("Double quote is not allowed inside an unquoted field");
                }

                /*
                 * RFC 4180 TEXTDATA:
                 *
                 * %x20-21
                 * / %x23-2B
                 * / %x2D-7E
                 */
                if (!isTextData(c))
                {
                    throw error("Invalid character in unquoted field: " + printable(c));
                }

                value.append((char) read());
            }
        }

        /**
         * Validates:
         * <p>
         * - Header field count.
         * - Record field count.
         */
        private void validateFieldCount(List<String> record)
        {
            int count = record.size();

            if (this.expectedFieldCount == -1)
            {
                this.expectedFieldCount = count;

                return;
            }

            if (count != this.expectedFieldCount)
            {
                String type = this.hasHeader && this.recordNumber == 1 ? "Header" : "Record " + this.recordNumber;

                throw error(type + " contains " + count + " fields; expected " + this.expectedFieldCount);
            }
        }

        /**
         * Reads LF;
         * Reads CRLF.
         */
        private void expectCrLf() throws IOException
        {
            int c = this.read();

            // LF line ending
            if (c == '\n')
            {
                return;
            }

            // CRLF line ending
            if (c == '\r')
            {
                int next = this.peek();

                if (next == '\n')
                {
                    this.read();
                    return;
                }

                // Bare CR is not accepted.
                throw error("Expected LF after CR: CRLF");
            }

            throw error("Expected record separator (LF or CRLF) but found " + printable(c));
        }

        private int peek() throws IOException
        {
            this.reader.mark(1);

            int c = this.reader.read();

            this.reader.reset();

            return c;
        }

        private int read() throws IOException
        {
            int c = this.reader.read();

            if (c != EOF)
            {
                this.position++;

                if (c == '\n')
                {
                    this. line++;
                    this.column = 0;

                }
                else
                {
                    this.column++;
                }
            }

            return c;
        }

        private void expect(char expected) throws IOException
        {
            int c = this.read();

            if (c != expected)
            {
                throw error("Expected " + printable(expected) + " but found " + printable(c));
            }
        }

        private CsvParseException error(String message)
        {
            return new CsvParseException(message + " at line " + line + ", column " + column + ", position " + position);
        }
    }

    public static final class CsvParseException extends RuntimeException
    {
        public CsvParseException(String message)
        {
            super(message);
        }
    }
}