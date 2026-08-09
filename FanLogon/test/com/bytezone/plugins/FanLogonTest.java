package com.bytezone.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.bytezone.dm3270.commands.AIDCommand;
import com.bytezone.dm3270.plugins.PluginData;
import com.bytezone.dm3270.plugins.PluginField;
import com.bytezone.dm3270.plugins.ScreenLocation;

// -----------------------------------------------------------------------------------//
@DisplayName ("FanLogon - logon automatico no servidor publico FanDeZhi")
class FanLogonTest
// -----------------------------------------------------------------------------------//
{
  private static final int COLUMNS = 80;
  private static final String USER = "HERC01";
  private static final String PASSWORD = "CUL8TR";

  private static final String TSO_HEADING = "------------------------------- "
      + "TSO/E LOGON -----------------------------------";
  private static final String FANDEZHI_HEADING =
      "Mainframe Operating System                              z/OS V1.6";

  private String originalHome;
  private FanLogon plugin;

  // Parameters le ~/dm3270/prefs.txt no construtor do plugin: o teste redireciona o
  // HOME para escrever um arquivo proprio e assim cobrir o activate () sem JavaFX.
  @BeforeEach
  void setUp (@TempDir File directory) throws IOException
  {
    originalHome = System.getProperty ("user.home");
    System.setProperty ("user.home", directory.getAbsolutePath ());

    File prefs = new File (new File (directory, "dm3270"), "prefs.txt");
    prefs.getParentFile ().mkdirs ();
    Files.write (prefs.toPath (), Arrays.asList ("[FanDeZhi]", "user=" + USER,
                                                "password=" + PASSWORD));

    plugin = new FanLogon ();
    plugin.activate ();
  }

  @AfterEach
  void tearDown ()
  {
    System.setProperty ("user.home", originalHome);
  }

  // ---------------------------------------------------------------------------------//
  //  Construcao das telas
  // ---------------------------------------------------------------------------------//

  private static class Screen
  {
    private final List<PluginField> fields = new ArrayList<> ();

    Screen text (String value)
    {
      return add (value.length (), true, value);
    }

    Screen input (int length)
    {
      return add (length, false, "");
    }

    Screen filler (int count)
    {
      for (int i = 0; i < count; i++)
        add (1, true, "x");
      return this;
    }

    private Screen add (int length, boolean isProtected, String value)
    {
      int position = fields.size () * COLUMNS;
      fields.add (new PluginField (fields.size (), new ScreenLocation (position), length,
                                   isProtected, true, true, false, value));
      return this;
    }

    PluginData build (int sequence)
    {
      return new PluginData (sequence, new ScreenLocation (0), new ArrayList<> (fields));
    }
  }

  // A tela inicial do FanDeZhi: cabecalho no campo 0, "TSO" no 5, a descricao no 6 e o
  // campo de comando editavel de 58 caracteres no 17.
  private static PluginData fanDeZhiScreen (int sequence)
  {
    return new Screen ().text (FANDEZHI_HEADING)      // 0
        .filler (4)                                   // 1-4
        .text ("TSO")                                 // 5
        .text ("- Logon to TSO/ISPF")                 // 6
        .filler (10)                                  // 7-16
        .input (58)                                   // 17
        .build (sequence);
  }

  // A tela de senha do TSO/E: cabecalho no campo 0, aviso no 3 e senha editavel no 12.
  private static PluginData passwordScreen (int sequence, int passwordLength)
  {
    return new Screen ().text (TSO_HEADING)                   // 0
        .filler (2)                                           // 1-2
        .text ("Enter LOGON parameters below:")               // 3
        .filler (8)                                           // 4-11
        .input (passwordLength)                               // 12
        .build (sequence);
  }

  // O menu principal do ISPF, que encerra o logon.
  private static PluginData ispfMenuScreen (int sequence, String user)
  {
    return new Screen ().filler (10)                          // 0-9
        .text ("ISPF Primary Option Menu")                    // 10
        .filler (12)                                          // 11-22
        .text ("User ID . :")                                 // 23
        .text (user)                                          // 24
        .build (sequence);
  }

  private static String changedValue (PluginData data)
  {
    return data.changedFields.isEmpty () ? null : data.changedFields.get (0).newData;
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("activate")
  class Activation
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("com os parametros no lugar, o plugin espera um pedido")
    void enablesRequest ()
    {
      assertTrue (plugin.doesRequest ());
      assertFalse (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("uma secao sem user nem password e recusada com alerta")
    void missingKeysAreRejected (@TempDir File other) throws IOException
    {
      // SiteParameters.getParameter () devolve "" para uma chave ausente, nunca null:
      // a verificacao usa isEmpty (), senao o plugin seguiria adiante e montaria o
      // comando "TSO " com o usuario vazio
      FanLogon incomplete = pluginWithPrefs (other, "[FanDeZhi]");
      List<String> alerts = new ArrayList<> ();
      incomplete.setAlertHandler ( (type, message) -> alerts.add (message));

      incomplete.activate ();

      assertEquals (List.of ("Parameters not found"), alerts);
      assertFalse (incomplete.doesRequest ());
      assertFalse (incomplete.doesAuto ());
    }

    @Test
    @DisplayName ("uma secao com user mas sem password tambem e recusada")
    void missingPasswordIsRejected (@TempDir File other) throws IOException
    {
      FanLogon incomplete =
          pluginWithPrefs (other, "[FanDeZhi]", "user=" + USER);
      List<String> alerts = new ArrayList<> ();
      incomplete.setAlertHandler ( (type, message) -> alerts.add (message));

      incomplete.activate ();

      assertEquals (List.of ("Parameters not found"), alerts);
      assertFalse (incomplete.doesRequest ());
    }

    @Test
    @DisplayName ("sem a secao FanDeZhi o plugin avisa e nao se habilita")
    void missingSectionIsReported (@TempDir File other) throws IOException
    {
      FanLogon incomplete = pluginWithPrefs (other, "[OutroSite]", "user=X");
      List<String> alerts = new ArrayList<> ();
      incomplete.setAlertHandler ( (type, message) -> alerts.add (message));

      incomplete.activate ();

      assertEquals (List.of ("Parameters for FanDeZhi not found"), alerts);
      assertFalse (incomplete.doesRequest ());
    }

    // Monta um plugin com um prefs.txt proprio, sem tocar o do caso principal.
    private FanLogon pluginWithPrefs (File directory, String... lines) throws IOException
    {
      System.setProperty ("user.home", directory.getAbsolutePath ());
      File prefs = new File (new File (directory, "dm3270"), "prefs.txt");
      prefs.getParentFile ().mkdirs ();
      Files.write (prefs.toPath (), Arrays.asList (lines));

      return new FanLogon ();
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("processRequest - tela inicial do FanDeZhi")
  class FirstScreen
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("digita TSO <usuario> e liga a automacao")
    void typesTsoCommand ()
    {
      PluginData data = fanDeZhiScreen (0);

      plugin.processRequest (data);

      assertEquals ("TSO " + USER, changedValue (data));
      assertEquals (AIDCommand.AID_ENTER, data.key);
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("uma tela com menos de sete campos e ignorada")
    void screenTooSmall ()
    {
      PluginData data = new Screen ().text (FANDEZHI_HEADING).filler (3).build (0);

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
      assertFalse (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("um cabecalho diferente e ignorado")
    void wrongHeading ()
    {
      PluginData data = new Screen ().text ("Outro sistema").filler (4).text ("TSO")
          .text ("- Logon to TSO/ISPF").filler (10).input (58).build (0);

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
      assertFalse (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("um campo de comando de outro tamanho e ignorado")
    void wrongCommandFieldLength ()
    {
      PluginData data = new Screen ().text (FANDEZHI_HEADING).filler (4).text ("TSO")
          .text ("- Logon to TSO/ISPF").filler (10).input (40).build (0);

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
      assertFalse (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("a opcao do menu tem de ser TSO")
    void wrongMenuOption ()
    {
      PluginData data = new Screen ().text (FANDEZHI_HEADING).filler (4).text ("CICS")
          .text ("- Logon to TSO/ISPF").filler (10).input (58).build (0);

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("processAuto - sequencia do logon")
  class AutoSequence
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("a tela inicial tambem e reconhecida no modo automatico")
    void recognisesFirstScreenInAutoMode ()
    {
      PluginData data = fanDeZhiScreen (0);

      plugin.processAuto (data);

      assertEquals ("TSO " + USER, changedValue (data));
      assertEquals (AIDCommand.AID_ENTER, data.key);
    }

    @Test
    @DisplayName ("uma tela inicial irreconhecivel na sequencia 0 nao muda nada")
    void unknownFirstScreen ()
    {
      PluginData data = new Screen ().text ("Outro sistema").filler (20).build (0);

      plugin.processAuto (data);

      assertTrue (data.changedFields.isEmpty ());
    }

    @ParameterizedTest (name = "sequencia {0}")
    @ValueSource (ints = { 1, 2 })
    @DisplayName ("as duas telas seguintes ao TSO sao apenas atravessadas")
    void passesThroughIntermediateScreens (int sequence)
    {
      plugin.processRequest (fanDeZhiScreen (0));

      PluginData data = new Screen ().filler (5).build (sequence);
      plugin.processAuto (data);

      assertTrue (data.changedFields.isEmpty ());
      assertEquals (0, data.key);
      assertTrue (plugin.doesAuto (), "a automacao deveria continuar");
    }

    @Test
    @DisplayName ("na terceira tela a senha e digitada")
    void typesPassword ()
    {
      plugin.processRequest (fanDeZhiScreen (0));

      PluginData data = passwordScreen (3, 8);
      plugin.processAuto (data);

      assertEquals (PASSWORD, changedValue (data));
      assertEquals (AIDCommand.AID_ENTER, data.key);
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("uma terceira tela que nao e a da senha encerra a automacao")
    void wrongPasswordScreen ()
    {
      plugin.processRequest (fanDeZhiScreen (0));

      PluginData data = new Screen ().text ("Outra tela").filler (20).build (3);
      plugin.processAuto (data);

      assertTrue (data.changedFields.isEmpty ());
      assertFalse (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("o menu do ISPF encerra o logon e suprime a exibicao")
    void reachesIspfMenu ()
    {
      plugin.processRequest (fanDeZhiScreen (0));

      PluginData data = ispfMenuScreen (5, USER);
      plugin.processAuto (data);

      assertFalse (plugin.doesAuto ());
      assertTrue (data.suppressDisplay);
      assertTrue (data.changedFields.isEmpty ());
    }

    @Test
    @DisplayName ("o menu de outro usuario nao encerra o logon")
    void ispfMenuOfAnotherUser ()
    {
      plugin.processRequest (fanDeZhiScreen (0));

      PluginData data = ispfMenuScreen (5, "OUTRO");
      plugin.processAuto (data);

      assertEquals (AIDCommand.AID_ENTER, data.key);
      assertFalse (data.suppressDisplay);
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("telas intermediarias entre a senha e o menu levam ENTER")
    void pressesEnterUntilTheMenu ()
    {
      plugin.processRequest (fanDeZhiScreen (0));

      for (int sequence = 5; sequence <= 20; sequence++)
      {
        PluginData data = new Screen ().filler (30).build (sequence);
        plugin.processAuto (data);

        assertEquals (AIDCommand.AID_ENTER, data.key, "sequencia " + sequence);
      }

      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("passando de vinte telas a automacao desiste")
    void givesUpAfterTwentyScreens ()
    {
      plugin.processRequest (fanDeZhiScreen (0));

      PluginData data = new Screen ().filler (30).build (21);
      plugin.processAuto (data);

      assertFalse (plugin.doesAuto ());
      assertEquals (0, data.key);
    }

    @Test
    @DisplayName ("o deslocamento acompanha a sequencia em que o pedido comecou")
    void offsetFollowsTheRequestSequence ()
    {
      // o pedido veio na tela 10, entao a senha e esperada na tela 13
      plugin.processRequest (fanDeZhiScreen (10));

      PluginData data = passwordScreen (13, 8);
      plugin.processAuto (data);

      assertEquals (PASSWORD, changedValue (data));
    }

    @Test
    @DisplayName ("uma tela de senha antes do momento certo e apenas atravessada")
    void passwordScreenTooEarly ()
    {
      plugin.processRequest (fanDeZhiScreen (0));

      PluginData data = passwordScreen (2, 8);
      plugin.processAuto (data);

      assertNull (changedValue (data));
      assertTrue (plugin.doesAuto ());
    }
  }
}
