package com.bytezone.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

// -----------------------------------------------------------------------------------//
class UploadContextTest
// -----------------------------------------------------------------------------------//
{
  @TempDir Path tempDir;

  // ---------------------------------------------------------------------------------//
  private File createTempFile (String... lines) throws IOException
  // ---------------------------------------------------------------------------------//
  {
    Path file = tempDir.resolve ("test.txt");
    Files.write (file, List.of (lines), StandardCharsets.UTF_8);
    return file.toFile ();
  }

  // ---------------------------------------------------------------------------------//
  @Nested class Preparation
  // ---------------------------------------------------------------------------------//
  {
    @Test void readsAllLines () throws IOException
    {
      File f = createTempFile ("line1", "line2", "line3");
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, false,
          UploadContext.UploadMode.REPLACE);
      ctx.prepare ();

      assertEquals (3, ctx.getTotalLines ());
      assertFalse (ctx.isFinished ());
    }

    @Test void truncatesLongLines () throws IOException
    {
      File f = createTempFile ("A".repeat (100));
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, false,
          UploadContext.UploadMode.REPLACE);
      List<String> errors = ctx.prepare ();

      assertTrue (errors.isEmpty ());
      List<String> block = ctx.getNextBlock (1);
      assertEquals (80, block.get (0).length ());
    }

    @Test void rejectsLongLinesWhenNoTruncate () throws IOException
    {
      File f = createTempFile ("A".repeat (100));
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, false, false,
          UploadContext.UploadMode.REPLACE);
      List<String> errors = ctx.prepare ();

      assertFalse (errors.isEmpty ());
      assertTrue (errors.get (0).contains ("excede LRECL"));
    }

    @Test void handlesEmptyFile () throws IOException
    {
      File f = createTempFile ();
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, false,
          UploadContext.UploadMode.REPLACE);
      ctx.prepare ();

      assertEquals (0, ctx.getTotalLines ());
      assertTrue (ctx.isFinished ());
    }

    @Test void linesWithinLreclAreUnchanged () throws IOException
    {
      File f = createTempFile ("short line", "another");
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, false,
          UploadContext.UploadMode.REPLACE);
      ctx.prepare ();

      List<String> block = ctx.getNextBlock (2);
      assertEquals ("short line", block.get (0));
      assertEquals ("another", block.get (1));
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested class CobolNumbering
  // ---------------------------------------------------------------------------------//
  {
    @Test void stripsCobolColumns () throws IOException
    {
      //         123456|content (up to 66 chars)                        |ident.
      String cobolLine = "000100       IDENTIFICATION DIVISION.                                  MYPGM";
      File f = createTempFile (cobolLine);
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, true,
          UploadContext.UploadMode.REPLACE);
      ctx.prepare ();

      List<String> block = ctx.getNextBlock (1);
      String result = block.get (0);
      // Should have stripped cols 1-6 and limited to 66 chars
      assertFalse (result.startsWith ("000100"));
      assertTrue (result.length () <= 66);
    }

    @Test void handlesShortCobolLines () throws IOException
    {
      File f = createTempFile ("12345");
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, true,
          UploadContext.UploadMode.REPLACE);
      ctx.prepare ();

      List<String> block = ctx.getNextBlock (1);
      assertEquals ("", block.get (0));
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested class BlockRetrieval
  // ---------------------------------------------------------------------------------//
  {
    @Test void retrievesBlocksSequentially () throws IOException
    {
      File f = createTempFile ("L01", "L02", "L03", "L04", "L05");
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, false,
          UploadContext.UploadMode.REPLACE);
      ctx.prepare ();

      List<String> b1 = ctx.getNextBlock (2);
      assertEquals (2, b1.size ());
      assertEquals ("L01", b1.get (0));
      assertEquals ("L02", b1.get (1));
      assertEquals (3, ctx.getLinesRemaining ());

      List<String> b2 = ctx.getNextBlock (10);
      assertEquals (3, b2.size ());
      assertTrue (ctx.isFinished ());
    }

    @Test void emptyBlockWhenFinished () throws IOException
    {
      File f = createTempFile ("only");
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, false,
          UploadContext.UploadMode.REPLACE);
      ctx.prepare ();

      ctx.getNextBlock (10);
      assertTrue (ctx.isFinished ());
      assertTrue (ctx.getNextBlock (10).isEmpty ());
    }

    @Test void blockSizeLargerThanRemaining () throws IOException
    {
      File f = createTempFile ("A", "B", "C");
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, false,
          UploadContext.UploadMode.REPLACE);
      ctx.prepare ();

      List<String> block = ctx.getNextBlock (100);
      assertEquals (3, block.size ());
      assertEquals (0, ctx.getLinesRemaining ());
    }

    @Test void linesSentTracksProgress () throws IOException
    {
      File f = createTempFile ("A", "B", "C", "D", "E");
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, false,
          UploadContext.UploadMode.REPLACE);
      ctx.prepare ();

      assertEquals (0, ctx.getLinesSent ());
      ctx.getNextBlock (2);
      assertEquals (2, ctx.getLinesSent ());
      ctx.getNextBlock (3);
      assertEquals (5, ctx.getLinesSent ());
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested class ModeAndMetadata
  // ---------------------------------------------------------------------------------//
  {
    @Test void preservesReplaceMode () throws IOException
    {
      File f = createTempFile ("x");
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, false,
          UploadContext.UploadMode.REPLACE);
      assertEquals (UploadContext.UploadMode.REPLACE, ctx.getMode ());
    }

    @Test void preservesAppendMode () throws IOException
    {
      File f = createTempFile ("x");
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, false,
          UploadContext.UploadMode.APPEND);
      assertEquals (UploadContext.UploadMode.APPEND, ctx.getMode ());
    }

    @Test void reportsLrecl () throws IOException
    {
      File f = createTempFile ("x");
      UploadContext ctx = new UploadContext (
          f, 133, StandardCharsets.UTF_8, true, false,
          UploadContext.UploadMode.REPLACE);
      assertEquals (133, ctx.getLrecl ());
    }

    @Test void reportsLocalFile () throws IOException
    {
      File f = createTempFile ("x");
      UploadContext ctx = new UploadContext (
          f, 80, StandardCharsets.UTF_8, true, false,
          UploadContext.UploadMode.REPLACE);
      assertEquals (f, ctx.getLocalFile ());
    }
  }
}
