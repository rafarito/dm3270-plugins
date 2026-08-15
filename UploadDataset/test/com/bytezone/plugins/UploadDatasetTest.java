package com.bytezone.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.bytezone.dm3270.commands.AIDCommand;
import com.bytezone.dm3270.plugins.PluginData;
import com.bytezone.dm3270.plugins.PluginField;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

// -----------------------------------------------------------------------------------//
class UploadDatasetTest
// -----------------------------------------------------------------------------------//
{
  @TempDir Path tempDir;

  // ---------------------------------------------------------------------------------//
  @Nested class ScreenDetection
  // ---------------------------------------------------------------------------------//
  {
    @Test void detectsEditScreen ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = editScreen ("EDIT  USER1.COBOL.SRC(MYPGM)");

      assertTrue (plugin.isEditScreen (data));
    }

    @Test void detectsRfeEditScreen ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = editScreen ("RFEEDIT  USER1.COBOL.SRC(MYPGM)");

      assertTrue (plugin.isEditScreen (data));
    }

    @Test void rejectsBrowseScreen ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = buildScreen ("BROWSE  USER1.COBOL.SRC(MYPGM)");

      assertFalse (plugin.isEditScreen (data));
    }

    @Test void rejectsViewScreen ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = buildScreen ("VIEW  USER1.COBOL.SRC(MYPGM)");

      assertFalse (plugin.isEditScreen (data));
    }

    @Test void detectsDatasetName ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = editScreen ("EDIT  USER1.COBOL.SRC(MYPGM)");

      assertEquals ("USER1.COBOL.SRC(MYPGM)",
          plugin.detectDatasetName (data));
    }

    @Test void detectsRfeDatasetName ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = editScreen ("RFEEDIT  SYS1.PARMLIB(IEASYS00)");

      assertEquals ("SYS1.PARMLIB(IEASYS00)",
          plugin.detectDatasetName (data));
    }

    @Test void returnsNullForUnrecognizedScreen ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = buildScreen ("ISPF Primary Option Menu");

      assertNull (plugin.detectDatasetName (data));
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested class CommandField
  // ---------------------------------------------------------------------------------//
  {
    @Test void findsCommandInputField ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = editScreen ("EDIT  USER1.SRC(TEST)");

      PluginField cmd = plugin.findCommandField (data);
      assertNotNull (cmd);
      assertTrue (cmd.isModifiable);
    }

    @Test void returnsNullWhenNoCommandField ()
    {
      UploadDataset plugin = createPlugin ();
      // Tela sem "Command ===>"
      PluginData data = new ScreenBuilder ()
          .protectedField (0, 0, "EDIT  USER1.SRC(X)")
          .protectedField (1, 0, "Something else")
          .inputField (1, 20, 40, "")
          .build ();

      assertNull (plugin.findCommandField (data));
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested class NumberField
  // ---------------------------------------------------------------------------------//
  {
    @Test void findsLastNumberField ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = editScreenWithData ();

      PluginField numField = plugin.findLastNumberField (data);
      assertNotNull (numField);
      assertFalse (numField.isProtected);
      assertEquals (6, numField.getLength ());
      // Deve encontrar a ultima linha de dados na tela (000300)
      assertEquals ("000300", numField.getFieldValue ().trim ());
    }

    @Test void skipsHeaderFields ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = editScreenWithData ();

      PluginField numField = plugin.findLastNumberField (data);
      // Deve estar na row >= 2 (abaixo do header)
      assertTrue (numField.location.row >= 2);
    }

    @Test void prefersDataLineOverMarker ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = editScreenWithData ();

      PluginField numField = plugin.findLastNumberField (data);
      // Deve retornar a ultima linha de dados, nao o marcador
      String value = numField.getFieldValue ();
      assertNotEquals ("******", value.trim ());
    }

    @Test void emptyDatasetReturnsTopOfData ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = editScreenEmpty ();

      PluginField numField = plugin.findLastNumberField (data);
      // Deve retornar o Top of Data como fallback
      assertNotNull (numField);
      assertEquals ("******", numField.getFieldValue ().trim ());
      // Deve ser row 2 (Top of Data), nao row 3 (Bottom of Data)
      assertEquals (2, numField.location.row);
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested class EmptyLineDetection
  // ---------------------------------------------------------------------------------//
  {
    @Test void findsEmptyInsertLines ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = editScreenWithEmptyLines (3);

      List<PluginField> emptyLines = plugin.findEmptyInsertLines (data);
      assertEquals (3, emptyLines.size ());
    }

    @Test void ignoresFilledLines ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = editScreenWithData ();

      List<PluginField> emptyLines = plugin.findEmptyInsertLines (data);
      assertEquals (0, emptyLines.size ());
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested class StateMachine
  // ---------------------------------------------------------------------------------//
  {
    @Test void processAutoDeleteTransitionsToInsert () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("line1", "line2");
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.DELETING);
      plugin.setDoesAuto (true);

      // Tela pos-DELETE: dataset vazio, sem linhas de numero
      PluginData data = editScreen ("EDIT  USER1.SRC(TEST)");

      plugin.processAuto (data);

      // Deve ter transicionado para INSERTING_CMD e definido key ENTER
      assertEquals (UploadDataset.UploadState.INSERTING_CMD, plugin.getState ());
      assertEquals (AIDCommand.AID_ENTER, data.getKey ());
    }

    @Test void processAutoGoingBottomTransitionsToInsert () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("line1");
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.GOING_BOTTOM);
      plugin.setDoesAuto (true);

      PluginData data = editScreenWithData ();

      plugin.processAuto (data);

      assertEquals (UploadDataset.UploadState.INSERTING_CMD, plugin.getState ());
    }

    @Test void processAutoInsertCmdFillsEmptyLines () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("content1", "content2");
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.INSERTING_CMD);
      plugin.setDoesAuto (true);

      PluginData data = editScreenWithEmptyLines (2);

      plugin.processAuto (data);

      assertEquals (UploadDataset.UploadState.FILLING_LINES, plugin.getState ());
      // As linhas devem ter sido preenchidas
      assertEquals (2, ctx.getLinesSent ());
    }

    @Test void transitionsToSaveWhenFinished () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("only line");
      ctx.prepare ();
      ctx.getNextBlock (1);  // consume all lines
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.FILLING_LINES);
      plugin.setDoesAuto (true);

      PluginData data = editScreen ("EDIT  USER1.SRC(TEST)");

      plugin.processAuto (data);

      assertEquals (UploadDataset.UploadState.SAVING, plugin.getState ());
    }

    @Test void postSaveCompletesUpload () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("line");
      ctx.prepare ();
      ctx.getNextBlock (1);
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.SAVING);
      plugin.setDoesAuto (true);

      PluginData data = editScreen ("EDIT  USER1.SRC(TEST)");

      plugin.processAuto (data);

      assertEquals (UploadDataset.UploadState.DONE, plugin.getState ());
      assertFalse (plugin.doesAuto ());
      assertNull (plugin.getContext ());
    }

    @Test void abortsOnNonEditScreen () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("line");
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.FILLING_LINES);
      plugin.setDoesAuto (true);

      PluginData data = buildScreen ("ISPF Primary Option Menu");

      plugin.processAuto (data);

      assertEquals (UploadDataset.UploadState.IDLE, plugin.getState ());
      assertFalse (plugin.doesAuto ());
    }
  }

  // ---------------------------------------------------------------
  // Helper methods
  // ---------------------------------------------------------------

  // ---------------------------------------------------------------------------------//
  private UploadDataset createPlugin ()
  // ---------------------------------------------------------------------------------//
  {
    UploadDataset plugin = new UploadDataset ();
    plugin.setAlertHandler ((type, msg) ->
    {
      // Swallow alerts in tests
    });
    plugin.activate ();
    return plugin;
  }

  // ---------------------------------------------------------------------------------//
  private UploadContext createContext (String... lines) throws IOException
  // ---------------------------------------------------------------------------------//
  {
    Path file = tempDir.resolve ("upload_test.txt");
    Files.write (file, List.of (lines), StandardCharsets.UTF_8);
    return new UploadContext (file.toFile (), 80, StandardCharsets.UTF_8,
        true, false, UploadContext.UploadMode.REPLACE);
  }

  // ---------------------------------------------------------------------------------//
  private static PluginData editScreen (String title)
  // ---------------------------------------------------------------------------------//
  {
    return ScreenBuilder
        .ispfEditScreen (title, "Columns 00001 00072")
        .build ();
  }

  // ---------------------------------------------------------------------------------//
  private static PluginData buildScreen (String title)
  // ---------------------------------------------------------------------------------//
  {
    return new ScreenBuilder ()
        .protectedField (0, 0, title)
        .protectedField (1, 0, "Option ===>")
        .inputField (1, 20, 40, "")
        .build ();
  }

  // ---------------------------------------------------------------------------------//
  private static PluginData editScreenWithData ()
  // ---------------------------------------------------------------------------------//
  {
    return ScreenBuilder
        .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072")
        .dataLine (3, "000100", "       IDENTIFICATION DIVISION.")
        .dataLine (4, "000200", "       PROGRAM-ID. TEST.")
        .dataLine (5, "000300", "       DATA DIVISION.")
        .build ();
  }

  // ---------------------------------------------------------------------------------//
  private static PluginData editScreenWithEmptyLines (int count)
  // ---------------------------------------------------------------------------------//
  {
    ScreenBuilder sb = ScreenBuilder
        .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072");

    for (int i = 0; i < count; i++)
      sb.dataLine (3 + i, "''''''", "");

    return sb.build ();
  }

  /** Dataset vazio apos DELETE ALL: so tem Top of Data e Bottom of Data. */
  // ---------------------------------------------------------------------------------//
  private static PluginData editScreenEmpty ()
  // ---------------------------------------------------------------------------------//
  {
    return ScreenBuilder
        .ispfEditScreen ("RFEEDIT  USER1.SRC(TEST)", "Columns 1 72")
        .dataLine (2, "******",
            "***************************** Top of Data ******************************")
        .dataLine (3, "******",
            "**************************** Bottom of Data ****************************")
        .build ();
  }
}
