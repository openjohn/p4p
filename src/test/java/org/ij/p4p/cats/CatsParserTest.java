package org.ij.p4p.cats;

import static com.google.common.truth.Truth.assertThat;

import org.ij.p4p.Tests;
import org.ij.p4p.util.SqLite;
import org.ij.p4p.util.SqProto;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.io.InputStreamReader;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;

public class CatsParserTest extends TestCase {
  public CatsParserTest(String testName) {
    super(testName);
  }
  public static Test suite() {
    return new TestSuite(CatsParserTest.class);
  }

  public void testParseData() throws Exception {
    SqProto<CatsSnapshot> sqProto = SqProto.create(
        SqLite.connectInMem(),
        CatsSnapshot.getDefaultInstance());
    Map<ZipEntry, ByteString> entries = ImmutableMap.of(
        new ZipEntry("ASSET_T"), Tests.readBytes("cats_asset_table_head.csv"),
        new ZipEntry("DAG71_T"), Tests.readBytes("cats_equitable_sharing_table_head.csv"));
    List<ParseIssue> parseIssues = CatsParser.importIntoDatabase(
        Tests.toZipInputStream(entries), sqProto);
    List<ParseIssue> expected = ImmutableList.of(
        ParseIssue.newBuilder()
            .setType(ParseIssue.Type.BATCH_RECORDS)
            .setTableId(1).setRecordCount(2).build(),
        ParseIssue.newBuilder()
            .setType(ParseIssue.Type.BATCH_RECORDS)
            .setTableId(2).setRecordCount(2).build());
    assertThat(parseIssues).isEqualTo(expected);
    CatsSnapshot snap = sqProto.snapshot();
    Tests.writeTargetFile("cats_snapshot.pbtxt", snap);
    assertThat(snap).isEqualTo(readSnapshot("cats_snapshot.pbtxt"));
  }

  public void testParseInconsistent() throws Exception {
    SqProto<CatsSnapshot> sqProto = SqProto.create(
        SqLite.connectInMem(),
        CatsSnapshot.getDefaultInstance());
    Map<ZipEntry, ByteString> entries = ImmutableMap.of(
        new ZipEntry("ASSET_T"), Tests.readBytes("cats_asset_table_inconsistent.csv"),
        new ZipEntry("DAG71_T"), Tests.readBytes("cats_equitable_sharing_table_head.csv"));
    List<ParseIssue> parseIssues = CatsParser.importIntoDatabase(
        Tests.toZipInputStream(entries), sqProto);
    List<ParseIssue> expected = ImmutableList.of(
        ParseIssue.newBuilder()
            .setType(ParseIssue.Type.INCONSISTENT_RECORD)
            .setTableId(1).setRecordId(2).setFieldCount(73).build(),
        ParseIssue.newBuilder()
            .setType(ParseIssue.Type.BATCH_RECORDS)
            .setTableId(1).setRecordCount(1).build(),
        ParseIssue.newBuilder()
            .setType(ParseIssue.Type.BATCH_RECORDS)
            .setTableId(2).setRecordCount(2).build());
    assertThat(parseIssues).isEqualTo(expected);
    CatsSnapshot expectedSnapshot = readSnapshot("cats_snapshot.pbtxt");
    // Remove the second asset record from the expected data.
    expectedSnapshot = expectedSnapshot.toBuilder().clearAsset()
        .addAsset(expectedSnapshot.getAsset(0)).build();
    assertThat(sqProto.snapshot()).isEqualTo(expectedSnapshot);
  }

  public static CatsSnapshot readSnapshot(String path) throws Exception {
    CatsSnapshot.Builder b = CatsSnapshot.newBuilder();
    TextFormat.merge(Tests.openTextResource(path), b);
    return b.build();
  }
}
