package com.bytezone.plugins;

import static com.bytezone.plugins.ScreenBuilder.ispfEditScreen;
import static com.bytezone.plugins.ScreenBuilder.modifiableFields;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.bytezone.dm3270.plugins.PluginData;

// -----------------------------------------------------------------------------------//
@DisplayName ("DocumentPage - leitura de uma pagina de EDIT do ISPF")
class DocumentPageTest
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
  private static ScreenBuilder threeLinePage (String title, String columns)
  // ---------------------------------------------------------------------------------//
  {
    return ispfEditScreen (title, columns)                             //
        .dataLine (3, "000100", "//JOB1 JOB (ACCT),CLASS=A")           //
        .dataLine (4, "000200", "//STEP1 EXEC PGM=IEFBR14")            //
        .dataLine (5, "000300", "//SYSPRINT DD SYSOUT=*");
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("reconhecimento da tela")
  class Recognition
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("aceita uma tela de EDIT completa")
    void acceptsCompleteScreen ()
    {
      assertNotNull (page (threeLinePage ("EDIT       MYUSER.TEST.CNTL(JOB1) - 01.05",
          "Columns 00001 00072")));
    }

    @ParameterizedTest (name = "titulo \"{0}\"")
    @ValueSource (strings = { "EDIT       MYUSER.TEST.CNTL(JOB1) - 01.05",
                              "RFEEDIT    MYUSER.TEST.CNTL(JOB1)",
                              "Edit       MYUSER.TEST.CNTL(JOB1)" })
    @DisplayName ("aceita qualquer titulo que contenha a palavra EDIT")
    void acceptsEditVariants (String title)
    {
      assertNotNull (page (threeLinePage (title, "Columns 00001 00072")));
    }

    @ParameterizedTest (name = "titulo \"{0}\"")
    @ValueSource (strings = { "BROWSE     MYUSER.TEST.CNTL(JOB1)",
                              "VIEW       MYUSER.TEST.CNTL(JOB1)" })
    @DisplayName ("LIMITACAO: telas de BROWSE e VIEW sao recusadas")
    void rejectsBrowseAndView (String title)
    {
      // getDatasetName() sabe extrair o nome de telas BROWSE e VIEW, mas createPage()
      // so aceita a tela se algum campo casar com EDIT_PATTERN ("(?i).*EDIT\\b.*").
      // Resultado: essas telas nunca chegam ao construtor. Se a intencao for suportar
      // os tres modos, o padrao precisa incluir BROWSE e VIEW.
      assertNull (page (threeLinePage (title, "Columns 00001 00072")));
    }

    @Test
    @DisplayName ("recusa uma tela sem o indicador de editor")
    void rejectsScreenWithoutEditor ()
    {
      assertNull (page (threeLinePage ("ISPF Primary Option Menu",
          "Columns 00001 00072")));
    }

    @Test
    @DisplayName ("recusa uma tela sem linha de comando")
    void rejectsScreenWithoutCommandLine ()
    {
      ScreenBuilder builder = new ScreenBuilder ()                     //
          .protectedField (0, 0, "EDIT       MYUSER.TEST.CNTL(JOB1)")  //
          .protectedField (0, 60, "Columns 00001 00072")               //
          .protectedField (2, 0, "Scroll ===>")                        //
          .inputField (2, 20, 8, "PAGE");

      assertNull (page (builder));
    }

    @Test
    @DisplayName ("recusa uma tela sem linha de scroll")
    void rejectsScreenWithoutScrollLine ()
    {
      ScreenBuilder builder = new ScreenBuilder ()                     //
          .protectedField (0, 0, "EDIT       MYUSER.TEST.CNTL(JOB1)")  //
          .protectedField (0, 60, "Columns 00001 00072")               //
          .protectedField (1, 0, "Command ===>")                       //
          .inputField (1, 20, 40, "");

      assertNull (page (builder));
    }

    @Test
    @DisplayName ("recusa quando o rotulo de comando vem antes do indicador de editor")
    void rejectsCommandBeforeEditor ()
    {
      ScreenBuilder builder = new ScreenBuilder ()                     //
          .protectedField (0, 0, "Command ===>")                       //
          .inputField (0, 20, 40, "")                                  //
          .protectedField (1, 0, "EDIT       MYUSER.TEST.CNTL(JOB1)")  //
          .protectedField (2, 0, "Scroll ===>")                        //
          .inputField (2, 20, 8, "PAGE");

      assertNull (page (builder));
    }

    @Test
    @DisplayName ("recusa quando o campo apos o rotulo de comando nao e editavel")
    void rejectsNonModifiableCommandInput ()
    {
      ScreenBuilder builder = new ScreenBuilder ()                     //
          .protectedField (0, 0, "EDIT       MYUSER.TEST.CNTL(JOB1)")  //
          .protectedField (0, 60, "Columns 00001 00072")               //
          .protectedField (1, 0, "Command ===>")                       //
          .protectedField (1, 20, "xxxx")                              //
          .protectedField (2, 0, "Scroll ===>")                        //
          .inputField (2, 20, 8, "PAGE");

      assertNull (page (builder));
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("nome do dataset")
  class DatasetName
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("separa dataset e membro")
    void splitsDatasetAndMember ()
    {
      DocumentPage documentPage = page (threeLinePage (
          "EDIT       MYUSER.TEST.CNTL(JOB1) - 01.05", "Columns 00001 00072"));

      assertEquals ("MYUSER.TEST.CNTL", documentPage.datasetName);
      assertEquals ("JOB1", documentPage.memberName);
      assertEquals ("MYUSER.TEST.CNTL(JOB1)", documentPage.fullName);
    }

    @Test
    @DisplayName ("dataset sequencial nao tem membro")
    void sequentialDatasetHasNoMember ()
    {
      DocumentPage documentPage = page (
          threeLinePage ("EDIT       MYUSER.TEST.DATA", "Columns 00001 00072"));

      assertEquals ("MYUSER.TEST.DATA", documentPage.datasetName);
      assertEquals ("", documentPage.memberName);
      assertEquals ("MYUSER.TEST.DATA", documentPage.fullName);
    }

    @Test
    @DisplayName ("titulo sem nome reconhecivel vira UNKNOWN em vez de estourar")
    void unrecognisedTitleBecomesUnknown ()
    {
      DocumentPage documentPage =
          page (threeLinePage ("EDIT sem nome de dataset", "Columns 00001 00072"));

      assertNotNull (documentPage);
      assertEquals ("UNKNOWN", documentPage.datasetName);
      assertEquals ("UNKNOWN", documentPage.fullName);
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("faixa de colunas")
  class Columns
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("le a faixa declarada na tela")
    void readsDeclaredRange ()
    {
      DocumentPage documentPage = page (threeLinePage (
          "EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072"));

      assertEquals (1, documentPage.leftColumn);
      assertEquals (72, documentPage.rightColumn);
    }

    @Test
    @DisplayName ("le uma faixa deslocada (pagina rolada para a direita)")
    void readsShiftedRange ()
    {
      DocumentPage documentPage = page (threeLinePage (
          "EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00073 00144"));

      assertEquals (73, documentPage.leftColumn);
      assertEquals (144, documentPage.rightColumn);
    }

    @Test
    @DisplayName ("LIMITACAO: a abreviacao 'Col' nao e reconhecida")
    void colAbbreviationIsNotRecognised ()
    {
      // getColumns() localiza o campo com findFieldContaining("columns"): um cabecalho
      // abreviado como "Col 00010 00050" nunca e encontrado. O ramo que compara
      // parts[i].equals("col") mais adiante e, na pratica, inalcancavel.
      DocumentPage documentPage = page (
          threeLinePage ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Col 00010 00050"));

      assertNotNull (documentPage);
      assertEquals (0, documentPage.leftColumn);
      assertEquals (0, documentPage.rightColumn);
    }

    @Test
    @DisplayName ("sem cabecalho de colunas, a faixa fica zerada")
    void missingColumnsHeaderLeavesZero ()
    {
      ScreenBuilder builder = new ScreenBuilder ()                     //
          .protectedField (0, 0, "EDIT       MYUSER.TEST.CNTL(JOB1)")  //
          .protectedField (1, 0, "Command ===>")                       //
          .inputField (1, 20, 40, "")                                  //
          .protectedField (2, 0, "Scroll ===>")                        //
          .inputField (2, 20, 8, "PAGE")                               //
          .dataLine (3, "000100", "//JOB1 JOB");

      DocumentPage documentPage = page (builder);

      assertEquals (0, documentPage.leftColumn);
      assertEquals (0, documentPage.rightColumn);
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("linhas de dados")
  class DataLines
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("pareia numero e conteudo de cada linha")
    void pairsNumbersAndContent ()
    {
      DocumentPage documentPage = page (threeLinePage (
          "EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072"));

      assertEquals (3, documentPage.numbers.size ());
      assertEquals (3, documentPage.lines.size ());
      assertEquals ("000100", documentPage.numbers.get (0));
      assertEquals ("//JOB1 JOB (ACCT),CLASS=A", documentPage.lines.get (0));
      assertEquals ("//SYSPRINT DD SYSOUT=*", documentPage.lines.get (2));
    }

    @Test
    @DisplayName ("guarda o primeiro e o ultimo numero de linha")
    void recordsFirstAndLastLineNumbers ()
    {
      DocumentPage documentPage = page (threeLinePage (
          "EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072"));

      assertEquals (100, documentPage.firstLine);
      assertEquals (300, documentPage.lastLine);
    }

    @Test
    @DisplayName ("pagina sem linhas de dados fica com firstLine = -1")
    void emptyPageHasNoFirstLine ()
    {
      DocumentPage documentPage = page (
          ispfEditScreen ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072"));

      assertNotNull (documentPage);
      assertTrue (documentPage.lines.isEmpty ());
      assertEquals (-1, documentPage.firstLine);
    }

    @Test
    @DisplayName ("numeros nao numericos zeram os limites em vez de estourar")
    void nonNumericLineNumbersAreTolerated ()
    {
      ScreenBuilder builder =
          ispfEditScreen ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072")
              .dataLine (3, "======", "//JOB1 JOB");

      DocumentPage documentPage = page (builder);

      assertNotNull (documentPage);
      assertEquals (-1, documentPage.firstLine);
      assertEquals (-1, documentPage.lastLine);
    }

    @Test
    @DisplayName ("quando as contagens divergem, o pareamento cai para a linha da tela")
    void pairsByRowWhenCountsDiffer ()
    {
      // duas linhas com numero, mas apenas uma com conteudo (a outra esta em branco
      // e o mainframe nao envia o campo)
      ScreenBuilder builder =
          ispfEditScreen ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072")
              .inputField (3, 1, 6, "000100")                //
              .inputField (3, 8, 72, "//JOB1 JOB")           //
              .inputField (4, 1, 6, "000200");               // sem conteudo

      DocumentPage documentPage = page (builder);

      assertEquals (1, documentPage.numbers.size ());
      assertEquals (1, documentPage.lines.size ());
      assertEquals ("000100", documentPage.numbers.get (0));
      assertEquals ("//JOB1 JOB", documentPage.lines.get (0));
    }

    @Test
    @DisplayName ("campos fora da janela de colunas 7-14 sao ignorados")
    void ignoresFieldsOutsideContentWindow ()
    {
      ScreenBuilder builder =
          ispfEditScreen ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072")
              .dataLine (3, "000100", "//JOB1 JOB")          //
              .inputField (4, 40, 20, "campo qualquer");     // coluna 40: ignorado

      DocumentPage documentPage = page (builder);

      assertEquals (1, documentPage.lines.size ());
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("marcadores de inicio e fim")
  class Markers
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("detecta Top of Data mesmo quando o campo e protegido")
    void detectsTopOfData ()
    {
      ScreenBuilder builder =
          ispfEditScreen ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072")
              .protectedField (3, 8, "***************** Top of Data ******************")
              .dataLine (4, "000100", "//JOB1 JOB");

      DocumentPage documentPage = page (builder);

      assertTrue (documentPage.hasBeginning);
      assertFalse (documentPage.hasEnd);
    }

    @Test
    @DisplayName ("detecta Bottom of Data")
    void detectsBottomOfData ()
    {
      ScreenBuilder builder =
          ispfEditScreen ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072")
              .dataLine (3, "000100", "//JOB1 JOB")
              .protectedField (4, 8, "*************** Bottom of Data *****************");

      DocumentPage documentPage = page (builder);

      assertTrue (documentPage.hasEnd);
      assertFalse (documentPage.hasBeginning);
    }

    @Test
    @DisplayName ("uma pagina inteira detecta inicio e fim")
    void detectsBothMarkers ()
    {
      ScreenBuilder builder =
          ispfEditScreen ("EDIT       MYUSER.TEST.CNTL(JOB1)", "Columns 00001 00072")
              .protectedField (3, 8, "***************** Top of Data ******************")
              .dataLine (4, "000100", "//JOB1 JOB")
              .protectedField (5, 8, "*************** Bottom of Data *****************");

      DocumentPage documentPage = page (builder);

      assertTrue (documentPage.hasBeginning);
      assertTrue (documentPage.hasEnd);
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("matches - a pagina ja foi vista?")
  class Matches
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("mesma faixa de colunas e mesma primeira linha")
    void samePage ()
    {
      DocumentPage first = page (threeLinePage ("EDIT       A.B(C)", "Columns 00001 00072"));
      DocumentPage second = page (threeLinePage ("EDIT       A.B(C)", "Columns 00001 00072"));

      assertTrue (first.matches (second));
    }

    @Test
    @DisplayName ("primeira linha diferente = pagina diferente")
    void differentFirstLine ()
    {
      DocumentPage first = page (threeLinePage ("EDIT       A.B(C)", "Columns 00001 00072"));
      DocumentPage second = page (
          ispfEditScreen ("EDIT       A.B(C)", "Columns 00001 00072")
              .dataLine (3, "000400", "//STEP2 EXEC"));

      assertFalse (first.matches (second));
    }

    @Test
    @DisplayName ("faixa de colunas diferente = pagina diferente")
    void differentColumns ()
    {
      DocumentPage first = page (threeLinePage ("EDIT       A.B(C)", "Columns 00001 00072"));
      DocumentPage second = page (threeLinePage ("EDIT       A.B(C)", "Columns 00073 00144"));

      assertFalse (first.matches (second));
    }

    @Test
    @DisplayName ("duas paginas vazias casam - e assim que o plugin detecta que travou")
    void twoEmptyPagesMatch ()
    {
      DocumentPage first =
          page (ispfEditScreen ("EDIT       A.B(C)", "Columns 00001 00072"));
      DocumentPage second =
          page (ispfEditScreen ("EDIT       A.B(C)", "Columns 00001 00072"));

      assertTrue (first.matches (second));
    }

    @Test
    @DisplayName ("uma pagina vazia nunca casa com uma pagina com dados")
    void emptyDoesNotMatchFilled ()
    {
      DocumentPage empty =
          page (ispfEditScreen ("EDIT       A.B(C)", "Columns 00001 00072"));
      DocumentPage filled =
          page (threeLinePage ("EDIT       A.B(C)", "Columns 00001 00072"));

      assertFalse (empty.matches (filled));
      assertFalse (filled.matches (empty));
    }

    @Test
    @DisplayName ("matches(null) devolve false")
    void nullDoesNotMatch ()
    {
      assertFalse (page (threeLinePage ("EDIT       A.B(C)", "Columns 00001 00072"))
          .matches (null));
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("compareTo - ordem de montagem do documento")
  class Ordering
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("ordena primeiro pela coluna, depois pela linha")
    void ordersByColumnThenLine ()
    {
      DocumentPage left =
          page (threeLinePage ("EDIT       A.B(C)", "Columns 00001 00072"));
      DocumentPage right =
          page (threeLinePage ("EDIT       A.B(C)", "Columns 00073 00144"));

      assertTrue (left.compareTo (right) < 0);
      assertTrue (right.compareTo (left) > 0);

      DocumentPage later = page (ispfEditScreen ("EDIT       A.B(C)", "Columns 00001 00072")
          .dataLine (3, "000400", "//STEP2 EXEC"));

      assertTrue (left.compareTo (later) < 0);
      assertEquals (0, left.compareTo (left));
    }
  }
}
