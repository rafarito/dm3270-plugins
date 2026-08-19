package com.bytezone.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

  /** Alertas emitidos pelo plugin durante o teste, no lugar do dialogo do JavaFX. */
  private final List<String> alerts = new ArrayList<> ();

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

    /** Ancorar o I<n> numa insercao pendente colocaria o bloco no lugar errado. */
    @Test void skipsPendingInsertLines ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = ScreenBuilder
          .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072")
          .dataLine (3, "000100", "PRIMEIRA")
          .dataLine (4, "000200", "SEGUNDA")
          .insertLine (5)
          .build ();

      assertEquals ("000200",
          plugin.findLastNumberField (data).getFieldValue ().trim ());
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

    /**
     * Uma linha em branco que ja existia no dataset tem conteudo vazio igual ao
     * de uma linha inserida — o que a distingue e o prefixo.
     */
    @Test void ignoresBlankLinesWithoutTheInsertPrefix ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = ScreenBuilder
          .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072")
          .dataLine (3, "000100", "PRIMEIRA")
          .dataLine (4, "000200", "")          // ja existia, em branco
          .insertLine (5)                      // recem inserida
          .build ();

      List<PluginField> emptyLines = plugin.findEmptyInsertLines (data);
      assertEquals (1, emptyLines.size ());
      assertEquals (5, emptyLines.get (0).location.row);
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested class FieldPadding
  // ---------------------------------------------------------------------------------//
  {
    /**
     * processReply grava com Field.setText(byte[]), que nao apaga o campo antes:
     * sem preencher ate o fim, o "I2" deixaria o resto de "000300" para tras e o
     * ISPF leria "I20300".
     */
    @Test void insertCommandCoversTheWholeNumberField () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("uma", "duas");
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.POSITIONING);
      plugin.setDoesAuto (true);

      PluginData data = editScreenWithData ();
      plugin.processAuto (data);

      PluginField numberField = plugin.findLastNumberField (data);
      assertEquals (6, numberField.newData.length ());
      assertEquals ("I2    ", numberField.newData);
    }

    @Test void saveCommandCoversTheWholeCommandField () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("unica");
      ctx.prepare ();
      ctx.getNextBlock (1);
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.FILLING_LINES);
      plugin.setDoesAuto (true);

      PluginData data = editScreen ("EDIT  USER1.SRC(TEST)");
      plugin.processAuto (data);

      PluginField commandField = plugin.findCommandField (data);
      assertEquals (40, commandField.newData.length ());
      assertEquals ("SAVE", commandField.newData.trim ());
    }

    @Test void truncatesTextLongerThanTheField ()
    {
      PluginData data = editScreen ("EDIT  USER1.SRC(TEST)");
      PluginField field = data.screenFields.get (5);        // scroll, 8 posicoes

      UploadDataset.write (field, "UM TEXTO BEM MAIOR", data);

      assertEquals (8, field.newData.length ());
      assertEquals ("UM TEXTO", field.newData);
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested class BlockSizing
  // ---------------------------------------------------------------------------------//
  {
    @Test void fallsBackToTwentyOnASparseScreen ()
    {
      UploadDataset plugin = createPlugin ();

      // Um dataset recem esvaziado so mostra os dois marcadores: a maior linha
      // ocupada nao diz nada sobre a altura do terminal.
      assertEquals (20, plugin.blockSize (editScreenEmpty ()));
    }

    @Test void growsWithATallerScreen ()
    {
      UploadDataset plugin = createPlugin ();

      ScreenBuilder sb = ScreenBuilder
          .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072");
      for (int row = 3; row <= 42; row++)
        sb.dataLine (row, String.format ("%06d", row * 100), "X");

      assertEquals (40, plugin.blockSize (sb.build ()));
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

    /**
     * O BOTTOM deixa a ultima linha no rodape, sem espaco abaixo dela para as
     * linhas inseridas aparecerem — por isso o LOCATE vem antes do I<n>.
     */
    @Test void processAutoGoingBottomRepositionsFirst () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("line1");
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.GOING_BOTTOM);
      plugin.setDoesAuto (true);

      PluginData data = editScreenWithData ();

      plugin.processAuto (data);

      assertEquals (UploadDataset.UploadState.POSITIONING, plugin.getState ());
      assertEquals ("LOCATE 000300",
          plugin.findCommandField (data).newData.trim ());
    }

    @Test void processAutoPositioningTransitionsToInsert () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("line1");
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.POSITIONING);
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

  // ---------------------------------------------------------------------------------//
  @Nested class BlockThroughput
  // ---------------------------------------------------------------------------------//
  {
    /**
     * O ISPF emenda UMA linha de continuacao a cada ENTER. Preenche-la sozinha e
     * o que fazia o upload andar a uma linha por ida-e-volta depois do primeiro
     * bloco: o plugin tem de pedir um bloco novo em vez de aproveita-la.
     */
    @Test void doesNotFillTheLoneContinuationLine () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext (numberedLines (50));
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.FILLING_LINES);
      plugin.setDoesAuto (true);

      PluginData data = fullScreenWithContinuationLine ();

      plugin.processAuto (data);

      assertEquals (UploadDataset.UploadState.POSITIONING, plugin.getState ());
      assertEquals (0, ctx.getLinesSent ());
    }

    @Test void fillsAWholeScreenOfInsertLinesAtOnce () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext (numberedLines (50));
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.FILLING_LINES);
      plugin.setDoesAuto (true);

      ScreenBuilder sb = ScreenBuilder
          .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072");
      for (int row = 3; row <= 22; row++)
        sb.insertLine (row);

      plugin.processAuto (sb.build ());

      assertEquals (UploadDataset.UploadState.FILLING_LINES, plugin.getState ());
      assertEquals (20, ctx.getLinesSent ());
    }

    /** No fim do arquivo um bloco parcial e o que ha — nao se pede outro. */
    @Test void fillsTheTailEvenWhenSmallerThanABlock () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("A", "B", "C");
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.FILLING_LINES);
      plugin.setDoesAuto (true);

      PluginData data = editScreenWithEmptyLines (3);

      plugin.processAuto (data);

      assertEquals (UploadDataset.UploadState.FILLING_LINES, plugin.getState ());
      assertEquals (3, ctx.getLinesSent ());
    }

    /**
     * Um upload inteiro contra o modelo de ISPF abaixo. Antes da correcao, as 45
     * linhas custavam uma ida-e-volta cada depois do primeiro bloco.
     */
    @Test void wholeUploadStaysUnderADozenRoundTrips () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      String[] source = numberedLines (45);

      UploadContext ctx = createContext (source);
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.DELETING);
      plugin.setDoesAuto (true);

      IspfModel ispf = new IspfModel ();
      int roundTrips = 0;

      while (plugin.doesAuto () && roundTrips < 40)
      {
        PluginData data = ispf.screen ();
        plugin.processAuto (data);
        ispf.apply (data);
        ++roundTrips;
      }

      assertEquals (UploadDataset.UploadState.DONE, plugin.getState (),
          "alertas: " + alerts);
      assertTrue (ispf.saved, "o SAVE nunca chegou ao host");
      assertEquals (List.of (source), ispf.lines);
      // Medido: 10. O limite deixa folga para ajustes sem esconder uma regressao
      // para o antigo ritmo de uma linha por ida-e-volta (que daria ~29 aqui).
      assertTrue (roundTrips <= 12,
          "gastou " + roundTrips + " idas-e-voltas para 45 linhas");
    }

    /** A ordem tem de sobreviver a uma linha em branco no meio do arquivo. */
    @Test void blankLinesInTheFileDoNotShuffleTheUpload () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("L1", "", "L3", "L4");
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.INSERTING_CMD);
      plugin.setDoesAuto (true);

      PluginData first = editScreenWithEmptyLines (2);
      plugin.processAuto (first);
      assertEquals (List.of ("L1", ""), writtenLines (first));

      // A tela volta com as duas gravadas — a segunda em branco — e duas novas
      // linhas de insercao. A linha em branco nao pode ser reaproveitada.
      PluginData second = ScreenBuilder
          .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072")
          .dataLine (3, "000100", "L1")
          .dataLine (4, "000200", "")
          .insertLine (5)
          .insertLine (6)
          .build ();

      plugin.processAuto (second);
      assertEquals (List.of ("L3", "L4"), writtenLines (second));
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested class HorizontalScrolling
  // ---------------------------------------------------------------------------------//
  {
    @Test void handlesLinesLongerThanScreenLimit () throws IOException
    {
      String longLine1 = "A".repeat(80) + "B".repeat(10); // 90 chars
      String longLine2 = "C".repeat(70) + "D".repeat(20); // 90 chars
      UploadContext ctx = createContext (90, longLine1, longLine2);
      ctx.prepare ();

      IspfModel ispf = new IspfModel ();
      ispf.setLrecl (90);
      UploadDataset plugin = createPlugin ();
      plugin.setContext (ctx);

      plugin.setState (UploadDataset.UploadState.DELETING);
      plugin.setDoesAuto (true);

      int roundTrips = 0;
      while (plugin.doesAuto () && roundTrips < 100)
      {
        PluginData data = ispf.screen ();
        plugin.processAuto (data);
        ispf.apply (data);
        ++roundTrips;
      }

      assertEquals (List.of (longLine1, longLine2), ispf.lines);
    }

    @Test void horizontalScrollMathFollowsLreclConstraints () throws IOException
    {
      String veryLongLine = "1234567890".repeat(13) + "123"; // 133 chars
      UploadContext ctx = createContext (133, veryLongLine);
      ctx.prepare ();

      IspfModel ispf = new IspfModel ();
      ispf.setLrecl (133);
      UploadDataset plugin = createPlugin ();
      plugin.setContext (ctx);

      plugin.setState (UploadDataset.UploadState.DELETING);
      plugin.setDoesAuto (true);

      int roundTrips = 0;

      while (plugin.doesAuto () && roundTrips < 100)
      {
        PluginData data = ispf.screen ();
        plugin.processAuto (data);
        ispf.apply (data);
        ++roundTrips;
      }

      assertEquals (List.of (veryLongLine), ispf.lines);
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested class ProgressGuard
  // ---------------------------------------------------------------------------------//
  {
    @Test void abortsAfterSixScreensWithoutProgress () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext (numberedLines (50));
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.INSERTING_CMD);
      plugin.setDoesAuto (true);

      // Uma tela que nunca oferece linha de insercao nenhuma
      for (int i = 0; i < 6; i++)
        plugin.processAuto (editScreen ("EDIT  USER1.SRC(TEST)"));

      assertEquals (UploadDataset.UploadState.IDLE, plugin.getState ());
      assertFalse (plugin.doesAuto ());
      assertEquals (1, alerts.size ());
      assertTrue (alerts.get (0).contains ("parou de progredir"), alerts.get (0));
    }

    @Test void progressResetsTheCounter () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext (numberedLines (10));
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.INSERTING_CMD);
      plugin.setDoesAuto (true);

      // Quatro telas estereis: o contador sobe mas ainda nao estoura
      for (int i = 0; i < 4; i++)
        plugin.processAuto (editScreen ("EDIT  USER1.SRC(TEST)"));
      assertTrue (plugin.doesAuto ());

      // Uma tela que rende linhas
      plugin.setState (UploadDataset.UploadState.INSERTING_CMD);
      plugin.processAuto (editScreenWithEmptyLines (3));
      assertEquals (3, ctx.getLinesSent ());

      // O contador zerou: outras quatro telas estereis nao derrubam o upload
      for (int i = 0; i < 4; i++)
        plugin.processAuto (editScreen ("EDIT  USER1.SRC(TEST)"));

      assertTrue (plugin.doesAuto ());
      assertTrue (alerts.isEmpty (), "alertas inesperados: " + alerts);
    }

    @Test void abortWarnsThatReplaceAlreadyEmptiedTheDataset () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("A");
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.FILLING_LINES);
      plugin.setDoesAuto (true);

      plugin.processAuto (buildScreen ("ISPF Primary Option Menu"));

      assertEquals (1, alerts.size ());
      assertTrue (alerts.get (0).contains ("DELETE ALL"), alerts.get (0));
      assertTrue (alerts.get (0).contains ("CANCEL"), alerts.get (0));
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested class HostConfirmation
  // ---------------------------------------------------------------------------------//
  {
    @Test void ignoresTheColumnsIndicator ()
    {
      UploadDataset plugin = createPlugin ();

      assertEquals ("", plugin.findShortMessage (editScreen ("EDIT  X.Y(Z)")));
    }

    @Test void readsTheIspfShortMessage ()
    {
      UploadDataset plugin = createPlugin ();
      PluginData data = ScreenBuilder
          .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072")
          .shortMessage ("Member TEST saved")
          .build ();

      assertEquals ("Member TEST saved", plugin.findShortMessage (data));
    }

    @Test void saveErrorAbortsInsteadOfReportingSuccess () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("A");
      ctx.prepare ();
      ctx.getNextBlock (1);
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.SAVING);
      plugin.setDoesAuto (true);

      plugin.processAuto (ScreenBuilder
          .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072")
          .shortMessage ("Save error")
          .build ());

      assertEquals (UploadDataset.UploadState.IDLE, plugin.getState ());
      assertFalse (plugin.doesAuto ());
      assertEquals (1, alerts.size ());
      assertTrue (alerts.get (0).startsWith ("ERROR"), alerts.get (0));
      assertTrue (alerts.get (0).contains ("recusou o SAVE"), alerts.get (0));
    }

    @Test void memberSavedCompletesTheUpload () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("A", "B");
      ctx.prepare ();
      ctx.getNextBlock (2);
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.SAVING);
      plugin.setDoesAuto (true);

      plugin.processAuto (ScreenBuilder
          .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072")
          .shortMessage ("Member TEST saved")
          .build ());

      assertEquals (UploadDataset.UploadState.DONE, plugin.getState ());
      assertEquals (1, alerts.size ());
      assertTrue (alerts.get (0).startsWith ("INFORMATION"), alerts.get (0));
      assertTrue (alerts.get (0).contains ("2 linhas"), alerts.get (0));
    }

    @Test void deleteErrorAborts () throws IOException
    {
      UploadDataset plugin = createPlugin ();
      UploadContext ctx = createContext ("A");
      ctx.prepare ();
      plugin.setContext (ctx);
      plugin.setState (UploadDataset.UploadState.DELETING);
      plugin.setDoesAuto (true);

      plugin.processAuto (ScreenBuilder
          .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072")
          .shortMessage ("Invalid command")
          .build ());

      assertEquals (UploadDataset.UploadState.IDLE, plugin.getState ());
      assertTrue (alerts.get (0).contains ("recusou o DELETE ALL"), alerts.get (0));
    }

    @Test void classifiesMessages ()
    {
      assertTrue (UploadDataset.isErrorMessage ("Save error"));
      assertTrue (UploadDataset.isErrorMessage ("Invalid command"));
      assertFalse (UploadDataset.isErrorMessage ("Member TEST saved"));
      assertFalse (UploadDataset.isErrorMessage (""));
    }
  }

  // ---------------------------------------------------------------
  // Helper methods
  // ---------------------------------------------------------------

  // ---------------------------------------------------------------------------------//
  private UploadDataset createPlugin ()
  // ---------------------------------------------------------------------------------//
  {
    alerts.clear ();
    UploadDataset plugin = new UploadDataset ();
    plugin.setAlertHandler ((type, msg) -> alerts.add (type + ": " + msg));
    plugin.activate ();
    return plugin;
  }

  // ---------------------------------------------------------------------------------//
  private UploadContext createContext (String... lines) throws IOException
  // ---------------------------------------------------------------------------------//
  {
    return createContext (80, lines);
  }

  // ---------------------------------------------------------------------------------//
  private UploadContext createContext (int lrecl, String... lines) throws IOException
  // ---------------------------------------------------------------------------------//
  {
    Path file = tempDir.resolve ("upload_test.txt");
    Files.write (file, List.of (lines), StandardCharsets.UTF_8);
    return new UploadContext (file.toFile (), lrecl, StandardCharsets.UTF_8,
        true, false, UploadContext.UploadMode.REPLACE);
  }

  // ---------------------------------------------------------------------------------//
  private static String[] numberedLines (int count)
  // ---------------------------------------------------------------------------------//
  {
    String[] lines = new String[count];
    for (int i = 0; i < count; i++)
      lines[i] = String.format ("LINHA %03d", i + 1);
    return lines;
  }

  /** O que o plugin digitou nos campos de conteudo, na ordem em que digitou. */
  // ---------------------------------------------------------------------------------//
  private static List<String> writtenLines (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    List<String> written = new ArrayList<> ();
    for (PluginField field : data.changedFields)
      if (field.location.column > 6 && field.location.column < 15)
        written.add (field.newData);
    return written;
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
      sb.insertLine (3 + i);

    return sb.build ();
  }

  /** Tela cheia de dados com a unica linha de continuacao no rodape. */
  // ---------------------------------------------------------------------------------//
  private static PluginData fullScreenWithContinuationLine ()
  // ---------------------------------------------------------------------------------//
  {
    ScreenBuilder sb = ScreenBuilder
        .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072");

    for (int row = 3; row <= 21; row++)
      sb.dataLine (row, String.format ("%06d", (row - 2) * 100), "JA GRAVADA");

    return sb.insertLine (22).build ();
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

  // ---------------------------------------------------------------
  // Modelo de ISPF EDIT
  // ---------------------------------------------------------------

  /**
   * Modelo minimo do ISPF EDIT, so com as regras que decidem o ritmo do upload:
   *
   * <ul>
   *   <li>{@code I<n>} num prefixo cria n linhas de insercao abaixo da ancora,
   *       limitadas ao que ainda cabe na tela;</li>
   *   <li>o ENTER sobre linhas de insercao preenchidas converte-as em linhas
   *       numeradas e emenda UMA linha de continuacao — a regra que fazia o
   *       upload degenerar em uma linha por ida-e-volta;</li>
   *   <li>{@code LOCATE n} traz a linha n para o topo do display.</li>
   * </ul>
   *
   * E um modelo, nao o host: serve para medir quantas idas-e-voltas a maquina de
   * estados gasta, nao para provar como o ISPF real se comporta.
   */
  // ---------------------------------------------------------------------------------//
  private static final class IspfModel
  // ---------------------------------------------------------------------------------//
  {
    private static final int FIRST_ROW = 3;
    private static final int LAST_ROW = 22;
    private static final int CAPACITY = LAST_ROW - FIRST_ROW + 1;

    private final List<String> lines = new ArrayList<> ();
    private final Map<Integer, Integer> rowToIndex = new HashMap<> ();

    private int top;              // indice da primeira linha de dados exibida
    private int anchor = -1;      // as insercoes pendentes vem depois deste indice
    private int pending;          // quantas linhas de insercao estao na tela
    private boolean saved;
    private int horizontalOffset; // offset de rolagem horizontal
    private int lrecl = 80;       // tamanho logico do registro

    public void setLrecl(int lrecl) { this.lrecl = lrecl; }

    private static String number (int index)
    {
      return String.format ("%06d", (index + 1) * 100);
    }

    PluginData screen ()
    {
      rowToIndex.clear ();

      ScreenBuilder sb = ScreenBuilder
          .ispfEditScreen ("EDIT  USER1.SRC(TEST)", "Columns 00001 00072");

      int row = FIRST_ROW;

      if (top == 0)
      {
        sb.dataLine (2, "******", "Top of Data");
        rowToIndex.put (2, -1);
      }

      if (anchor < top)
        row = emitInserts (sb, row);

      for (int i = top; i < lines.size () && row <= LAST_ROW; i++)
      {
        rowToIndex.put (row, i);
        String visibleText = lines.get (i);
        if (visibleText.length () > horizontalOffset) {
            visibleText = visibleText.substring (horizontalOffset);
        } else {
            visibleText = "";
        }
        if (visibleText.length () > 72) {
            visibleText = visibleText.substring (0, 72);
        }
        sb.dataLine (row++, number (i), visibleText);

        if (i == anchor)
          row = emitInserts (sb, row);
      }

      if (row <= LAST_ROW)
        sb.dataLine (row, "******", "Bottom of Data");

      return sb.build ();
    }

    private int emitInserts (ScreenBuilder sb, int row)
    {
      for (int i = 0; i < pending && row <= LAST_ROW; i++)
        sb.insertLine (row++);
      return row;
    }

    void apply (PluginData data)
    {
      String command = "";
      String prefixCommand = "";
      int prefixIndex = -1;
      int prefixRow = -1;
      Map<Integer, String> typedMap = new HashMap<> ();

      for (PluginField field : data.changedFields)
      {
        String value = field.newData == null ? "" : field.newData;

        if (field.location.row == 1 && field.location.column == 20)
          command = value.trim ();
        else if (field.location.column == 1)
        {
          prefixCommand = value.trim ();
          prefixRow = field.location.row;
          prefixIndex = rowToIndex.getOrDefault (prefixRow, -1);
        }
        else if (field.location.column == 8) {
          typedMap.put (field.location.row, value);
        }
      }

      if (!typedMap.isEmpty ())
      {
        if (horizontalOffset == 0 && pending > 0)
        {
          List<Integer> rows = new ArrayList<> (typedMap.keySet ());
          rows.sort (null);
          int at = anchor + 1;
          for (Integer r : rows) {
            lines.add (at++, typedMap.get (r));
          }

          anchor = at - 1;
          pending = 1;                                    // linha de continuacao
          top = Math.max (0, anchor - CAPACITY + 2);
        }
        else
        {
          // Updating existing lines (scrolled)
          for (Map.Entry<Integer, String> entry : typedMap.entrySet ())
          {
            int r = entry.getKey ();
            int idx = rowToIndex.getOrDefault (r, -1);
            if (idx >= 0 && idx < lines.size ())
            {
              String existing = lines.get (idx);
              String append = entry.getValue ();
              if (existing.length () < horizontalOffset)
                  existing = existing + " ".repeat (horizontalOffset - existing.length ());
              String updated = existing.substring (0, horizontalOffset) + append;
              if (existing.length () > horizontalOffset + append.length ())
                  updated += existing.substring (horizontalOffset + append.length ());
              lines.set (idx, updated);
            }
          }
        }
      }

      if (command.equals ("SAVE"))
        saved = true;
      else if (command.startsWith ("LOCATE "))
      {
        String target = command.substring (7).trim ();
        for (int i = 0; i < lines.size (); i++)
          if (number (i).equals (target))
            top = i;
        pending = 0;
      }
      else if (command.startsWith ("RIGHT "))
      {
        int shift = Integer.parseInt (command.substring (6).trim ());
        horizontalOffset = Math.min(horizontalOffset + shift, Math.max(0, lrecl - 72));
      }
      else if (command.equals ("LEFT MAX"))
      {
        horizontalOffset = 0;
      }

      if (prefixCommand.matches ("I\\d+"))
      {
        anchor = prefixIndex;
        int free = LAST_ROW - prefixRow;
        pending = Math.min (Integer.parseInt (prefixCommand.substring (1)), free);
      }
    }
  }
}
