package csv.exporter;

import csv.utils.CsvColumn;
import csv.parser.Rfc4180Parser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Streaming RFC 4180 CSV exporter for beans annotated with {@link CsvColumn}.
 * The fields annotated with {@link CsvColumn} determine the CSV columns.
 * Example:
 *  <pre>
 *  public class Person
 *  {
 *        @CsvColumn("id")
 *        private Long id;
 *
 *        @CsvColumn("first_name")
 *        private String firstName;
 *
 *        @CsvColumn("birth_date")
 *        private LocalDate birthDate;
 *  }
 *  </pre>
 *
 *  Produces:
 *  <pre>
 *      id,first_name,birth_date\r\n
 *      1,Priscilla,1998-10-29\r\n
 *      2,Audie,\r\n
 *  </pre>
 *
 *  The exporter always uses CRLF record separators.
 *  Null values are written as empty CSV fields.
 *  Fields containing comma, quote, CR or LF are quoted and quotes
 *  inside quoted fields are escaped according to RFC 4180.
 */
public final class Rfc4180BeanExporter
{
    private static final String CRLF = "\r\n";

    private Rfc4180BeanExporter() {
    }

    /**
     * Exports the supplied beans to a Writer.
     * The Writer is not closed by this method.
     *
     *  @param writer destination Writer
     *  @param delimiter value delimiter
     *  @param beanType bean class
     *  @param beans beans to export
     *  @param <T> bean type
     *  @throws IOException if writing fails
     */
    public static <T> void export(Writer writer, char delimiter, Class<T> beanType, Iterable<T> beans) throws IOException
    {
        if (writer == null)
        {
            throw new NullPointerException("writer");
        }
        if (beanType == null)
        {
            throw new NullPointerException("beanType");
        }
        if (beans == null)
        {
            throw new NullPointerException("beans");
        }

        Rfc4180BeanExporter.validateDelimiter(delimiter);

        BeanDefinition<T> definition = new BeanDefinition<>(beanType);

        Rfc4180BeanExporter.writeHeader(writer, delimiter, definition);

        for (T bean : beans)
        {
            if (bean == null)
            {
                throw new CsvExportException("Cannot export null bean");
            }
            Rfc4180BeanExporter.writeBean(writer, delimiter, definition, bean);
        }
    }

    public static <T> void export(Writer writer, Class<T> beanType, Iterable<T> beans) throws IOException
    {
        Rfc4180BeanExporter.export(writer, ',', beanType, beans);
    }

    /**
     * Exports the supplied beans to an OutputStream using UTF-8.
     * The OutputStream is closed by this method.
     *
     * @param output output stream
     * @param beanType bean class
     * @param beans beans to export
     * @param <T> bean type
     * @throws IOException if writing fails
     */
    public static <T> void export(OutputStream output, Class<T> beanType, Iterable<T> beans) throws IOException
    {
        Rfc4180BeanExporter.export(output, StandardCharsets.UTF_8, ',', beanType, beans);
    }

