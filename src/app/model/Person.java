package app.model;

import csv.exporter.Rfc4180BeanExporter;
import csv.parser.Rfc4180BeanParser;
import csv.parser.Rfc4180Parser;
import csv.utils.CsvColumn;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class Person
{
    @CsvColumn("id")
    private Long id;

    @CsvColumn("first_name")
    private String firstName;

    @CsvColumn("last_name")
    private String lastName;

    @CsvColumn(value = "birth_date", format = "M/d/yyyy")
    private LocalDate birthDate;

    @CsvColumn("email")
    private String email;

    @CsvColumn("gender")
    private Gender gender;

    public Person() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("Person{");
        sb.append("id=").append(id);
        sb.append(", firstName='").append(firstName).append('\'');
        sb.append(", lastName='").append(lastName).append('\'');
        sb.append(", birthDate=").append(birthDate);
        sb.append(", email='").append(email).append('\'');
        sb.append(", gender=").append(gender);
        sb.append('}');
        return sb.toString();
    }

    static void main() {

        try (InputStream in = Files.newInputStream(Path.of("../csv-parser/resources/data.csv")))
        {
            Rfc4180Parser.parse(in, StandardCharsets.UTF_8, true, System.out::println);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try (Reader reader = Files.newBufferedReader(Path.of("../csv-parser/resources/data.csv"), StandardCharsets.UTF_8)) {
            Rfc4180Parser.parse(reader, true,
                    record -> {

                        String firstColumn = record.get(0);

                        System.out.println(firstColumn);
                    });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        List<Person> personList = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(Path.of("../csv-parser/resources/data.csv"), StandardCharsets.UTF_8))
        {
            Rfc4180BeanParser.parse(reader, Person.class, personList::add);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        personList.forEach(System.out::println);

        try (Writer writer = Files.newBufferedWriter(Path.of("../csv-parser/resources/output.csv"), StandardCharsets.UTF_8)) {
            Rfc4180BeanExporter.export(writer, Person.class, personList);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}