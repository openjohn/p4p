package org.ij.p4p.util;

import static com.google.common.truth.Truth.assertThat;

import org.ij.p4p.Tests;
import org.ij.p4p.cats.CatsRawSnapshot;
import org.ij.p4p.cats.RawAsset;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.io.InputStreamReader;
import java.util.Map;
import java.util.zip.ZipEntry;

public class CsvToPbTest extends TestCase {
  public CsvToPbTest(String testName) {
    super(testName);
  }
  public static Test suite() {
    return new TestSuite(CsvToPbTest.class);
  }

  public void testParseCatsData() throws Exception {
    Map<ZipEntry, ByteString> entries = ImmutableMap.of(
        new ZipEntry("ASSET_T"), Tests.readBytes("cats_asset_table_head.csv"),
        new ZipEntry("DAG71_T"), Tests.readBytes("cats_equitable_sharing_table_head.csv"));
    CatsRawSnapshot snap = CsvToPb.parseTablesFromZip(
        Tests.toZipInputStream(entries),
        CSVFormat.EXCEL,
        CatsRawSnapshot.getDefaultInstance());
    Tests.writeTargetFile("cats_raw_snapshot.pbtxt", snap);
    CatsRawSnapshot expected = readSnapshot("cats_raw_snapshot.pbtxt");
    assertThat(snap).isEqualTo(expected);
  }

  public void testParseInconsistent() throws Exception {
    // Last field of the last record is missing (comma truncated).
    Map<ZipEntry, ByteString> entries = ImmutableMap.of(
        new ZipEntry("ASSET_T"), Tests.readBytes("cats_asset_table_inconsistent.csv"));
    try {
      CsvToPb.parseTablesFromZip(
          Tests.toZipInputStream(entries),
          CSVFormat.EXCEL,
          CatsRawSnapshot.getDefaultInstance());
      fail("Expected an exception");
    } catch (CsvToPb.InconsistentRecordException e) {
      assertThat(e.getMessage()).isEqualTo("table:ASSET_T record:2 fields:73");
      assertThat(e.inconsistent.table.getNumber()).isEqualTo(1);
    }
  }

  public static CatsRawSnapshot readSnapshot(String path) throws Exception {
    CatsRawSnapshot.Builder b = CatsRawSnapshot.newBuilder();
    TextFormat.merge(Tests.openTextResource(path), b);
    return b.build();
  }
}