    /**
     * Exports the supplied beans to an OutputStream using the
     * specified charset.
     *
     * @param output output stream
     * @param charset charset
     * @param delimiter value delimiter
     * @param beanType bean class
     * @param beans beans to export
     * @param <T> bean type
     * @throws IOException if writing fails
     */
    public static <T> void export(OutputStream output, Charset charset, char delimiter, Class<T> beanType, Iterable<T> beans) throws IOException
    {
        if (output == null)
        {
            throw new NullPointerException("output");
        }
        if (charset == null)
        {
            throw new NullPointerException("charset");
        }
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(output, charset)))
        {
            Rfc4180BeanExporter.export(writer, delimiter, beanType, beans);
        }
    }

    private static void validateDelimiter(char delimiter)
    {
        if (delimiter != ',' && delimiter != ';')
        {
            throw new IllegalArgumentException("CSV delimiter must be ',' or ';'");
        }
    }

    private static <T> void writeHeader(Writer writer, char delimiter, BeanDefinition<T> definition) throws IOException
    {
        for (int i = 0; i < definition.fields.size(); i++)
        {
            if (i > 0)
            {
                writer.write(delimiter);
            }
            String columnName = definition.fields.get(i).annotation.value();
            Rfc4180BeanExporter.writeCsvField(writer, delimiter, columnName);
        }
        writer.write(CRLF);
    }

    private static <T> void writeBean(Writer writer, char delimiter, BeanDefinition<T> definition, T bean) throws IOException
    {
        for (int i = 0; i < definition.fields.size(); i++)
        {
            if (i > 0)
            {
                writer.write(delimiter);
            }
            FieldDefinition field = definition.fields.get(i);
            Object value;
            try
            {
                value = field.field.get(bean);
            }
            catch (IllegalAccessException e)
            {
                throw new CsvExportException("Failed to read field '" + field.field.getName() + "'", e);
            }
            String text = Rfc4180BeanExporter.formatValue(value, field.field, field.annotation.format());
            Rfc4180BeanExporter.writeCsvField(writer, delimiter, text);
        }
        writer.write(CRLF);
    }

    private static String formatValue(Object value, Field field, String format)
    {
        if (value == null)
        {
            return "";
        }
        if (value instanceof String)
        {
            return (String) value;
        }
        if (value instanceof Character)
        {
            return value.toString();
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal || value instanceof UUID)
        {
            return value.toString();
        }
        if (value instanceof Enum<?>)
        {
            return ((Enum<?>) value).name();
        }
        if (value instanceof LocalDate)
        {
            return formatTemporal(value, format, LocalDate.class);
        }
        if (value instanceof LocalDateTime)
        {
            return formatTemporal(value, format, LocalDateTime.class);
        }
        if (value instanceof LocalTime)
        {
            return formatTemporal(value, format, LocalTime.class);
        }
        if (value instanceof OffsetDateTime)
        {
            return formatTemporal(value, format, OffsetDateTime.class);
        }
        if (value instanceof OffsetTime)
        {
            return formatTemporal(value, format, OffsetTime.class);
        }
        if (value instanceof ZonedDateTime)
        {
            return formatTemporal(value, format, ZonedDateTime.class);
        }
        throw new CsvExportException("Unsupported field type '" + field.getType().getName() + "' for field '" + field.getName() + "'");
    }

    private static String formatTemporal(Object value, String format, Class<?> type)
    {
        if (format == null || format.isEmpty())
        {
            return value.toString();
        }
        try
        {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format, Locale.ROOT);
            if (type == LocalDate.class)
            {
                return formatter.format((LocalDate) value);
            }
            if (type == LocalDateTime.class)
            {
                return formatter.format((LocalDateTime) value);
            }
            if (type == LocalTime.class)
            {
                return formatter.format((LocalTime) value);
            }
            if (type == OffsetDateTime.class)
            {
                return formatter.format((OffsetDateTime) value);
            }
            if (type == OffsetTime.class)
            {
                return formatter.format((OffsetTime) value);
            }
            if (type == ZonedDateTime.class)
            {
                return formatter.format((ZonedDateTime) value);
            }
            throw new CsvExportException("Unsupported temporal type: " + type.getName());
        }
        catch (IllegalArgumentException e)
        {
            throw new CsvExportException("Invalid date/time format '" + format + "' for " + type.getName(), e);
        }
    }

    /**
     * Writes one CSV field according to RFC 4180. * * A field is quoted when it contains: * * - comma * - double quote * - CR * - LF * * Double quotes inside quoted fields are doubled.
     */
    private static void writeCsvField(Writer writer, char delimiter, String value) throws IOException
    {
        if (value == null)
        {
            return;
        }
        validateCharacters(value);
        boolean quote = value.indexOf(delimiter) >= 0
                        || value.indexOf('"') >= 0
                        || value.indexOf('\r') >= 0
                        || value.indexOf('\n') >= 0;
        if (!quote)
        {
            writer.write(value);
            return;
        }
        writer.write('"');
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (c == '"')
            {
                writer.write("\"\"");
            }
            else
            {
                writer.write(c);
            }
        }
        writer.write('"');
    }

    /**
     * Validates characters against the same TEXTDATA rules used * by {@link Rfc4180Parser}. * * Comma, CR, LF and quote are allowed because they are valid * when the field is quoted.
     */
    private static void validateCharacters(String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            int c = value.charAt(i);
            if (isTextData(c) || c == ',' || c == '"' || c == '\r' || c == '\n') // isTextData(;) otherwise (...,char delimiter)
            {
                continue;
            }
            throw new CsvExportException("Invalid character " + printable(c) + " at string index " + i);
        }
    }

    private static boolean isTextData(int c)
    {
        return (c >= 0x20 && c <= 0x21) || (c >= 0x23 && c <= 0x2B) || (c >= 0x2D && c <= 0x7E);
    }

    private static String printable(int c)
    {
        switch (c)
        {
            case '\r':
                return "0x000D ('\\r')";
            case '\n':
                return "0x000A ('\\n')";
            case '\t':
                return "0x0009 ('\\t')";
            default:
                return String.format("0x%04X ('%s')", c, (char) c);
        }
    }

    private static List<Field> getAllFields(Class<?> type)
    {
        List<Class<?>> hierarchy = new ArrayList<>();

        Class<?> current = type;

        while (current != null && current != Object.class)
        {
            hierarchy.add(current);
            current = current.getSuperclass();
        }

        Collections.reverse(hierarchy);

        List<Field> fields = new ArrayList<>();

        for (Class<?> clazz : hierarchy)
        {
            for (Field field : clazz.getDeclaredFields())
            {
                if (field.isAnnotationPresent(CsvColumn.class)
                        && !Modifier.isStatic(field.getModifiers())
                        && !Modifier.isFinal(field.getModifiers())
                        && !field.isSynthetic()) {

                    fields.add(field);
                }
            }
        }

        return fields;
    }

    private record BeanDefinition<T>(List<FieldDefinition> fields)
    {
        private BeanDefinition(Class<T> beanType)
        {
            List<FieldDefinition> definitions = new ArrayList<>();
            Set<String> columnNames = new HashSet<>();
            for (Field field : getAllFields(beanType))
            {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || field.isSynthetic())
                {
                    continue;
                }
                CsvColumn annotation = field.getAnnotation(CsvColumn.class);
                if (annotation == null)
                {
                    continue;
                }
                String columnName = annotation.value();
                if (columnName == null || columnName.isEmpty())
                {
                    throw new CsvExportException("CSV column name for field '" + field.getName() + "' cannot be empty");
                }
                if (!columnNames.add(columnName))
                {
                    throw new CsvExportException("Duplicate CSV column '" + columnName + "' mapped by field '" + field.getName() + "'");
                }
                try
                {
                    field.setAccessible(true);
                }
                catch (RuntimeException e)
                {
                    throw new CsvExportException("Cannot access bean field '" + field.getName() + "'", e);
                }
                definitions.add(new FieldDefinition(field, annotation));
            }
            if (definitions.isEmpty())
            {
                throw new CsvExportException("Bean " + beanType.getName() + " contains no @CsvColumn fields");
            }
            this(Collections.unmodifiableList(definitions));
        }
    }

    private record FieldDefinition(Field field, CsvColumn annotation) {
    }

    public static final class CsvExportException extends RuntimeException
    {
        public CsvExportException(String message)
        {
            super(message);
        }

        public CsvExportException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}