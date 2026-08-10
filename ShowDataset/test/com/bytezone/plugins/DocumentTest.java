package com.bytezone.plugins;

import static com.bytezone.plugins.ScreenBuilder.ispfEditScreen;
import static com.bytezone.plugins.ScreenBuilder.modifiableFields;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.bytezone.dm3270.plugins.PluginData;

/*
 * Copia dos testes do modulo DownloadDataset. Document.java e DocumentPage.java sao
 * identicos byte a byte nos dois modulos, entao a suite tambem precisa ser duplicada
 * para que o ShowDataset tenha cobertura propria. Ao extrair as duas classes para um
 * modulo comum, este arquivo deve ser apagado junto.
 */
// -----------------------------------------------------------------------------------//
@DisplayName ("Document - montagem do dataset a partir das paginas capturadas")
class DocumentTest
// -----------------------------------------------------------------------------------//
{
  // ---------------------------------------------------------------------------------//
  private static DocumentPage page (ScreenBuilder builder)
  // ---------------------------------------------------------------------------------//
  {
    PluginData data = builder.build ();
    return DocumentPage.createPage (data, modifiableFields (data));
  }

  // ---------------------------------------------------------------------------------//
  private static DocumentPage leftPage ()
  // ---------------------------------------------------------------------------------//
  {
    return page (ispfEditScreen ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072")
        .dataLine (3, "000100", "//JOB1 JOB (ACCT)")
        .dataLine (4, "000200", "//STEP1 EXEC PGM=X"));
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("colecao de paginas")
  class Pages
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("herda dataset e membro da primeira pagina")
    void inheritsNamesFromFirstPage ()
    {
      Document document = new Document (leftPage ());

      assertEquals ("MYUSER.TEST.CNTL", document.datasetName);
      assertEquals ("JOB1", document.memberName);
    }

    @Test
    @DisplayName ("uma pagina nova e acrescentada")
    void addsNewPage ()
    {
      Document document = new Document (leftPage ());

      document.addDocumentPage (
          page (ispfEditScreen ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072")
              .dataLine (3, "000300", "//SYSPRINT DD SYSOUT=*")));

      assertEquals (2, document.pages.size ());
    }

    @Test
    @DisplayName ("uma pagina ja conhecida substitui a anterior em vez de duplicar")
    void replacesMatchingPage ()
    {
      Document document = new Document (leftPage ());

      document.addDocumentPage (leftPage ());

      assertEquals (1, document.pages.size ());
    }

    @Test
    @DisplayName ("recusa uma pagina de outro dataset")
    void rejectsForeignPage ()
    {
      Document document = new Document (leftPage ());

      DocumentPage otherDataset =
          page (ispfEditScreen ("EDIT       OTHER.TEST.CNTL(JOB9)", "Columns 00001 00072")
              .dataLine (3, "000100", "//JOB9 JOB"));

      assertThrows (AssertionError.class,
          () -> document.addDocumentPage (otherDataset));
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("stitch - montagem das linhas")
  class Stitching
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("uma pagina da coluna 1 vira 'numero conteudo'")
    void buildsNumberedLines ()
    {
      Document document = new Document (leftPage ());

      List<Document.Line> lines = document.getLines ();

      assertEquals (2, lines.size ());
      assertEquals ("000100 //JOB1 JOB (ACCT)", lines.get (0).toString ());
      assertEquals ("000200 //STEP1 EXEC PGM=X", lines.get (1).toString ());
    }

    @Test
    @DisplayName ("guarda a maior coluna vista entre as paginas")
    void tracksMaxColumns ()
    {
      Document document = new Document (leftPage ());
      document.getLines ();

      assertEquals (72, document.maxColumns);
    }

    @Test
    @DisplayName ("uma pagina rolada para a direita e concatenada a direita da anterior")
    void appendsShiftedPage ()
    {
      Document document = new Document (leftPage ());

      document.addDocumentPage (
          page (ispfEditScreen ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00073 00144")
              .dataLine (3, "000100", "CONTINUACAO1")
              .dataLine (4, "000200", "CONTINUACAO2")));

      List<Document.Line> lines = document.getLines ();

      assertEquals (2, lines.size ());
      assertTrue (lines.get (0).toString ().startsWith ("000100 //JOB1 JOB (ACCT)"),
          lines.get (0).toString ());
      assertTrue (lines.get (0).toString ().endsWith ("CONTINUACAO1"),
          lines.get (0).toString ());
      assertEquals (144, document.maxColumns);
    }

    @Test
    @DisplayName ("a continuacao e alinhada na coluna esquerda da segunda pagina")
    void alignsContinuationAtLeftColumn ()
    {
      Document document = new Document (leftPage ());

      document.addDocumentPage (
          page (ispfEditScreen ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00073 00144")
              .dataLine (3, "000100", "X")
              .dataLine (4, "000200", "Y")));

      String line = document.getLines ().get (0).toString ();

      // 73 (coluna esquerda) + 6 (largura do numero de linha) = 79 caracteres de prefixo
      assertEquals (79 + 1, line.length ());
      assertEquals ('X', line.charAt (79));
    }

    @Test
    @DisplayName ("acrescentar uma pagina invalida o cache de linhas ja montadas")
    void addingAPageInvalidatesTheCache ()
    {
      Document document = new Document (leftPage ());

      assertEquals (2, document.getLines ().size ());

      document.addDocumentPage (
          page (ispfEditScreen ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072")
              .dataLine (3, "000300", "//SYSPRINT DD SYSOUT=*")));

      assertEquals (3, document.getLines ().size (),
          "as linhas precisam ser remontadas a partir de todas as paginas");
    }

    @Test
    @DisplayName ("chamar getLines duas vezes nao duplica as linhas")
    void getLinesIsStable ()
    {
      Document document = new Document (leftPage ());

      assertEquals (document.getLines ().size (), document.getLines ().size ());
      assertEquals (2, document.getLines ().size ());
    }
  }
}
