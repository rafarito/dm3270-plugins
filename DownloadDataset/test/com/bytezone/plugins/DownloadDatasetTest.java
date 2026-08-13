package com.bytezone.plugins;

import static com.bytezone.plugins.ScreenBuilder.ispfEditScreen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bytezone.dm3270.commands.AIDCommand;
import com.bytezone.dm3270.plugins.PluginData;

// -----------------------------------------------------------------------------------//
@DisplayName ("DownloadDataset - navegacao automatica pelas paginas do EDIT")
class DownloadDatasetTest
// -----------------------------------------------------------------------------------//
{
  private static final String TITLE = "EDIT       MYUSER.TEST.CNTL(JOB1)";

  private DownloadDataset plugin;

  @BeforeEach
  void setUp ()
  {
    plugin = new DownloadDataset ();
    plugin.activate ();
  }

  // ---------------------------------------------------------------------------------//
  //  Construcao das telas
  // ---------------------------------------------------------------------------------//

  // Uma pagina de EDIT com tres linhas de dados, na faixa de colunas indicada.
  private static ScreenBuilder page (String columns, int firstLine)
  {
    return ispfEditScreen (TITLE, columns)                                          //
        .dataLine (3, String.format ("%06d", firstLine), "//JOB1 JOB (ACCT)")       //
        .dataLine (4, String.format ("%06d", firstLine + 100), "//STEP1 EXEC")      //
        .dataLine (5, String.format ("%06d", firstLine + 200), "//SYSPRINT DD");
    }

  // A mesma pagina com o marcador de fim de dados.
  private static ScreenBuilder pageWithEnd (String columns, int firstLine)
  {
    return page (columns, firstLine).protectedField (6, 8, "****** Bottom of Data ******");
  }

  // Uma pagina sem nenhuma linha de dados.
  private static ScreenBuilder emptyPage (String columns)
  {
    return ispfEditScreen (TITLE, columns);
  }

  // setMax () escreve no campo seguinte ao rotulo "Scroll ===>". change () guarda o
  // texto em newData e registra o campo em changedFields; fieldValue nao muda.
  private static String maxValue (PluginData data)
  {
    return data.changedFields.isEmpty () ? null : data.changedFields.get (0).newData;
  }

  private static int linesOf (File file)
  {
    try
    {
      return Files.readAllLines (file.toPath ()).size ();
    }
    catch (IOException e)
    {
      throw new AssertionError ("nao deu para ler o arquivo gravado", e);
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("ciclo de vida")
  class Lifecycle
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("um plugin recem-criado nao responde a nada")
    void startsInert ()
    {
      DownloadDataset fresh = new DownloadDataset ();

      assertFalse (fresh.doesRequest ());
      assertFalse (fresh.doesAuto ());
    }

    @Test
    @DisplayName ("activate liga apenas o modo pedido pelo usuario")
    void activateEnablesRequest ()
    {
      assertTrue (plugin.doesRequest ());
      assertFalse (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("deactivate desliga os dois modos")
    void deactivateDisablesBoth ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 100).build ());
      assertTrue (plugin.doesAuto ());

      plugin.deactivate ();

      assertFalse (plugin.doesAuto ());
      assertFalse (plugin.doesRequest ());
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("processRequest - primeira tela capturada")
  class FirstScreen
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("uma tela que nao e do EDIT e ignorada")
    void ignoresOtherScreens ()
    {
      PluginData data = new ScreenBuilder ()
          .protectedField (0, 0, "ISPF Primary Option Menu").build ();

      plugin.processRequest (data);

      assertFalse (plugin.doesAuto (), "nao deveria iniciar a captura");
      assertEquals (0, data.key);
    }

    @Test
    @DisplayName ("uma pagina que nao comeca na linha 1 sobe ate o topo (PF7)")
    void scrollsUpToTheTop ()
    {
      PluginData data = page ("Columns 00001 00072", 500).build ();

      plugin.processRequest (data);

      assertEquals (AIDCommand.AID_PF7, data.key);
      assertEquals ("m", maxValue (data), "o scroll deveria ir para o maximo");
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("uma pagina rolada para a direita volta para a esquerda (PF10)")
    void scrollsLeftToColumnOne ()
    {
      PluginData data = page ("Columns 00073 00144", 1).build ();

      plugin.processRequest (data);

      assertEquals (AIDCommand.AID_PF10, data.key);
      assertEquals ("m", maxValue (data));
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("no canto superior esquerdo comeca a descer (PF8)")
    void startsScrollingDown ()
    {
      PluginData data = page ("Columns 00001 00072", 1).build ();

      plugin.processRequest (data);

      assertEquals (AIDCommand.AID_PF8, data.key);
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("uma pagina unica com fim de dados vai ao canto inferior direito (PF11)")
    void jumpsToBottomRightWhenDocumentFits ()
    {
      PluginData data = pageWithEnd ("Columns 00001 00072", 1).build ();

      plugin.processRequest (data);

      assertEquals (AIDCommand.AID_PF11, data.key);
      assertEquals ("m", maxValue (data));
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("um novo pedido descarta o estado do anterior")
    void resetsBetweenRequests ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());

      // um segundo pedido no meio do documento tem de recomecar subindo
      PluginData second = page ("Columns 00001 00072", 900).build ();
      plugin.processRequest (second);

      assertEquals (AIDCommand.AID_PF7, second.key);
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("processAuto - varredura das paginas")
  class AutoScroll
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("uma tela que nao e do EDIT interrompe a varredura")
    void stopsOnForeignScreen ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());

      plugin.processAuto (new ScreenBuilder ()
          .protectedField (0, 0, "ISPF Primary Option Menu").build ());

      assertFalse (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("continua descendo enquanto houver linhas novas")
    void keepsScrollingDown ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());

      PluginData second = page ("Columns 00001 00072", 400).build ();
      plugin.processAuto (second);

      assertEquals (AIDCommand.AID_PF8, second.key);
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("ao encontrar o fim dos dados vai para o canto inferior direito")
    void goesRightWhenEndReached ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());

      PluginData second = pageWithEnd ("Columns 00001 00072", 400).build ();
      plugin.processAuto (second);

      assertEquals (AIDCommand.AID_PF11, second.key);
      assertEquals ("m", maxValue (second));
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("numa faixa deslocada sem inicio de dados, sobe (PF7)")
    void scrollsUpOnShiftedPage ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());

      PluginData shifted = page ("Columns 00073 00144", 400).build ();
      plugin.processAuto (shifted);

      assertEquals (AIDCommand.AID_PF7, shifted.key);
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("uma pagina repetida encerra a varredura")
    void stopsWhenPageRepeats ()
    {
      // sem documento aberto, o fim da varredura nao precisa salvar nada
      DownloadDataset fresh = new DownloadDataset ();
      fresh.activate ();

      PluginData first = page ("Columns 00073 00144", 400).build ();
      fresh.processRequest (first);
      assertEquals (AIDCommand.AID_PF7, first.key);     // fora da linha 1: sobe primeiro

      // a mesma pagina duas vezes: nada mudou, entao paramos
      fresh.processAuto (page ("Columns 00073 00144", 400).build ());
      PluginData repeat = page ("Columns 00073 00144", 400).build ();
      fresh.processAuto (repeat);

      assertFalse (fresh.doesAuto ());
    }

    @Test
    @DisplayName ("sem documento aberto e fora da linha 1, a varredura desiste")
    void givesUpWhenNotAtFirstLine ()
    {
      DownloadDataset fresh = new DownloadDataset ();
      fresh.activate ();

      PluginData first = page ("Columns 00001 00072", 500).build ();
      fresh.processRequest (first);        // manda PF7, sem abrir documento

      // a tela seguinte ainda nao esta no topo
      PluginData second = page ("Columns 00001 00072", 300).build ();
      fresh.processAuto (second);

      assertFalse (fresh.doesAuto ());
    }

    @Test
    @DisplayName ("sem documento aberto e fora da coluna 1, insiste no PF10")
    void retriesLeftWhenNotAtFirstColumn ()
    {
      DownloadDataset fresh = new DownloadDataset ();
      fresh.activate ();

      PluginData first = page ("Columns 00073 00144", 1).build ();
      fresh.processRequest (first);        // manda PF10, sem abrir documento

      PluginData second = page ("Columns 00145 00216", 1).build ();
      fresh.processAuto (second);

      assertEquals (AIDCommand.AID_PF10, second.key);
      assertEquals ("m", maxValue (second));
      assertTrue (fresh.doesAuto ());
    }

    @Test
    @DisplayName ("chegando ao topo e a esquerda, o documento e aberto e a descida comeca")
    void opensDocumentWhenBackAtTheOrigin ()
    {
      DownloadDataset fresh = new DownloadDataset ();
      fresh.activate ();

      PluginData first = page ("Columns 00001 00072", 500).build ();
      fresh.processRequest (first);        // PF7

      PluginData second = page ("Columns 00001 00072", 1).build ();
      fresh.processAuto (second);

      assertEquals (AIDCommand.AID_PF8, second.key);
      assertTrue (fresh.doesAuto ());
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("fim da captura")
  class Completion
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("uma pagina vazia encerra a captura e entrega o documento montado")
    void emptyPageFinishesTheCapture ()
    {
      // a escolha do arquivo e uma etapa separada da captura: o teste a substitui e
      // verifica o documento que seria gravado
      List<Document> saved = new ArrayList<> ();
      plugin.setDocumentSaver (saved::add);

      plugin.processRequest (page ("Columns 00001 00072", 1).build ());
      plugin.processAuto (emptyPage ("Columns 00001 00072").build ());

      assertFalse (plugin.doesAuto ());
      assertEquals (1, saved.size ());
      assertEquals ("MYUSER.TEST.CNTL", saved.get (0).datasetName);
      assertEquals ("JOB1", saved.get (0).memberName);
    }

    @Test
    @DisplayName ("uma pagina repetida tambem encerra e entrega o documento")
    void repeatedPageFinishesTheCapture ()
    {
      List<Document> saved = new ArrayList<> ();
      plugin.setDocumentSaver (saved::add);

      plugin.processRequest (page ("Columns 00001 00072", 1).build ());
      plugin.processAuto (page ("Columns 00001 00072", 400).build ());
      plugin.processAuto (page ("Columns 00001 00072", 400).build ());

      assertFalse (plugin.doesAuto ());
      assertEquals (1, saved.size ());
    }

    @Test
    @DisplayName ("o nome sugerido para o arquivo usa o membro quando existe")
    void suggestsTheMemberName ()
    {
      List<Document> saved = new ArrayList<> ();
      plugin.setDocumentSaver (saved::add);

      plugin.processRequest (page ("Columns 00001 00072", 1).build ());
      plugin.processAuto (emptyPage ("Columns 00001 00072").build ());

      assertEquals ("JOB1.txt", DownloadDataset.suggestedFileName (saved.get (0)));
    }

    @Test
    @DisplayName ("o arquivo gravado traz uma linha por registro")
    void writesOneLinePerRecord (@TempDir File directory)
    {
      List<Document> saved = new ArrayList<> ();
      plugin.setDocumentSaver (saved::add);

      plugin.processRequest (page ("Columns 00001 00072", 1).build ());
      plugin.processAuto (emptyPage ("Columns 00001 00072").build ());

      File file = new File (directory, "JOB1.txt");
      DownloadDataset.writeTo (saved.get (0), file);

      assertTrue (file.exists ());
      assertEquals (3, linesOf (file), "as tres linhas capturadas");
    }

    @Test
    @DisplayName ("uma pagina vazia sem documento aberto encerra sem salvar")
    void emptyPageWithoutDocument ()
    {
      DownloadDataset fresh = new DownloadDataset ();
      fresh.activate ();

      PluginData first = page ("Columns 00001 00072", 500).build ();
      fresh.processRequest (first);        // PF7, sem abrir documento

      fresh.processAuto (emptyPage ("Columns 00001 00072").build ());

      assertFalse (fresh.doesAuto ());
    }

    @Test
    @DisplayName ("nao existe mais limite de iteracoes, permitindo grandes downloads")
    void canProcessMoreThanTwentyLoops ()
    {
      DownloadDataset fresh = new DownloadDataset ();
      fresh.activate ();

      PluginData first = page ("Columns 00073 00144", 1).build ();
      fresh.processRequest (first);        // PF10, sem abrir documento

      for (int i = 0; i < 21; i++)
        fresh.processAuto (page (String.format ("Columns %05d %05d", 73 + i * 72,
                                              144 + i * 72), 1).build ());

      assertTrue (fresh.doesAuto (), "o loop de iteracoes nao deveria mais parar o processamento");
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("campo de scroll")
  class ScrollField
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("o 'm' de scroll maximo vai para o campo de scroll")
    void maxGoesToTheScrollField ()
    {
      // setMax () localiza o rotulo "Scroll ===>" e altera o campo seguinte: e ele que
      // o ISPF le para rolar ao extremo com PF7/PF8. Escrever na linha de comando nao
      // rolava nada e deixava lixo para o ISPF interpretar como comando.
      PluginData data = page ("Columns 00001 00072", 500).build ();

      plugin.processRequest (data);

      assertEquals (1, data.changedFields.size ());
      assertEquals ("m", data.changedFields.get (0).newData);
      assertEquals (5, data.changedFields.get (0).sequence, "campo apos Scroll ===>");
    }

    @Test
    @DisplayName ("sem rotulo de scroll nenhum campo e alterado")
    void withoutScrollLabel ()
    {
      // uma tela de EDIT sem "Scroll ===>" nao passa por createPage, entao o setMax
      // nunca e chamado — mas se fosse, sairia sem tocar em nada
      PluginData data = new ScreenBuilder ()                              //
          .protectedField (0, 0, TITLE)                                   //
          .protectedField (0, 60, "Columns 00001 00072")                  //
          .protectedField (1, 0, "Command ===>")                          //
          .inputField (1, 20, 40, "").build ();

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
    }

    @Test
    @DisplayName ("sem rotulo de comando o scroll nao e alterado")
    void withoutCommandLabel ()
    {
      // uma tela de EDIT sem o rotulo "Command ===>" nao passa por createPage, entao
      // o setMax nunca e chamado — o teste garante que o caminho e o mesmo
      PluginData data = new ScreenBuilder ()                              //
          .protectedField (0, 0, TITLE)                                   //
          .protectedField (0, 60, "Columns 00001 00072")                  //
          .protectedField (2, 0, "Scroll ===>")                           //
          .inputField (2, 20, 8, "PAGE").build ();

      plugin.processRequest (data);

      assertFalse (plugin.doesAuto ());
      assertTrue (data.changedFields.isEmpty ());
    }
  }
}
