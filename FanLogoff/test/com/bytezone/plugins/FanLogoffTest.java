package com.bytezone.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.bytezone.dm3270.commands.AIDCommand;
import com.bytezone.dm3270.plugins.PluginData;
import com.bytezone.dm3270.plugins.PluginField;
import com.bytezone.dm3270.plugins.ScreenLocation;

// -----------------------------------------------------------------------------------//
@DisplayName ("FanLogoff - reconhece a linha de comando e envia o logoff")
class FanLogoffTest
// -----------------------------------------------------------------------------------//
{
  private static final int COLUMNS = 80;

  private FanLogoff plugin;

  @BeforeEach
  void setUp ()
  {
    plugin = new FanLogoff ();
  }

  // ---------------------------------------------------------------------------------//
  //  Construcao das telas
  // ---------------------------------------------------------------------------------//

  private static class Screen
  {
    private final List<PluginField> fields = new ArrayList<> ();

    Screen protectedField (int row, int column, String value)
    {
      return add (row, column, value.length (), true, value);
    }

    Screen inputField (int row, int column, int length)
    {
      return add (row, column, length, false, "");
    }

    private Screen add (int row, int column, int length, boolean isProtected,
        String value)
    {
      fields.add (new PluginField (fields.size (),
          new ScreenLocation (row * COLUMNS + column), length, isProtected, true, true,
          false, value));
      return this;
    }

    PluginData build ()
    {
      return new PluginData (0, new ScreenLocation (0), new ArrayList<> (fields));
    }
  }

  // O prompt precisa estar na coluna 1 de uma das tres primeiras linhas, e o campo
  // seguinte tem de ser editavel com um dos comprimentos que o plugin reconhece.
  private static PluginData promptScreen (String prompt, int row, int inputLength)
  {
    return new Screen ().protectedField (row, 1, prompt)
        .inputField (row, 1 + prompt.length (), inputLength).build ();
  }

  private static String changedValue (PluginData data)
  {
    return data.changedFields.isEmpty () ? null : data.changedFields.get (0).newData;
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("estado inicial")
  class InitialState
  // ---------------------------------------------------------------------------------//
  {
    @Test
    @DisplayName ("comeca esperando um pedido do usuario")
    void startsWaitingForRequest ()
    {
      assertTrue (plugin.doesRequest ());
      assertFalse (plugin.doesAuto ());
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("reconhecimento do prompt")
  class PromptRecognition
  // ---------------------------------------------------------------------------------//
  {
    @ParameterizedTest (name = "linha {0}")
    @ValueSource (ints = { 1, 2, 3 })
    @DisplayName ("Command ===> vale nas tres primeiras linhas com campo de 48 ou 65")
    void commandPromptOnAnyOfThreeRows (int row)
    {
      PluginData data = promptScreen ("Command ===>", row, 48);

      plugin.processRequest (data);

      assertEquals ("=x", changedValue (data));
      assertEquals (AIDCommand.AID_ENTER, data.key);
      assertTrue (plugin.doesAuto ());
      assertFalse (plugin.doesRequest ());
    }

    @Test
    @DisplayName ("o campo de comando tambem pode ter 65 caracteres")
    void commandPromptWithLongerField ()
    {
      PluginData data = promptScreen ("Command ===>", 1, 65);

      plugin.processRequest (data);

      assertEquals ("=x", changedValue (data));
    }

    @ParameterizedTest (name = "{0} com campo de {1}")
    @CsvSource ({ "COMMAND INPUT ===>, 42", "Action ===>, 49", "Option ===>, 66" })
    @DisplayName ("os outros tres prompts valem apenas na linha 3")
    void otherPromptsOnRowThree (String prompt, int inputLength)
    {
      PluginData data = promptScreen (prompt, 3, inputLength);

      plugin.processRequest (data);

      assertEquals ("=x", changedValue (data));
      assertEquals (AIDCommand.AID_ENTER, data.key);
      assertTrue (plugin.doesAuto ());
    }

    @ParameterizedTest (name = "{0} na linha 1")
    @CsvSource ({ "COMMAND INPUT ===>, 42", "Action ===>, 49", "Option ===>, 66" })
    @DisplayName ("esses prompts sao ignorados fora da linha 3")
    void otherPromptsOnlyOnRowThree (String prompt, int inputLength)
    {
      PluginData data = promptScreen (prompt, 1, inputLength);

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
      assertFalse (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("um comprimento de campo diferente do esperado e recusado")
    void wrongFieldLength ()
    {
      PluginData data = promptScreen ("Command ===>", 1, 50);

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
      assertFalse (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("um prompt fora da coluna 1 e recusado")
    void wrongColumn ()
    {
      PluginData data = new Screen ().protectedField (1, 5, "Command ===>")
          .inputField (1, 20, 48).build ();

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
    }

    @Test
    @DisplayName ("um prompt na linha 4 e recusado")
    void wrongRow ()
    {
      PluginData data = promptScreen ("Command ===>", 4, 48);

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
    }

    @Test
    @DisplayName ("um prompt editavel nao conta")
    void promptMustBeProtected ()
    {
      PluginData data = new Screen ().inputField (1, 1, 12).inputField (1, 13, 48).build ();

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
    }

    @Test
    @DisplayName ("o campo seguinte tem de existir")
    void needsAFollowingField ()
    {
      PluginData data = new Screen ().protectedField (1, 1, "Command ===>").build ();

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
    }

    @Test
    @DisplayName ("apenas os vinte primeiros campos sao examinados")
    void looksAtTwentyFieldsOnly ()
    {
      Screen screen = new Screen ();
      for (int i = 0; i < 20; i++)
        screen.protectedField (10, i, "x");
      screen.protectedField (1, 1, "Command ===>").inputField (1, 13, 48);

      PluginData data = screen.build ();
      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty (),
                  "o prompt esta na posicao 20, fora da janela examinada");
    }

    @Test
    @DisplayName ("uma tela vazia nao faz nada")
    void emptyScreen ()
    {
      PluginData data = new Screen ().build ();

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
      assertFalse (plugin.doesAuto ());
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("ISPF Command Shell")
  class CommandShell
  // ---------------------------------------------------------------------------------//
  {
    // A tela do shell e reconhecida por posicao: campo 10 com o titulo, campo 17 com
    // "===>" e campo 18 editavel de 234 caracteres.
    private PluginData shellScreen (String title, String arrow, int inputLength)
    {
      Screen screen = new Screen ();
      for (int i = 0; i < 10; i++)
        screen.protectedField (0, i, "x");

      screen.protectedField (5, 10, title);                    // 10
      for (int i = 11; i < 17; i++)
        screen.protectedField (6, i, "x");

      screen.protectedField (8, 1, arrow);                     // 17
      screen.inputField (8, 6, inputLength);                   // 18

      return screen.build ();
    }

    @Test
    @DisplayName ("reconhece a tela pelo titulo, pela flecha e pelo tamanho do campo")
    void recognisesShell ()
    {
      PluginData data = shellScreen ("ISPF Command Shell", "===>", 234);

      plugin.processRequest (data);

      assertEquals ("=x", changedValue (data));
      assertEquals (AIDCommand.AID_ENTER, data.key);
      assertTrue (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("um titulo diferente e recusado")
    void wrongTitle ()
    {
      PluginData data = shellScreen ("ISPF Primary Option", "===>", 234);

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
    }

    @Test
    @DisplayName ("uma flecha diferente e recusada")
    void wrongArrow ()
    {
      PluginData data = shellScreen ("ISPF Command Shell", "==>", 234);

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
    }

    @Test
    @DisplayName ("um campo de entrada de outro tamanho e recusado")
    void wrongInputLength ()
    {
      PluginData data = shellScreen ("ISPF Command Shell", "===>", 100);

      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
    }

    @Test
    @DisplayName ("uma tela com menos de 19 campos nao chega a ser examinada")
    void screenTooSmall ()
    {
      Screen screen = new Screen ();
      for (int i = 0; i < 18; i++)
        screen.protectedField (0, i, "x");

      PluginData data = screen.build ();
      plugin.processRequest (data);

      assertTrue (data.changedFields.isEmpty ());
    }
  }

  // ---------------------------------------------------------------------------------//
  @Nested
  @DisplayName ("processAuto - tela READY")
  class ReadyScreen
  // ---------------------------------------------------------------------------------//
  {
    @ParameterizedTest (name = "comprimento {0}")
    @ValueSource (ints = { 1911, 1831 })
    @DisplayName ("digita logoff no campo grande que segue o READY")
    void typesLogoff (int inputLength)
    {
      PluginData data = new Screen ().protectedField (0, 0, "READY")
          .inputField (1, 0, inputLength).build ();

      plugin.processAuto (data);

      assertEquals ("logoff", changedValue (data));
      assertEquals (AIDCommand.AID_ENTER, data.key);
      assertFalse (plugin.doesAuto (), "o logoff encerra a automacao");
    }

    @Test
    @DisplayName ("o READY tambem e reconhecido no terceiro campo")
    void readyOnThirdField ()
    {
      PluginData data = new Screen ().protectedField (0, 0, "x")
          .protectedField (0, 2, "y").protectedField (1, 0, "READY")
          .inputField (2, 0, 1911).build ();

      plugin.processAuto (data);

      assertEquals ("logoff", changedValue (data));
    }

    @Test
    @DisplayName ("um campo de outro comprimento nao recebe o comando")
    void wrongInputLength ()
    {
      PluginData data = new Screen ().protectedField (0, 0, "READY")
          .inputField (1, 0, 100).build ();

      plugin.processAuto (data);

      assertTrue (data.changedFields.isEmpty ());
      assertFalse (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("sem READY na tela nada e digitado")
    void noReadyOnScreen ()
    {
      PluginData data = new Screen ().protectedField (0, 0, "ISPF")
          .inputField (1, 0, 1911).build ();

      plugin.processAuto (data);

      assertTrue (data.changedFields.isEmpty ());
      assertFalse (plugin.doesAuto ());
    }

    @Test
    @DisplayName ("processAuto sempre desliga a automacao no fim")
    void alwaysTurnsAutoOff ()
    {
      plugin.processRequest (promptScreen ("Command ===>", 1, 48));
      assertTrue (plugin.doesAuto ());

      plugin.processAuto (new Screen ().build ());

      assertFalse (plugin.doesAuto ());
    }
  }
}
