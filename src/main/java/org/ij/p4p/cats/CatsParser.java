package org.ij.p4p.cats;

import static com.google.common.truth.Truth.assertThat;

import org.ij.p4p.util.CsvToPb;
import org.ij.p4p.util.Receiver;
import org.ij.p4p.util.SqLite;
import org.ij.p4p.util.SqProto;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.apache.commons.csv.CSVFormat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;

/**
 * Parses the CATS data from CSV format and imports into SQLite.
 */
public class CatsParser {
  private static final Set<String> BOOL_VALUES = ImmutableSet.of("Y", "N");

  /**
   * Imports data from the Zip into a new SQLite database. Returns all of the parse
   * issues as a list after completion. Useful for testing.
   */
  public static List<ParseIssue> importIntoDatabase(
      ZipInputStream in, SqProto<CatsSnapshot> sqProto) throws IOException, SQLException {
    final List<ParseIssue> parseIssues = Lists.newArrayListWithCapacity(100);
    importIntoDatabase(in, sqProto, new Receiver<ParseIssue>() {
        public void receive(ParseIssue issue) {
          parseIssues.add(issue);
        }
      });
    return parseIssues;
  }

  /**
   * Imports data from the Zip into a new SQLite database. Existing tables are
   * dropped and recreated before importing the data.
   */
  public static void importIntoDatabase(
      ZipInputStream zipInputStream, SqProto<CatsSnapshot> sqProto,
      Receiver<ParseIssue> issues) throws IOException, SQLException {
    sqProto.dropAndCreateTables();
    try {
      CsvToPb.parseTablesFromZip(
          zipInputStream,
          CSVFormat.EXCEL,
          CatsRawSnapshot.getDefaultInstance(),
          SqProto.BATCH_SIZE,
          receiveTableRecords(issues),
          receiveIntoDatabase(sqProto, issues));
    } catch (UncheckedExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), SQLException.class);
      Throwables.propagate(e.getCause());
    }
    sqProto.sqLite.connection.commit();
  }

  /**
   * Builds ParseIssue protos from inconsistent CSVRecords.
   */
  private static Receiver<CsvToPb.TableRecord> receiveTableRecords(final Receiver<ParseIssue> issues) {
    return new Receiver<CsvToPb.TableRecord>() {
      public void receive(CsvToPb.TableRecord bad) {
        assertThat(bad.record.isConsistent()).isFalse();
        issues.receive(ParseIssue.newBuilder()
            .setType(ParseIssue.Type.INCONSISTENT_RECORD)
            .setTableId(bad.table.getNumber())
            .setRecordId(bad.record.getRecordNumber())
            .setFieldCount(bad.record.size()).build());
      }
    };
  }

  /**
   * Converts records from text format and inserts into SQLite.
   */
  private static Receiver<CatsRawSnapshot> receiveIntoDatabase(
      final SqProto<CatsSnapshot> sqProto, final Receiver<ParseIssue> issues) {
    return new Receiver<CatsRawSnapshot>() {
      public void receive(CatsRawSnapshot parsed) {
        try {
          sqProto.insertSnapshot(convertFromText(parsed, issues));
        } catch (SQLException e) {
          throw new UncheckedExecutionException(e);
        }
      }
    };
  }

  /**
   * Converts a raw snapshot (all records in text format) to a typed snapshot.
   */
  public static CatsSnapshot convertFromText(CatsRawSnapshot snap, final Receiver<ParseIssue> recv) {
    CatsSnapshot.Builder b = CatsSnapshot.newBuilder();
    Descriptor outputSchema = CatsSnapshot.getDescriptor();
    for (Map.Entry<FieldDescriptor, Object> e : snap.getAllFields().entrySet()) {
      final FieldDescriptor outputField = outputSchema.findFieldByNumber(e.getKey().getNumber());
      List<Message> inputRecords = (List<Message>) e.getValue();
      recv.receive(ParseIssue.newBuilder()
          .setType(ParseIssue.Type.BATCH_RECORDS)
          .setTableId(outputField.getNumber())
          .setRecordCount(inputRecords.size()).build());
      for (Message inputRecord : inputRecords) {
        Message.Builder outputRecord = b.newBuilderForField(outputField);
        convertFromText(inputRecord, outputRecord, new Receiver<ParseIssue>() {
            public void receive(ParseIssue issue) {
              recv.receive(issue.toBuilder()
                  .setTableId(outputField.getNumber()).build());
            }
          });
        b.addRepeatedField(outputField, outputRecord.build());
      }
    }
    return b.build();
  }

  /**
   * Maps a raw proto (all fields in string format) to a typed proto by field no.
   */
  public static void convertFromText(Message inputRecord, Message.Builder outputRecord, Receiver<ParseIssue> recv) {
    Descriptor outputSchema = outputRecord.getDescriptorForType();
    for (Map.Entry<FieldDescriptor, Object> e : inputRecord.getAllFields().entrySet()) {
      FieldDescriptor outputField = outputSchema.findFieldByNumber(e.getKey().getNumber());
      switch (outputField.getJavaType()) {
        case STRING:
          outputRecord.setField(outputField, e.getValue());
          break;
        case BOOLEAN:
          if (!BOOL_VALUES.contains((String) e.getValue())) {
            recv.receive(ParseIssue.newBuilder()
                .setType(ParseIssue.Type.INVALID_FIELD_VALUE)
                .setFieldId(e.getKey().getNumber())
                .setFieldValue((String) e.getValue()).build());
          } else {
            outputRecord.setField(outputField, e.getValue().equals("Y"));
          }
          break;
        case INT:
          outputRecord.setField(outputField, Integer.valueOf((String) e.getValue()));
          break;
        case DOUBLE:
          outputRecord.setField(outputField, Double.valueOf((String) e.getValue()));
          break;
        default:
          throw new RuntimeException("Unhandled data type: " +
              outputField.getName() + ": " + outputField.getJavaType());
      }
    }
  }

  public static void main(String[] args) throws Exception {
    assertThat(args.length).isEqualTo(2);
    File zipFile = new File(args[0]);
    File databaseFile = new File(args[1]);
    assertThat(zipFile.getPath().endsWith(".zip")).isTrue();
    assertThat(databaseFile.getPath().endsWith(".db")).isTrue();
    ZipInputStream in = new ZipInputStream(new FileInputStream(zipFile.getPath()));
    SqProto<CatsSnapshot> sqProto = SqProto.create(
        SqLite.connect(databaseFile.getPath()),
        CatsSnapshot.getDefaultInstance());
    final Map<ParseIssue, AtomicInteger> parseIssues = Maps.newHashMap();
    importIntoDatabase(in, sqProto, new Receiver<ParseIssue>() {
        public void receive(ParseIssue issue) {
          AtomicInteger issueCount = parseIssues.get(issue);
          if (issueCount == null) {
            issueCount = new AtomicInteger();
            parseIssues.put(issue, issueCount);
          }
          if (issueCount.incrementAndGet() == 1) {
            // Print the first occurrence of each issue as we go.
            System.err.println(issue.toString());
          }
        }
      });
    sqProto.sqLite.connection.close();
    System.err.println("Import complete.");
    if (!parseIssues.isEmpty()) {
      System.err.println("Parse issues encountered:");
      for (Map.Entry<ParseIssue, AtomicInteger> e : parseIssues.entrySet()) {
        System.err.println(String.format("%d { %s }",
            e.getValue().get(), TextFormat.shortDebugString(e.getKey())));
      }
    }
  }
}
