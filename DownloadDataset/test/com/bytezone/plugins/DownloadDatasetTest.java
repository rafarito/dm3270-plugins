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
  private final List<String> alerts = new ArrayList<> ();

  private DownloadDataset createPlugin ()
  {
    DownloadDataset p = new DownloadDataset ();
    p.setAlertHandler ((type, msg) -> alerts.add (type + ": " + msg));
    return p;
  }

  @BeforeEach
  void setUp ()
  {
    alerts.clear ();
    plugin = createPlugin ();
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

  // A mesma pagina com o marcador de inicio de dados.
  private static ScreenBuilder pageWithTop (String columns, int firstLine)
  {
    return page (columns, firstLine).protectedField (6, 8,
        "***************************** Top of Data ******************************");
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

  // setMax () escreve no campo seguinte ao rotulo "Scroll ===>".
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

  /**
   * Helper: executa processRequest na origem (linha 1, col 1) e consome a sondagem
   * de LRECL padrao (PF11 MAX que nao rola porque LRECL <= 72).
   */
  private void requestAndProbe ()
  {
    plugin.processRequest (page ("Columns 00001 00072", 1).build ());
    plugin.processAuto (page ("Columns 00001 00072", 1).build ());
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
      DownloadDataset fresh = createPlugin ();

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
    @DisplayName ("no canto superior esquerdo sonda o LRECL com PF11 MAX")
    void probesLreclOnFirstScreen ()
    {
      PluginData data = page ("Columns 00001 00072", 1).build ();

      plugin.processRequest (data);

      assertEquals (AIDCommand.AID_PF11, data.key);
      assertEquals ("m", maxValue (data));
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("uma pagina unica com fim de dados tambem sonda LRECL com PF11 MAX")
    void probesLreclEvenWithEnd ()
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
  @DisplayName ("sondagem de LRECL")
  class LreclProbe
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("LRECL padrao (<=72): apos sondagem comeca a descer")
    void standardLreclStartsDescending ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());

      // Sondagem: PF11 MAX nao rolou (LRECL <= 72)
      PluginData probe = page ("Columns 00001 00072", 1).build ();
      plugin.processAuto (probe);

      assertEquals (AIDCommand.AID_PF8, probe.key);
      assertEquals ("page", maxValue (probe), "o scroll deveria voltar ao normal");
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("LRECL padrao com hasEnd: salva imediatamente")
    void standardLreclWithEndSaves ()
    {
      List<Document> saved = new ArrayList<> ();
      plugin.setDocumentSaver (saved::add);

      plugin.processRequest (pageWithEnd ("Columns 00001 00072", 1).build ());

      // Sondagem: PF11 MAX nao rolou, hasEnd visivel
      plugin.processAuto (pageWithEnd ("Columns 00001 00072", 1).build ());

      assertFalse (plugin.doesAuto ());
      assertEquals (1, saved.size ());
    }

    @Test
    @DisplayName ("LRECL largo (>72): volta para a coluna 1 com PF10 MAX")
    void wideLreclReturnsToColumnOne ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());

      // Sondagem: PF11 MAX rolou ate col 129-200
      PluginData probe = page ("Columns 00129 00200", 1).build ();
      plugin.processAuto (probe);

      assertEquals (AIDCommand.AID_PF10, probe.key);
      assertEquals ("m", maxValue (probe));
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("apos retornar da sondagem larga, comeca a descer")
    void afterWideProbeStartsDescending ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());
      plugin.processAuto (page ("Columns 00129 00200", 1).build ());    // sondagem

      // De volta na coluna 1
      PluginData back = page ("Columns 00001 00072", 1).build ();
      plugin.processAuto (back);

      assertEquals (AIDCommand.AID_PF8, back.key);
      assertEquals ("page", maxValue (back), "o scroll deveria voltar ao normal");
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("sondagem detectada apos navegacao inicial ao topo")
    void probeAfterScrollToTop ()
    {
      DownloadDataset fresh = createPlugin ();
      fresh.activate ();

      // Comeca no meio do documento
      PluginData first = page ("Columns 00001 00072", 500).build ();
      fresh.processRequest (first);        // PF7 para o topo

      // Chega ao topo: abre documento e sonda LRECL
      PluginData atTop = page ("Columns 00001 00072", 1).build ();
      fresh.processAuto (atTop);

      assertEquals (AIDCommand.AID_PF11, atTop.key);
      assertEquals ("m", maxValue (atTop));
      assertTrue (fresh.doesAuto ());
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
      requestAndProbe ();

      PluginData second = page ("Columns 00001 00072", 400).build ();
      plugin.processAuto (second);

      assertEquals (AIDCommand.AID_PF8, second.key);
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("com LRECL padrao, ao encontrar o fim dos dados encerra e salva")
    void savesWhenEndReachedWithStandardLrecl ()
    {
      List<Document> saved = new ArrayList<> ();
      plugin.setDocumentSaver (saved::add);

      requestAndProbe ();

      PluginData second = pageWithEnd ("Columns 00001 00072", 400).build ();
      plugin.processAuto (second);

      assertFalse (plugin.doesAuto ());
      assertEquals (1, saved.size ());
    }

    @Test
    @DisplayName ("uma pagina repetida encerra a varredura")
    void stopsWhenPageRepeats ()
    {
      // sem documento aberto, o fim da varredura nao precisa salvar nada
      DownloadDataset fresh = createPlugin ();
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
      DownloadDataset fresh = createPlugin ();
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
      DownloadDataset fresh = createPlugin ();
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
    @DisplayName ("chegando ao topo e a esquerda, o documento e aberto e a sondagem comeca")
    void opensDocumentWhenBackAtTheOrigin ()
    {
      DownloadDataset fresh = createPlugin ();
      fresh.activate ();

      PluginData first = page ("Columns 00001 00072", 500).build ();
      fresh.processRequest (first);        // PF7

      PluginData second = page ("Columns 00001 00072", 1).build ();
      fresh.processAuto (second);

      assertEquals (AIDCommand.AID_PF11, second.key);
      assertEquals ("m", maxValue (second));
      assertTrue (fresh.doesAuto ());
    }

    @Test
    @DisplayName ("uma pagina vazia sem documento aberto encerra sem salvar")
    void emptyPageWithoutDocument ()
    {
      DownloadDataset fresh = createPlugin ();
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
      DownloadDataset fresh = createPlugin ();
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
  @DisplayName ("serpentina horizontal com LRECL largo")
  class SerpentineScan
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("ao encontrar Bottom of Data com LRECL largo, avanca para a direita")
    void advancesRightOnEndWithWideLrecl ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());
      plugin.processAuto (page ("Columns 00129 00200", 1).build ());    // sondagem: LRECL=200
      plugin.processAuto (page ("Columns 00001 00072", 1).build ());    // retorno

      // Desce ate o fim dos dados
      PluginData bottom = pageWithEnd ("Columns 00001 00072", 400).build ();
      plugin.processAuto (bottom);

      assertEquals (AIDCommand.AID_PF11, bottom.key, "deve avancar para a direita");
      assertEquals ("page", maxValue (bottom), "o avanco deve ser de uma tela, nao MAX");
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("na faixa deslocada sem Top of Data, sobe com PF7")
    void ascendsInShiftedStripe ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());
      plugin.processAuto (page ("Columns 00129 00200", 1).build ());    // sondagem
      plugin.processAuto (page ("Columns 00001 00072", 1).build ());    // retorno
      plugin.processAuto (pageWithEnd ("Columns 00001 00072", 400).build ()); // bottom, PF11

      // Agora estamos na faixa 73-144, sem Top of Data visivel
      PluginData shifted = page ("Columns 00073 00144", 300).build ();
      plugin.processAuto (shifted);

      assertEquals (AIDCommand.AID_PF7, shifted.key, "deve subir nesta faixa");
      assertTrue (shifted.changedFields.isEmpty (),
          "continuar dentro da mesma faixa nao deve mexer no scroll");
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("ao atingir Top of Data na faixa deslocada, avanca novamente")
    void advancesRightOnTopInShiftedStripe ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());
      plugin.processAuto (page ("Columns 00129 00200", 1).build ());    // sondagem
      plugin.processAuto (page ("Columns 00001 00072", 1).build ());    // retorno
      plugin.processAuto (pageWithEnd ("Columns 00001 00072", 400).build ()); // PF11

      // Sobe ate o topo da faixa 73-144
      PluginData top = pageWithTop ("Columns 00073 00144", 1).build ();
      plugin.processAuto (top);

      // rightColumn (144) < detectedLrecl (200) -> avanca
      assertEquals (AIDCommand.AID_PF11, top.key, "deve avancar para a proxima faixa");
      assertEquals ("page", maxValue (top), "o avanco deve ser de uma tela, nao MAX");
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("na ultima faixa com Bottom of Data, salva o documento")
    void savesOnLastStripeBottom ()
    {
      List<Document> saved = new ArrayList<> ();
      plugin.setDocumentSaver (saved::add);

      plugin.processRequest (page ("Columns 00001 00072", 1).build ());
      plugin.processAuto (page ("Columns 00129 00200", 1).build ());    // sondagem: LRECL=200
      plugin.processAuto (page ("Columns 00001 00072", 1).build ());    // retorno
      plugin.processAuto (pageWithEnd ("Columns 00001 00072", 400).build ()); // PF11
      plugin.processAuto (pageWithTop ("Columns 00073 00144", 1).build ());  // PF11

      // Descendo na faixa 145-200, encontra Bottom of Data
      // rightColumn (200) < detectedLrecl (200) -> false -> salva
      PluginData lastBottom = pageWithEnd ("Columns 00145 00200", 400).build ();
      plugin.processAuto (lastBottom);

      assertFalse (plugin.doesAuto ());
      assertEquals (1, saved.size ());
    }

    @Test
    @DisplayName ("na ultima faixa com Top of Data, salva o documento")
    void savesOnLastStripeTop ()
    {
      List<Document> saved = new ArrayList<> ();
      plugin.setDocumentSaver (saved::add);

      // Simula LRECL=144 (2 faixas: 1-72 e 73-144)
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());
      plugin.processAuto (page ("Columns 00073 00144", 1).build ());    // sondagem: LRECL=144
      plugin.processAuto (page ("Columns 00001 00072", 1).build ());    // retorno
      plugin.processAuto (pageWithEnd ("Columns 00001 00072", 400).build ()); // PF11

      // Na faixa 73-144 subindo, atinge Top of Data
      // rightColumn (144) < detectedLrecl (144) -> false -> salva
      PluginData lastTop = pageWithTop ("Columns 00073 00144", 1).build ();
      plugin.processAuto (lastTop);

      assertFalse (plugin.doesAuto ());
      assertEquals (1, saved.size ());
    }

    @Test
    @DisplayName ("pagina repetida com colunas pendentes avanca ao inves de salvar")
    void repeatedPageAdvancesWhenColumnsRemain ()
    {
      plugin.processRequest (page ("Columns 00001 00072", 1).build ());
      plugin.processAuto (page ("Columns 00129 00200", 1).build ());    // sondagem: LRECL=200
      plugin.processAuto (page ("Columns 00001 00072", 1).build ());    // retorno

      // Duas vezes a mesma pagina: como ha colunas pendentes, avanca
      plugin.processAuto (page ("Columns 00001 00072", 400).build ());
      PluginData repeat = page ("Columns 00001 00072", 400).build ();
      plugin.processAuto (repeat);

      assertEquals (AIDCommand.AID_PF11, repeat.key, "deve avancar, nao salvar");
      assertEquals ("page", maxValue (repeat), "o avanco deve ser de uma tela, nao MAX");
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("LRECL=216 (3 faixas) com varias paginas por faixa: nenhuma linha e perdida")
    void capturesAllLinesAcrossThreeStripesWithoutSkipping ()
    {
      // Regressao: com o scroll preso em MAX apos a sondagem, um unico PF8/PF7
      // pulava direto do topo para o fim de cada faixa, perdendo as paginas do
      // meio - exatamente o que este teste percorre explicitamente.
      List<Document> saved = new ArrayList<> ();
      plugin.setDocumentSaver (saved::add);

      plugin.processRequest (page ("Columns 00001 00072", 1).build ());

      // Sondagem: LRECL=216 exige 3 faixas (1-72, 73-144, 145-216)
      plugin.processAuto (page ("Columns 00145 00216", 1).build ());

      // Retorno da sondagem: reafirma o topo da faixa 1
      PluginData back = page ("Columns 00001 00072", 1).build ();
      plugin.processAuto (back);
      assertEquals ("page", maxValue (back), "o scroll deveria voltar ao normal");

      // Faixa 1 (1-72), descendo: meio e fim, sem pular nenhum
      PluginData mid1 = page ("Columns 00001 00072", 301).build ();
      plugin.processAuto (mid1);
      assertTrue (mid1.changedFields.isEmpty (), "continuacao normal nao mexe no scroll");

      PluginData bottom1 = pageWithEnd ("Columns 00001 00072", 601).build ();
      plugin.processAuto (bottom1);
      assertEquals (AIDCommand.AID_PF11, bottom1.key, "fim da faixa 1: avanca para a faixa 2");
      assertEquals ("page", maxValue (bottom1), "o avanco deve ser de uma tela, nao MAX");

      // Faixa 2 (73-144), subindo a partir do fim: fim, meio e topo
      PluginData bottom2 = pageWithEnd ("Columns 00073 00144", 601).build ();
      plugin.processAuto (bottom2);
      assertEquals (AIDCommand.AID_PF7, bottom2.key, "faixa deslocada: sobe");

      PluginData mid2 = page ("Columns 00073 00144", 301).build ();
      plugin.processAuto (mid2);
      assertTrue (mid2.changedFields.isEmpty (), "continuacao normal nao mexe no scroll");

      PluginData top2 = pageWithTop ("Columns 00073 00144", 1).build ();
      plugin.processAuto (top2);
      assertEquals (AIDCommand.AID_PF11, top2.key, "topo da faixa 2: avanca para a faixa 3");
      assertEquals ("page", maxValue (top2), "o avanco deve ser de uma tela, nao MAX");

      // Faixa 3 (145-216), descendo a partir do topo: topo, meio e fim -> salva
      PluginData top3 = pageWithTop ("Columns 00145 00216", 1).build ();
      plugin.processAuto (top3);
      assertEquals (AIDCommand.AID_PF8, top3.key, "faixa deslocada: desce");

      PluginData mid3 = page ("Columns 00145 00216", 301).build ();
      plugin.processAuto (mid3);
      assertTrue (mid3.changedFields.isEmpty (), "continuacao normal nao mexe no scroll");

      plugin.processAuto (pageWithEnd ("Columns 00145 00216", 601).build ());

      assertFalse (plugin.doesAuto ());
      assertEquals (1, saved.size ());

      Document document = saved.get (0);
      assertEquals (9, document.getLines ().size (),
          "3 paginas verticais x 3 linhas cada, sem duplicar nem pular nenhuma");
      assertEquals (216, document.maxColumns, "deve ter costurado as 3 faixas ate o LRECL");
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
      List<Document> saved = new ArrayList<> ();
      plugin.setDocumentSaver (saved::add);

      requestAndProbe ();
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

      requestAndProbe ();
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

      requestAndProbe ();
      plugin.processAuto (emptyPage ("Columns 00001 00072").build ());

      assertEquals ("JOB1.txt", DownloadDataset.suggestedFileName (saved.get (0)));
    }

    @Test
    @DisplayName ("o arquivo gravado traz uma linha por registro")
    void writesOneLinePerRecord (@TempDir File directory) throws IOException
    {
      List<Document> saved = new ArrayList<> ();
      plugin.setDocumentSaver (saved::add);

      requestAndProbe ();
      plugin.processAuto (emptyPage ("Columns 00001 00072").build ());

      File file = new File (directory, "JOB1.txt");
      DownloadDataset.writeTo (saved.get (0), file);

      assertTrue (file.exists ());
      assertEquals (3, linesOf (file), "as tres linhas capturadas");
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
      PluginData data = page ("Columns 00001 00072", 500).build ();

      plugin.processRequest (data);

      assertEquals (1, data.changedFields.size ());
      assertEquals ("m", data.changedFields.get (0).newData);
      assertEquals (5, data.changedFields.get (0).sequence, "campo apos Scroll ===> ");
    }

    @Test
    @DisplayName ("sem rotulo de scroll nenhum campo e alterado")
    void withoutScrollLabel ()
    {
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
