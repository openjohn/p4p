package org.ij.p4p.util;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Charsets;
import com.google.common.base.CharMatcher;
import com.google.common.io.Files;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Message;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CsvToPb {
  public static final int BATCH_SIZE = 10000;

  /**
   * Thrown from functions that don't pass inconsistent records as callbacks.
   */
  public static class InconsistentRecordException extends RuntimeException {
    public final TableRecord inconsistent;

    public InconsistentRecordException(TableRecord inconsistent) {
      super(inconsistent.toString());
      this.inconsistent = inconsistent;
    }
  }

  /**
   * Holds a field descriptor from the database schema, describing a table,
   * along with a CSVRecord from that table.
   */
  public static class TableRecord {
    public final FieldDescriptor table;
    public final CSVRecord record;

    public TableRecord(FieldDescriptor table, CSVRecord record) {
      this.table = table;
      this.record = record;
    }

    public String toString() {
      return String.format("table:%s record:%d fields:%d",
          table.getName(), record.getRecordNumber(), record.size());
    }
  }

  /**
   * Parses CSV files from the Zip and returns all of the records. Throws an exception
   * if there are inconsistent records. Useful for testing.
   */
  public static <T extends Message> T parseTablesFromZip(
      ZipInputStream in, CSVFormat format, T template) throws IOException {
    final Message.Builder b = template.newBuilderForType();
    parseTablesFromZip(
        in, format, template, BATCH_SIZE,
        new Receiver<TableRecord>() {
          public void receive(TableRecord inconsistent) {
            throw new InconsistentRecordException(inconsistent);
          }
        },
        new Receiver<T>() {
          public void receive(T batch) {
            b.mergeFrom(batch);
          }
        });
    return (T) b.build();
  }

  /**
   * Parses CSV files from the Zip and returns records in batches. Expects the template
   * proto to have repeated fields of type message, with field names matching the zip entry
   * file names (non-matching zip entries are skipped).
   */
  public static <T extends Message> void parseTablesFromZip(
      ZipInputStream in, CSVFormat format, T template, final int batchSize,
      Receiver<TableRecord> inconsistent, Receiver<T> recv) throws IOException {
    for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
      String tableName = Files.getNameWithoutExtension(e.getName());
      if (hasField(template, tableName)) {
        parseTableFromCsv(tableName, in, format, template, batchSize, inconsistent, recv);
      }
    }
  }

  /**
   * Parses CSV data and returns records in batches. Expects the template proto to have
   * repeated fields of type message, with a field named the same as tableName.
   */
  private static <T extends Message> void parseTableFromCsv(
      String tableName, InputStream in, CSVFormat csvFormat, T template, final int batchSize,
      final Receiver<TableRecord> inconsistent, final Receiver<T> recv) throws IOException {
    final Descriptor d = getDatabaseDescriptor(template);
    final FieldDescriptor field = d.findFieldByName(tableName);
    final Message.Builder b = template.newBuilderForType();
    final AtomicInteger recordCount = new AtomicInteger();
    parseCsvDataByFieldName(
        csvFormat,
        b.newBuilderForField(field).build(),
        new InputStreamReader(in, Charsets.UTF_8),
        new Receiver<CSVRecord>() {
          public void receive(CSVRecord record) {
            inconsistent.receive(new TableRecord(field, record));
          }
        },
        new Receiver<Message>() {
          public void receive(Message record) {
            b.addRepeatedField(field, record);
            if (recordCount.incrementAndGet() % batchSize == 0) {
              recv.receive((T) b.build());
              b.clear();
            }
          }
        });
    if (recordCount.get() % batchSize != 0) {
      recv.receive((T) b.build());
    }
  }

  /**
   * Parses CSV data into proto format, mapping the CSV column header to the proto field names.
   * Expects the template proto to have optional fields of type string. Trims whitespace from
   * field values, and treats empty field values as nulls. The CSVFormat argument specifies the
   * CSV dialect that we're parsing. Inconsistent records (field count not matching header
   * column count) are returned via the inconsistent record receiver.
   */
  public static <T extends Message> void parseCsvDataByFieldName(
      CSVFormat format, T template, Reader in, Receiver<CSVRecord> inconsistent,
      Receiver<T> recv) throws IOException {
    Descriptor schema = getTableDescriptor(template);
    final int fieldCount = schema.getFields().size();
    for (CSVRecord record : format.withHeader().parse(in)) {
      if (!record.isConsistent()) {
        inconsistent.receive(record);
        continue;
      }
      Message.Builder b = template.newBuilderForType();
      for (FieldDescriptor field : schema.getFields()) {
        if (record.isMapped(field.getName())) {
          String value = record.get(field.getName());
          if (value != null) {
            // Guava is known to correctly handle Unicode whitespace.
            value = CharMatcher.WHITESPACE.trimFrom(value);
            if (!value.isEmpty()) {
              b.setField(field, value);
            }
          }
        }
      }
      recv.receive((T) b.build());
    }
  }

  /**
   * Returns true if the template proto has a field with the given name.
   */
  private static <T extends Message> boolean hasField(T template, String fieldName) {
    return template.getDescriptorForType().findFieldByName(fieldName) != null;
  }

  /**
   * Expects the template proto to have optional fields of type string.
   * @return The descriptor for the template proto.
   */
  private static <T extends Message> Descriptor getTableDescriptor(T template) {
    Descriptor schema = template.getDescriptorForType();
    for (FieldDescriptor field : schema.getFields()) {
      assertThat(field.getJavaType()).isEqualTo(JavaType.STRING);
      assertThat(field.isOptional()).isTrue();
    }
    return schema;
  }

  /**
   * Expects the template proto to have repeated fields of type message.
   * @return The descriptor for the template proto.
   */
  private static <T extends Message> Descriptor getDatabaseDescriptor(T template) {
    Descriptor schema = template.getDescriptorForType();
    for (FieldDescriptor field : schema.getFields()) {
      assertThat(field.getJavaType()).isEqualTo(JavaType.MESSAGE);
      assertThat(field.isRepeated()).isTrue();
    }
    return schema;
  }
}
