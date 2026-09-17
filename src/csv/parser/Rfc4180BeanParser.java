package csv.parser;

import csv.utils.CsvColumn;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Constructor;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Streaming bean mapper built on top of {@link Rfc4180Parser}.
 * <p>
 * The first CSV record is treated as the header.
 * <p>
 * Each subsequent CSV record is converted into an instance of the
 * specified bean class and passed to the supplied Consumer.
 * <p>
 * Example CSV:
 *
 * <pre>
 * id,first_name,last_name,birth_date,email,gender
 * 1,Priscilla,Fintoph,10/29/1998,pfintoph0@themeforest.net,Female
 * </pre>
 * <p>
 * Example bean:
 *
 * <pre>
 * public class Person {
 *
 *     @CsvColumn("id")
 *     private Long id;
 *
 *     @CsvColumn("first_name")
 *     private String firstName;
 *
 *     @CsvColumn("last_name")
 *     private String lastName;
 *
 *     @CsvColumn(value = "birth_date", format = "MM/dd/yyyy")
 *     private LocalDate birthDate;
 *
 *     @CsvColumn("email")
 *     private String email;
 *
 *     @CsvColumn("gender")
 *     private Gender gender;
 *
 *     public Person() {
 *     }
 * }
 * </pre>
 * <p>
 * The complete CSV is never loaded into memory.
 * Only the current CSV record and current bean are held in memory.
 */
public final class Rfc4180BeanParser {

    private Rfc4180BeanParser() {
    }

    /**
     * Parses a CSV from a Reader using the default options.
     * <p>
     * The first record is treated as the CSV header.
     * <p>
     * Header matching is case-insensitive.
     * <p>
     * Unknown CSV columns are rejected.
     * <p>
     * Missing bean columns are rejected.
     *
     * @param reader   CSV source
     * @param beanType bean class
     * @param consumer receives one bean at a time
     */
    public static <T> void parse(Reader reader, Class<T> beanType, Consumer<T> consumer) throws IOException
    {
        Rfc4180BeanParser.parse(reader, ',', beanType, consumer, true, true, true);
    }

    /**
     * Parses a CSV from a Reader using the default options.
     * <p>
     * The first record is treated as the CSV header.
     * <p>
     * Header matching is case-insensitive.
     * <p>
     * Unknown CSV columns are rejected.
     * <p>
     * Missing bean columns are rejected.
     *
     * @param reader   CSV source
     * @param delimiter value delimiter
     * @param beanType bean class
     * @param consumer receives one bean at a time
     */
    public static <T> void parse(Reader reader, char delimiter, Class<T> beanType, Consumer<T> consumer) throws IOException
    {
        Rfc4180BeanParser.parse(reader, delimiter, beanType, consumer, true, true, true);
    }

    /**
     * Parses a CSV from an InputStream using UTF-8.
     * <p>
     * The first record is treated as the CSV header.
     */
    public static <T> void parse(InputStream input, Class<T> beanType, Consumer<T> consumer) throws IOException
    {
        Rfc4180BeanParser.parse(input, StandardCharsets.UTF_8, ',', beanType, consumer);
    }

    /**
     * Parses a CSV from an InputStream using the supplied charset.
     * <p>
     * The first record is treated as the CSV header.
     */
    public static <T> void parse(InputStream input, Charset charset, char delimiter, Class<T> beanType, Consumer<T> consumer) throws IOException
    {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(beanType, "beanType");
        Objects.requireNonNull(consumer, "consumer");

        BeanMapper<T> mapper = new BeanMapper<>(beanType, consumer, true, true, true);

        /*
         * Rfc4180Parser owns the Reader created from the InputStream.
         */
        Rfc4180Parser.parse(input, charset, delimiter, true, mapper);
    }

    /**
     * Parses a CSV from a Reader with configurable options.
     *
     * @param reader                 CSV source
     * @param beanType               bean class
     * @param consumer               receives one bean at a time
     * @param caseInsensitiveHeaders whether CSV header matching
     *                               should ignore case
     * @param failOnUnknownColumns   whether unknown CSV columns
     *                               should cause an error
     * @param failOnMissingColumns   whether bean columns missing
     *                               from the CSV should cause an error
     */
    public static <T> void parse(Reader reader, char delimiter, Class<T> beanType, Consumer<T> consumer, boolean caseInsensitiveHeaders, boolean failOnUnknownColumns, boolean failOnMissingColumns) throws IOException
    {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(beanType, "beanType");
        Objects.requireNonNull(consumer, "consumer");

        BeanMapper<T> mapper = new BeanMapper<>(beanType, consumer, caseInsensitiveHeaders, failOnUnknownColumns, failOnMissingColumns);

        Rfc4180Parser.parse(reader, delimiter, true, mapper);
    }

