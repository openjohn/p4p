package org.ij.p4p;

import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.protobuf.ByteString;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Helper functions for unit testing.
 */
public class Tests {

  /**
   * Opens a Java resource as a binary input stream.
   */
  public static InputStream openResourceStream(String path) {
    InputStream in = Tests.class.getResourceAsStream("/" + path);
    if (in == null) {
      throw new RuntimeException("Invalid resource path: " + path);
    }
    return in;
  }

  /**
   * Opens a Java resource as an input stream in UTF-8 format.
   */
  public static InputStreamReader openTextResource(String path) throws Exception {
    return new InputStreamReader(openResourceStream(path), "UTF-8");
  }

  /**
   * Opens a Java resource and returns the UTF-8 contents.
   */
  public static String readText(String path) throws Exception {
    return CharStreams.toString(openTextResource(path));
  }

  /**
   * Opens a Java resource and returns the contents as a ByteString.
   */
  public static ByteString readBytes(String path) throws Exception {
    return ByteString.copyFrom(
        ByteStreams.toByteArray(openResourceStream(path)));
  }

  /**
   * Serializes the map to a zipped byte array and returns a zip input stream
   * reading from the contents.
   */
  public static ZipInputStream toZipInputStream(
      Map<ZipEntry, ByteString> entries) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ZipOutputStream zip = new ZipOutputStream(out, Charsets.UTF_8);
    for (Map.Entry<ZipEntry, ByteString> e : entries.entrySet()) {
      zip.putNextEntry(e.getKey());
      ByteString contents = e.getValue();
      zip.write(contents.toByteArray(), 0, contents.size());
      zip.closeEntry();
    }
    zip.finish();
    return new ZipInputStream(
        new ByteArrayInputStream(out.toByteArray()),
        Charsets.UTF_8);
  }

  /**
   * Writes a string to a file in the Maven target directory.
   */
  public static void writeTargetFile(String path, Object content) throws Exception {
    File mavenTargetPath = new File("target/test-output");
    File mavenTargetFile = new File("target/test-output/" + path);
    mavenTargetPath.mkdirs();
    PrintWriter pw = new PrintWriter(mavenTargetFile, "UTF-8");
    pw.print(content.toString());
    pw.flush();
    pw.close();
  }
}
