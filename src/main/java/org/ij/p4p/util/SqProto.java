package org.ij.p4p.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

/**
 * Maps protos to SQLite using reflection.
 *
 * The message definitions must be structured to correspond to a SQL schema.
 * The template passed to the create method is the database schema, and must contain
 * only repeated proto fields (which correspond to database tables). Each table proto
 * must contain only optional primitive fields (including strings and byte strings),
 * since SQL doesn't support repeated fields or nested data structures.
 *
 * If you have a proto schema that uses repeated fields and nested protos, you should
 * first convert these to a flattened representation. This gives you the opportunity to
 * make choices, such as (a) representing the nested data structures using JSON, XML,
 * or as a BLOB (via the proto toByteString method), and (b) what to store in place of
 * a repeated field, if anything, such as a count or an average value.
 */
public class SqProto<T extends Message> {
  public static final int BATCH_SIZE = 10000;

  public final SqLite sqLite;
  private final Descriptor databaseSchema;
  private final T templateProto;

  /**
   * Instantiates a SqProto with the correct descriptor for the schema proto.
   */
  public static <T extends Message> SqProto<T> create(SqLite sqLite, T template) {
    Descriptor databaseSchema = template.getDescriptorForType();
    for (FieldDescriptor table : databaseSchema.getFields()) {
      // The database schema proto must contain only repeated proto fields.
      assertThat(table.isRepeated()).isTrue();
      assertThat(table.getJavaType()).isEqualTo(JavaType.MESSAGE);
      Descriptor tableSchema = table.getMessageType();
      for (FieldDescriptor field : tableSchema.getFields()) {
        // The table schema proto must contain only optional primitive fields.
        assertThat(field.isOptional()).isTrue();
        assertThat(field.getJavaType()).isNotEqualTo(JavaType.MESSAGE);
      }
    }
    return new SqProto<T>(sqLite, template);
  }

  /**
   * Use the create() factory function to instantiate.
   */
  private SqProto(SqLite sqLite, T templateProto) {
    this.sqLite = sqLite;
    this.templateProto = templateProto;
    this.databaseSchema = templateProto.getDescriptorForType();
  }

  /**
   * Drops and then creates tables corresponding to each field in the schema proto.
   */
  public void dropAndCreateTables() throws SQLException {
    for (FieldDescriptor table : databaseSchema.getFields()) {
      sqLite.dropTable(table.getName()).executeUpdate();
      sqLite.createTable(table.getName(), getSqColumns(table.getMessageType())).executeUpdate();
    }
  }

  /**
   * Inserts a batch of records into the database.
   */
  public void insertSnapshot(T snapshot) throws SQLException {
    for (FieldDescriptor table : databaseSchema.getFields()) {
      insertInto(table.getName(), table.getMessageType().getFields(),
          (List<? extends MessageOrBuilder>) snapshot.getField(table));
    }
  }

  /**
   * Snapshots the database in batches of records.
   */
  public void snapshot(int batchSize, Receiver<T> recv) throws SQLException {
    checkArgument(batchSize > 0);
    int recordCount = 0;
    Message.Builder b = templateProto.newBuilderForType();
    for (FieldDescriptor table : databaseSchema.getFields()) {
      Statement s = sqLite.connection.createStatement();
      try {
        ResultSet rs = s.executeQuery("select * from " + table.getName());
        while (rs.next()) {
          Message.Builder fb = b.newBuilderForField(table);
          mergeFrom(fb, rs);
          b.addRepeatedField(table, fb.build());
        }
      } catch (SQLException e) {
        throw new SQLException("table:" + table.getName(), e);
      } catch (RuntimeException e) {
        throw new RuntimeException("table:" + table.getName(), e);
      } finally {
        s.close();
      }
      if (++recordCount % batchSize == 0) {
        recv.receive((T) b.build());
        b = templateProto.newBuilderForType();
      }
    }
    if (recordCount % batchSize != 0) {
      recv.receive((T) b.build());
    }
  }

  /**
   * Snapshots the database contents into a proto.
   */
  public T snapshot() throws SQLException {
    final Message.Builder b = templateProto.newBuilderForType();
    snapshot(BATCH_SIZE, new Receiver<T>() {
        public void receive(T mergeFrom) {
          b.mergeFrom(mergeFrom);
        }
      });
    return (T) b.build();
  }

  /**
   * Inserts a batch of records into a table in the database.
   */
  private void insertInto(String tableName, List<FieldDescriptor> tableSchema,
      List<? extends MessageOrBuilder> protos) throws SQLException {
    PreparedStatement insert = sqLite.insertValues(tableName, tableSchema.size());
    for (MessageOrBuilder proto : protos) {
      int insertIndex = 0;
      for (FieldDescriptor field : tableSchema) {
        try {
          setValue(insert, ++insertIndex, field, proto);
        } catch (RuntimeException e) {
          throw new SQLException(tableName + ":" + field.getName(), e);
        }
      }
      insert.addBatch();
    }
    insert.executeBatch();
  }

  /**
   * Sets the value of a field in the prepared statement.
   */
  private static void setValue(PreparedStatement insert,
      int insertIndex, FieldDescriptor field, MessageOrBuilder proto) throws SQLException {
    if (proto.hasField(field)) {
      setPrimitiveValue(insert, insertIndex, field, proto.getField(field));
    } else {
      insert.setNull(insertIndex, Types.NULL);
    }
  }