    /**
     * Finds the required no-argument constructor.
     */
    private static <T> Constructor<T> findConstructor(Class<T> type)
    {
        try
        {
            Constructor<T> constructor = type.getDeclaredConstructor();

            constructor.setAccessible(true);

            return constructor;
        }
        catch (NoSuchMethodException e)
        {
            throw new BeanParseException("Bean " + type.getName() + " must have a no-argument constructor", e);
        }
    }

    /**
     * Gets fields from the class and all of its superclasses.
     */
    private static List<Field> getAllFields(Class<?> type)
    {
        List<Field> fields = new ArrayList<>();

        Class<?> current = type;

        while (current != null && current != Object.class)
        {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));

            current = current.getSuperclass();
        }

        return fields;
    }

    /**
     * Converts a CSV String into the Java type of the field.
     */
    private static Object convert(String raw, Field field, String format, boolean nullable) {

        Class<?> type = field.getType();

        /*
         * Empty CSV value.
         */
        if (raw.isEmpty())
        {
            if (!nullable)
            {
                throw new IllegalArgumentException("Value cannot be null or empty");
            }

            /*
             * Primitive fields cannot contain null.
             */
            if (type.isPrimitive())
            {
                throw new IllegalArgumentException("Primitive field '" + field.getName() + "' cannot be null");
            }

            return null;
        }

        /*
         * String
         */
        if (type == String.class)
        {
            return raw;
        }

        /*
         * Boolean
         */
        if (type == boolean.class || type == Boolean.class)
        {
            return parseBoolean(raw);
        }

        /*
         * Byte
         */
        if (type == byte.class || type == Byte.class)
        {
            return Byte.valueOf(raw);
        }

        /*
         * Short
         */
        if (type == short.class || type == Short.class)
        {
            return Short.valueOf(raw);
        }

        /*
         * Integer
         */
        if (type == int.class || type == Integer.class)
        {
            return Integer.valueOf(raw);
        }

        /*
         * Long
         */
        if (type == long.class || type == Long.class)
        {
            return Long.valueOf(raw);
        }

        /*
         * Float
         */
        if (type == float.class || type == Float.class)
        {
            return Float.valueOf(raw);
        }

        /*
         * Double
         */
        if (type == double.class || type == Double.class)
        {
            return Double.valueOf(raw);
        }

        /*
         * Character
         */
        if (type == char.class || type == Character.class)
        {
            if (raw.length() != 1)
            {
                throw new IllegalArgumentException("Character value must contain exactly one character");
            }

            return raw.charAt(0);
        }

        /*
         * BigInteger
         */
        if (type == BigInteger.class)
        {
            return new BigInteger(raw);
        }

        /*
         * BigDecimal
         */
        if (type == BigDecimal.class)
        {
            return new BigDecimal(raw);
        }

        /*
         * UUID
         */
        if (type == UUID.class)
        {
            return UUID.fromString(raw);
        }

        /*
         * Java time.
         */
        if (type == LocalDate.class)
        {
            return parseLocalDate(raw, format);
        }

        if (type == LocalDateTime.class)
        {
            return parseLocalDateTime(raw, format);
        }

        if (type == LocalTime.class)
        {
            return parseLocalTime(raw, format);
        }

        if (type == OffsetDateTime.class)
        {
            return parseOffsetDateTime(raw, format);
        }

        if (type == OffsetTime.class)
        {
            return parseOffsetTime(raw, format);
        }

        if (type == ZonedDateTime.class)
        {
            return parseZonedDateTime(raw, format);
        }

        /*
         * Enum.
         */
        if (type.isEnum())
        {
            return parseEnum(raw, type);
        }

        throw new IllegalArgumentException("Unsupported field type: " + type.getName());
    }

    private static Boolean parseBoolean(String value)
    {
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value))
        {
            return true;
        }

        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value))
        {
            return false;
        }

        throw new IllegalArgumentException("Invalid boolean value: " + value);
    }

    private static LocalDate parseLocalDate(String value, String format)
    {
        if (format == null || format.isEmpty())
        {
            return LocalDate.parse(value);
        }

        return LocalDate.parse(value, DateTimeFormatter.ofPattern(format));
    }

    private static LocalDateTime parseLocalDateTime(String value, String format)
    {
        if (format == null || format.isEmpty())
        {
            return LocalDateTime.parse(value);
        }

        return LocalDateTime.parse(value, DateTimeFormatter.ofPattern(format));
    }

    private static LocalTime parseLocalTime(String value, String format)
    {
        if (format == null || format.isEmpty())
        {
            return LocalTime.parse(value);
        }

        return LocalTime.parse(value, DateTimeFormatter.ofPattern(format));
    }

    private static OffsetDateTime parseOffsetDateTime(String value, String format)
    {
        if (format == null || format.isEmpty())
        {
            return OffsetDateTime.parse(value);
        }

        return OffsetDateTime.parse(value, DateTimeFormatter.ofPattern(format));
    }

    private static OffsetTime parseOffsetTime(String value, String format)
    {
        if (format == null || format.isEmpty())
        {
            return OffsetTime.parse(value);
        }

        return OffsetTime.parse(value, DateTimeFormatter.ofPattern(format));
    }

    private static ZonedDateTime parseZonedDateTime(String value, String format)
    {
        if (format == null || format.isEmpty())
        {
            return ZonedDateTime.parse(value);
        }

        return ZonedDateTime.parse(value, DateTimeFormatter.ofPattern(format));
    }

    @SuppressWarnings({
            "unchecked",
            "rawtypes"
    })
    private static Object parseEnum(String value, Class<?> enumType)
    {
        Class<? extends Enum> type = (Class<? extends Enum>) enumType;

        /*
         * First try the exact enum constant name.
         */
        try
        {
            return Enum.valueOf(type, value);
        }
        catch (IllegalArgumentException ignored)
        {
            // Try case-insensitive matching below.
            // Normalized
        }

        /*
         * Try normalized matching.
         */
        String normalizedValue = normalizeEnumValue(value);
        for (Enum constant : type.getEnumConstants())
        {
            if (normalizeEnumValue(constant.name()).equals(normalizedValue))
            {
                return constant;
            }
        }

        throw new IllegalArgumentException("Invalid enum value '" + value + "' for " + enumType.getName());
    }

    private static String normalizeEnumValue(String value)
    {
        return value.replaceAll("[\\s_-]", "").toLowerCase(Locale.ROOT);
        //return value.replaceAll("[\\s_-]", "").toLowerCase(Locale.ROOT);
    }

    private static String printableValue(String value)
    {
        if (value == null)
        {
            return "null";
        }

        return "'" + value + "'";
    }

    /**
     * Internal CSV record -> bean mapper.
     */
    private static final class BeanMapper<T> implements Consumer<List<String>>
    {
        private final Class<T> beanType;
        private final Consumer<T> consumer;

        private final boolean caseInsensitiveHeaders;
        private final boolean failOnUnknownColumns;
        private final boolean failOnMissingColumns;

        private final Constructor<T> constructor;

        private final Map<String, FieldDefinition> fields;

        /**
         * Maps CSV column index -> bean field.
         */
        private Map<Integer, FieldDefinition> columnMappings;

        /**
         * True after the header has been processed.
         */
        private boolean headerProcessed;

        /**
         * Data row number, excluding the header.
         */
        private long dataRowNumber;

        private BeanMapper(Class<T> beanType, Consumer<T> consumer, boolean caseInsensitiveHeaders, boolean failOnUnknownColumns, boolean failOnMissingColumns)
        {

            this.beanType = beanType;
            this.consumer = consumer;
            this.caseInsensitiveHeaders = caseInsensitiveHeaders;
            this.failOnUnknownColumns = failOnUnknownColumns;
            this.failOnMissingColumns = failOnMissingColumns;

            this.fields = discoverFields(beanType);
            this.constructor = findConstructor(beanType);
        }

        @Override
        public void accept(List<String> record)
        {
            /*
             * The first record is always the CSV header.
             */
            if (!this.headerProcessed)
            {
                this.processHeader(record);

                this.headerProcessed = true;

                return;
            }

            this.dataRowNumber++;

            T bean = this.createBean();

            for (int i = 0; i < record.size(); i++)
            {
                FieldDefinition definition = this.columnMappings.get(i);

                /*
                 * Unknown CSV columns are allowed only when
                 * failOnUnknownColumns == false.
                 */
                if (definition == null)
                {
                    continue;
                }

                String rawValue = record.get(i);

                Object value;

                try
                {
                    value = convert(rawValue, definition.field, definition.annotation.format(), definition.annotation.nullable());
                }
                catch (RuntimeException e)
                {
                    throw new BeanParseException(
                            "Failed to parse data row "
                                    + this.dataRowNumber
                                    + ", column "
                                    + (i + 1)
                                    + " ('"
                                    + definition.annotation.value()
                                    + "')"
                                    + ", field '"
                                    + definition.field.getName()
                                    + "'"
                                    + ", value "
                                    + printableValue(rawValue),
                            e);
                }

                try
                {
                    definition.field.set(bean, value);
                }
                catch (IllegalAccessException e)
                {
                    throw new BeanParseException(
                            "Failed to assign data row "
                                    + this.dataRowNumber
                                    + ", column "
                                    + (i + 1)
                                    + " ('"
                                    + definition.annotation.value()
                                    + "')"
                                    + " to field '"
                                    + definition.field.getName()
                                    + "'",
                            e);
                }
            }

            /*
             * Deliver the completed bean immediately.
             *
             * This preserves streaming behaviour.
             */
            this.consumer.accept(bean);
        }

        /**
         * Processes the CSV header and creates the mapping:
         * <p>
         * CSV column index -> bean field.
         */
        private void processHeader(List<String> header)
        {
            if (header.isEmpty())
            {
                throw new BeanParseException("CSV header cannot be empty");
            }

            this.columnMappings = new HashMap<>();

            Set<String> seenHeaders = new HashSet<>();

            for (int i = 0; i < header.size(); i++)
            {
                String columnName = header.get(i);

                String key = this.normalize(columnName);

                /*
                 * Duplicate CSV headers are ambiguous.
                 */
                if (!seenHeaders.add(key))
                {
                    throw new BeanParseException("Duplicate CSV header '" + columnName + "' at column " + (i + 1));
                }

                FieldDefinition definition = this.fields.get(key);

                if (definition == null)
                {
                    if (this.failOnUnknownColumns)
                    {
                        throw new BeanParseException("Unknown CSV column '" + columnName + "' at column " + (i + 1));
                    }

                    continue;
                }

                this.columnMappings.put(i, definition);
            }

            /*
             * Make sure every @CsvColumn field has a
             * corresponding CSV column.
             */
            if (this.failOnMissingColumns)
            {
                Set<FieldDefinition> mappedFields = new HashSet<>(this.columnMappings.values());

                for (FieldDefinition definition : this.fields.values())
                {
                    if (!mappedFields.contains(definition))
                    {
                        throw new BeanParseException("CSV is missing bean column '" + definition.annotation.value() + "' for field '" + definition.field.getName() + "'");
                    }
                }
            }
        }

        private String normalize(String value)
        {
            if (this.caseInsensitiveHeaders)
            {
                return value.toLowerCase(Locale.ROOT);
                //return value.toLowerCase(Locale.ROOT);
            }

            return value;
        }

        private T createBean()
        {
            try
            {
                return this.constructor.newInstance();
            }
            catch (ReflectiveOperationException e)
            {
                throw new BeanParseException("Failed to instantiate bean " + this.beanType.getName(), e);
            }
        }

        /**
         * Finds all @CsvColumn fields, including inherited fields.
         */
        private Map<String, FieldDefinition> discoverFields(Class<T> type)
        {
            Map<String, FieldDefinition> result = new HashMap<>();

            for (Field field : getAllFields(type))
            {
                int modifiers = field.getModifiers();

                /*
                 * These fields cannot/should not be populated.
                 */
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || field.isSynthetic())
                {
                    continue;
                }

                CsvColumn annotation = field.getAnnotation(CsvColumn.class);

                if (annotation == null)
                {
                    continue;
                }

                String key = this.normalize(annotation.value());

                if (result.containsKey(key))
                {
                    throw new BeanParseException("Multiple bean fields are mapped to CSV column '" + annotation.value() + "'");
                }

                try
                {
                    field.setAccessible(true);
                }
                catch (RuntimeException e)
                {
                    throw new BeanParseException("Cannot access bean field '" + field.getName() + "'", e);
                }

                result.put(key, new FieldDefinition(field, annotation));
            }

            if (result.isEmpty())
            {
                throw new BeanParseException("Bean " + type.getName() + " contains no @CsvColumn fields");
            }

            return Collections.unmodifiableMap(result);
        }
    }

    private record FieldDefinition(Field field, CsvColumn annotation) {

    }

    /**
     * Exception thrown when a CSV record cannot be mapped
     * to the requested bean.
     */
    public static final class BeanParseException extends RuntimeException {

        public BeanParseException(String message)
        {
            super(message);
        }

        public BeanParseException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}