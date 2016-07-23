package org.ij.p4p.util;

import static com.google.common.truth.Truth.assertThat;

import org.ij.p4p.Tests;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.protobuf.TextFormat;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;

public class SqProtoTest extends TestCase {
  public SqProtoTest(String testName) {
    super(testName);
  }
  public static Test suite() {
    return new TestSuite(SqProtoTest.class);
  }

  /**
   * Creates a SQLite database with a schema corresponding to a CatsSnapshot,
   * reads the database schema into a SqSchema proto, and validates the schema.
   */
  public void testSchema() throws Exception {
    SqProto<GroceryStore> sqProto = SqProto.create(
        SqLite.connectTemp(), GroceryStore.getDefaultInstance());
    sqProto.dropAndCreateTables();
    // Add an index so that we can verify in the schema output.
    Statement s = sqProto.sqLite.connection.createStatement();
    s.executeUpdate("create unique index fresh_produce_id on fresh_produce(produce_id)");
    SqSchema sqSchema = sqProto.sqLite.getSchema();
    Tests.writeTargetFile("grocery_store_schema.pbtxt", sqSchema);
    assertThat(sqSchema).isEqualTo(readSqSchema("grocery_store_schema.pbtxt"));
  }

  /**
   * Populates a database, reads the contents back, and verifies.
   */
  public void testRoundTrip() throws Exception {
    GroceryStore expected = getGroceryData();
    SqProto<GroceryStore> sqProto = SqProto.create(
        SqLite.connectTemp(), GroceryStore.getDefaultInstance());
    sqProto.dropAndCreateTables();
    sqProto.insertSnapshot(expected);
    assertThat(sqProto.snapshot()).isEqualTo(expected);
  }

  /**
   * Populates a database, queries, and reads the results into a proto.
   */
  public void testMergeFrom() throws Exception {
    SqProto<GroceryStore> sqProto = SqProto.create(
        SqLite.connectTemp(), GroceryStore.getDefaultInstance());
    sqProto.dropAndCreateTables();
    sqProto.insertSnapshot(getGroceryData());
    Statement s = sqProto.sqLite.connection.createStatement();
    // Note that is_organic is not read into the result proto, and the
    // profit_margin field is not present in the result set.
    ResultSet rs = s.executeQuery(
        "select produce_id, is_organic, " +
        "cast(round(100 * (retail_price - wholesale_cost)) as int) as margin_cents " +
        "from fresh_produce");
    // Inspect the resultset metadata.
    List<SqColumn> columns = sqProto.sqLite.getColumns(rs);
    assertThat(columns.size()).isEqualTo(3);
    assertThat(columns.get(0)).isEqualTo(SqColumn.newBuilder()
        .setColumnName("produce_id").setDataType("int").build());
    assertThat(columns.get(1)).isEqualTo(SqColumn.newBuilder()
        .setColumnName("is_organic").setDataType("boolean").build());
    // Inspect the resultset data parsed into protos.
    List<MarginCalculation> results = sqProto.readIntoList(
        rs, MarginCalculation.getDefaultInstance());
    assertThat(results.size()).isEqualTo(2);
    assertThat(results.get(0)).isEqualTo(MarginCalculation.newBuilder()
        .setProduceId(1).setMarginCents(6).build());
    assertThat(results.get(1)).isEqualTo(MarginCalculation.newBuilder()
        .setProduceId(2).setMarginCents(11).build());
  }

  /**
   * Reads an SqSchema proto from a text resource.
   */
  public static SqSchema readSqSchema(String path) throws Exception {
    SqSchema.Builder b = SqSchema.newBuilder();
    TextFormat.merge(Tests.openTextResource(path), b);
    return b.build();
  }

  /**
   * Builds a small dataset for populating a database for testing.
   */
  public static GroceryStore getGroceryData() {
    VendorData vendor1 = VendorData.newBuilder()
        .setName("Perfect Produce LLC").build();
    VendorData vendor2 = VendorData.newBuilder()
        .setName("Best Produce LLC").build();
    GroceryStore.Builder b = GroceryStore.newBuilder();
    b.addFreshProduceBuilder()
        .setProduceId(1)
        .setType(Produce.Type.APPLE)
        .setDescription("Honey Crisp")
        .setRetailPrice(2.99)
        .setWholesaleCost(2.93f)
        .setExpirationDays(10)
        .setIsOrganic(true)
        .setVendorData(vendor1.toByteString());
    b.addFreshProduceBuilder()
        .setProduceId(2)
        .setType(Produce.Type.ORANGE)
        .setDescription("Florida Navel")
        .setRetailPrice(3.99)
        .setWholesaleCost(3.88f)
        .setExpirationDays(12)
        .setIsOrganic(false)
        .setVendorData(vendor2.toByteString());
    b.addFrozenProduceBuilder()
        .setProduceId(3)
        .setType(Produce.Type.BROCCOLI)
        .setDescription("Broccoli Head")
        .setRetailPrice(1.99)
        .setWholesaleCost(1.97f)
        .setExpirationDays(7)
        .setVendorData(vendor1.toByteString());
    b.addFrozenProduceBuilder()
        .setProduceId(4)
        .setType(Produce.Type.CARROTS)
        .setDescription("Organic Carrots")
        .setRetailPrice(2.19)
        .setWholesaleCost(2.14f)
        .setExpirationDays(10)
        .setIsOrganic(true);
    b.addFrozenProduceBuilder()
        .setProduceId(5);
    return b.build();
  }
}
