package org.ij.p4p.util;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.TreeMap;

/**
 * Wraps a JDBC connection to SQLite.
 */
public class SqLite {
  public final Connection connection;

  public SqLite(Connection connection) {
    this.connection = connection;
  }

  /**
   * Opens a SQLite connection to a file.
   */
  public static SqLite connect(String path) throws SQLException {
    try {
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
    // This begins a transaction so that SQLite doesn't execute each statement as
    // a separate transaction. Some SQL statements, such as ATTACH, throw an exception
    // when executed within a transaction, so auto commit must be ENABLED.
    connection.setAutoCommit(false);
    return new SqLite(connection);
  }

  /**
   * Opens a SQLite connection to an in-memory database.
   */
  public static SqLite connectInMem() throws SQLException {
    return connect(":memory:");
  }

  /**
   * Opens a SQLite connection to a temporary file database.
   */
  public static SqLite connectTemp() throws SQLException {
    return connect("");
  }

  /**
   * Prepares a DROP TABLE statement.
   */
  public PreparedStatement dropTable(String name) throws SQLException {
    return connection.prepareStatement("drop table if exists " + name);
  }

  /**
   * Prepares a CREATE TABLE statement.
   */
  public PreparedStatement createTable(String name, List<String> columns) throws SQLException {
    return connection.prepareStatement(String.format(
        "create table if not exists %s(%s)", name, Joiner.on(",").join(columns)));
  }

  /**
   * Prepares an INSERT VALUES statement.
   */
  public PreparedStatement insertValues(String name, int nColumns) throws SQLException {
    return connection.prepareStatement(String.format("insert into %s values (%s)",
      name, Joiner.on(",").join(repeat("?", nColumns))));
  }

  /**
   * Attaches a database to the current connection. The databaseName is the alias that the
   * database will be mapped to for referencing tables.
   */
  public void attach(String databasePath, String databaseName) throws SQLException {
    connection.setAutoCommit(true);  // Don't use a transaction.
    PreparedStatement s = connection.prepareStatement("attach database ? as ?");
    s.setString(1, databasePath);
    s.setString(2, databaseName);
    s.execute();
    connection.setAutoCommit(false);
  }

  /**
   * Serializes the schemas for all attached databases to a SqSchema proto.
   */
  public SqSchema getSchema() throws SQLException {
    SqSchema.Builder b = SqSchema.newBuilder();
    for (SqDatabase database : getDatabases()) {
      SqDatabase.Builder databaseB = database.toBuilder();
      for (SqObject object : getObjects(database.getDatabaseName())) {
        String objectType = object.getObjectType();
        if (objectType.equals("table")) {
          SqTable.Builder table = SqTable.newBuilder().setTableName(object.getObjectName());
          table.addAllColumn(getColumns(database.getDatabaseName(), table.getTableName()));
          databaseB.addTable(table);
        } else if (objectType.equals("index")) {
          SqIndex.Builder index = SqIndex.newBuilder()
              .setIndexName(object.getObjectName())
              .setTableName(object.getAssociatedTable());
          index.addAllColumn(getIndexColumns(database.getDatabaseName(), index.getIndexName()));
          databaseB.addIndex(index);
        } else {
          databaseB.addObject(object);
        }
      }
      b.addDatabase(databaseB);
    }
    return b.build();
  }

  /**
   * Returns the results of a database_list query.
   */
  private List<SqDatabase> getDatabases() throws SQLException {
    Statement s = connection.createStatement();
    try {
      ResultSet rs = s.executeQuery("pragma database_list");
      List<SqDatabase> output = Lists.newArrayListWithCapacity(20);
      while (rs.next()) {
        output.add(toSqDatabase(rs));
      }
      return output;
    } finally {
      s.close();
    }
  }

  /**
   * Returns the contents of the sqlite_master table for a database.
   */
  private List<SqObject> getObjects(String databaseName) throws SQLException {
    Statement s = connection.createStatement();
    try {
      ResultSet rs = s.executeQuery(String.format("select * from '%s'.sqlite_master", databaseName));
      List<SqObject> output = Lists.newArrayListWithCapacity(20);
      while (rs.next()) {
        output.add(toSqObject(rs));
      }
      return output;
    } finally {
      s.close();
    }
  }

  /**
   * Returns the results of a table_info query.
   */
  private List<SqColumn> getColumns(String databaseName, String tableName) throws SQLException {
    Statement s = connection.createStatement();
    try {
      ResultSet rs = s.executeQuery(String.format("pragma '%s'.table_info(%s)", databaseName, tableName));
      List<SqColumn> output = Lists.newArrayListWithCapacity(20);
      while (rs.next()) {
        output.add(toSqColumn(rs));
      }
      return output;
    } finally {
      s.close();
    }
  }

  /**
   * Returns the columns in index-rank order from an index_info query.
   */
  private List<String> getIndexColumns(String databaseName, String indexName) throws SQLException {
    Statement s = connection.createStatement();
    try {
      // TODO validate database and table names
      ResultSet rs = s.executeQuery(String.format("pragma '%s'.index_info(%s)", databaseName, indexName));
      TreeMap<Integer, String> indexColumns = Maps.newTreeMap();
      while (rs.next()) {
        int indexRank = rs.getInt(1);
        if (!rs.wasNull()) {
          indexColumns.put(indexRank, rs.getString(3));
        }
      }
      return ImmutableList.copyOf(indexColumns.values());
    } finally {
      s.close();
    }
  }

  /**
   * Serializes results from a database_list query.
   */
  private static SqDatabase toSqDatabase(ResultSet rs) throws SQLException {
    SqDatabase.Builder b = SqDatabase.newBuilder();
    int seq = rs.getInt(SqDatabase.SEQ_NO_FIELD_NUMBER);
    if (!rs.wasNull()) {
      b.setSeqNo(seq);
    }
    String name = rs.getString(SqDatabase.DATABASE_NAME_FIELD_NUMBER);
    if (name != null) {
      b.setDatabaseName(name);
    }
    String file = rs.getString(SqDatabase.DATABASE_PATH_FIELD_NUMBER);
    if (file != null) {
      b.setDatabasePath(file);
    }
    return b.build();
  }

  /**
   * Serializes results from a sqlite_master query.
   */
  private static SqObject toSqObject(ResultSet rs) throws SQLException {
    SqObject.Builder b = SqObject.newBuilder();
    String objectType = rs.getString(SqObject.OBJECT_TYPE_FIELD_NUMBER);
    if (objectType != null) {
      b.setObjectType(objectType);
    }
    String objectName = rs.getString(SqObject.OBJECT_NAME_FIELD_NUMBER);
    if (objectName != null) {
      b.setObjectName(objectName);
    }
    String associatedTable = rs.getString(SqObject.ASSOCIATED_TABLE_FIELD_NUMBER);
    if (associatedTable != null) {
      b.setAssociatedTable(associatedTable);
    }
    long rootPage = rs.getLong(SqObject.ROOT_PAGE_FIELD_NUMBER);
    if (!rs.wasNull()) {
      b.setRootPage(rootPage);
    }
    String sqlText = rs.getString(SqObject.SQL_TEXT_FIELD_NUMBER);
    if (sqlText != null) {
      b.setSqlText(sqlText);
    }
    return b.build();
  }

  /**
   * Serializes results from a table_info query.
   */
  private static SqColumn toSqColumn(ResultSet rs) throws SQLException {
    SqColumn.Builder b = SqColumn.newBuilder();
    int columnId = rs.getInt(SqColumn.COLUMN_ID_FIELD_NUMBER);
    if (!rs.wasNull()) {
      // Shift to correspond with proto field numbers.
      b.setColumnId(columnId + 1);
    }
    String columnName = rs.getString(SqColumn.COLUMN_NAME_FIELD_NUMBER);
    if (columnName != null) {
      b.setColumnName(columnName);
    }
    String dataType = rs.getString(SqColumn.DATA_TYPE_FIELD_NUMBER);
    if (dataType != null) {
      b.setDataType(dataType.toLowerCase());
    }
    int notNull = rs.getInt(SqColumn.NULLABLE_FIELD_NUMBER);
    if (!rs.wasNull()) {
      // Convert this to a positive boolean.
      b.setNullable(notNull == 0);
    }
    String defaultValue = rs.getString(SqColumn.DEFAULT_VALUE_FIELD_NUMBER);
    if (defaultValue != null) {
      b.setDefaultValue(defaultValue);
    }
    int pkIndex = rs.getInt(SqColumn.PK_INDEX_FIELD_NUMBER);
    // Zero means the column isn't part of the primary key.
    if (!rs.wasNull() && pkIndex > 0) {
      b.setPkIndex(pkIndex);
    }
    return b.build();
  }

  /**
   * Returns the ResultSetMetaData as SqColumn records.
   */
  public static List<SqColumn> getColumns(ResultSet rs) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int columnCount = md.getColumnCount();
    List<SqColumn> output = Lists.newArrayListWithCapacity(columnCount);
    for (int i = 1; i <= columnCount; ++i) {
      output.add(SqColumn.newBuilder()
          .setColumnName(md.getColumnName(i))
          .setDataType(md.getColumnTypeName(i).toLowerCase()).build());
    }
    return output;
  }

  /**
   * Repeats the input value N times into a list.
   */
  private static <T> List<T> repeat(T value, int n) {
    List<T> values = Lists.newArrayListWithCapacity(n);
    for (int i = 0; i < n; ++i) {
      values.add(value);
    }
    return values;
  }
}