  /**
   * Sets the value of a primitive field in the prepared statement.
   */
  private static void setPrimitiveValue(PreparedStatement insert,
      int insertIndex, FieldDescriptor field, Object value) throws SQLException {
    switch (field.getJavaType()) {
      case BYTE_STRING:
        ByteString bValue = (ByteString) value;
        insert.setBytes(insertIndex, bValue.toByteArray());
        break;
      case ENUM:
        EnumValueDescriptor eValue = (EnumValueDescriptor) value;
        insert.setInt(insertIndex, eValue.getNumber());
        break;
      case STRING:
        // Not sure which string accessor the setObject method would use.
        insert.setString(insertIndex, (String) value);
        break;
      default:
        // Set the value based on the boxed type.
        insert.setObject(insertIndex, value);
    }
  }

  /**
   * Converts a message descriptor into a SQLite column specification.
   */
  private static List<String> getSqColumns(Descriptor tableSchema) throws SQLException {
    List<FieldDescriptor> fields = tableSchema.getFields();
    List<String> columns = Lists.newArrayListWithCapacity(fields.size());
    for (FieldDescriptor field : fields) {
      columns.add(field.getName() + " " + getSqType(field.getJavaType()));
    }
    return columns;
  }

  /**
   * Maps the JavaType to the SQLite data type.
   */
  private static String getSqType(JavaType type) {
    switch (type) {
      case BOOLEAN:
        return "boolean";
      case BYTE_STRING:
        return "blob";
      case DOUBLE:
        return "double";
      case FLOAT:
        return "float";
      case ENUM:
      case INT:
      case LONG:
        return "int";
      case STRING:
        return "string";
      default:
        throw new RuntimeException("javaType=" + type.toString());
    }
  }

  /**
   * Reads the ResultSet into protos and returns as a List.
   */
  public static <P extends Message> List<P> readIntoList(
      ResultSet rs, P template) throws SQLException {
    final List<P> records = Lists.newArrayListWithCapacity(BATCH_SIZE);
    readIntoProtos(rs, template, new Receiver<P>() {
        public void receive(P record) {
          records.add(record);
        }
      });
    return records;
  }

  /**
   * Returns records from the ResultSet via the Receiver. Uses the template proto
   * field names to extract columns, which must have corresponding data types. Fields
   * missing from the result set are skipped (according to optional semantics).
   */
  public static <P extends Message> void readIntoProtos(
      ResultSet rs, P template, Receiver<P> recv) throws SQLException {
    Descriptor schema = template.getDescriptorForType();
    for (FieldDescriptor field : schema.getFields()) {
      // The template proto must contain only optional primitive fields.
      assertThat(field.isOptional()).isTrue();
      assertThat(field.getJavaType()).isNotEqualTo(JavaType.MESSAGE);
    }
    while (rs.next()) {
      Message.Builder b = template.newBuilderForType();
      mergeFrom(b, rs);
      recv.receive((P) b.build());
    }
  }

  /**
   * Populates the builder with data from the current record in the result set. The idea
   * is based on the TextFormat.merge() function. Note that columns are referenced by name,
   * such that records can be read from other than the canonical table.
   */
  private static void mergeFrom(Message.Builder b, ResultSet rs) throws SQLException {
    Descriptor tableSchema = b.getDescriptorForType();
    for (FieldDescriptor field : tableSchema.getFields()) {
      try {
        switch (field.getJavaType()) {
          case MESSAGE:
            throw new RuntimeException("Nested messages not supported.");
          case BYTE_STRING:
            byte[] bytes = rs.getBytes(field.getName());
            if (bytes != null) {
              setValue(b, field, ByteString.copyFrom(bytes));
            }
            break;
          case ENUM:
            int intValue = rs.getInt(field.getName());
            if (!rs.wasNull()) {
              setValue(b, field, field.getEnumType().findValueByNumber(intValue));
            }
            break;
          case STRING:
            // Not sure which string accessor the getObject method would use.
            String stringValue = rs.getString(field.getName());
            if (stringValue != null) {
              setValue(b, field, stringValue);
            }
            break;
          case LONG:
            // SQLite stores integers using up to 8 bytes depending on the magnitude
            // of the value, but the getObject method returns an int.
            long longValue = rs.getLong(field.getName());
            if (!rs.wasNull()) {
              setValue(b, field, Long.valueOf(longValue));
            }
            break;
          case BOOLEAN:
            // The getObject method returns boolean types as integers.
            boolean boolValue = rs.getBoolean(field.getName());
            if (!rs.wasNull()) {
              setValue(b, field, Boolean.valueOf(boolValue));
            }
            break;
          case FLOAT:
            // The getObject method returns floats as doubles.
            float floatValue = rs.getFloat(field.getName());
            if (!rs.wasNull()) {
              setValue(b, field, Float.valueOf(floatValue));
            }
            break;
          default:
            Object boxedValue = rs.getObject(field.getName());
            if (boxedValue != null) {
              setValue(b, field, boxedValue);
            }
        }
      } catch (SQLException e) {
        if (e.getMessage().startsWith("no such column:")) {
          // The table we're extracting from doesn't have the field.
          continue;
        } else {
          throw e;
        }
      }
    }
  }

  /**
   * Sets or adds a value to a field in a builder.
   */
  private static void setValue(Message.Builder b, FieldDescriptor field, Object value) {
    try {
      b.setField(field, value);
    } catch (RuntimeException e) {
      throw new RuntimeException(field.getName() + ":" + value, e);
    }
  }
}
