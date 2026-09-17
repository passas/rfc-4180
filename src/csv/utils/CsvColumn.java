package csv.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface CsvColumn {

    String value();

    /**
     * Optional date/time pattern.
     *
     * Example:
     *
     * @CsvColumn(
     *     value = "birth_date",
     *     format = "MM/dd/yyyy"
     * )
     */
    String format() default "";

    /**
     * Whether an empty CSV value should be converted to null.
     */
    boolean nullable() default true;
}
