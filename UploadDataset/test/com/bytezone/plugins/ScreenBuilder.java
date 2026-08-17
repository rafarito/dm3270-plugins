package com.bytezone.plugins;

import java.util.ArrayList;
import java.util.List;

import com.bytezone.dm3270.plugins.PluginData;
import com.bytezone.dm3270.plugins.PluginField;
import com.bytezone.dm3270.plugins.ScreenLocation;

/**
 * Monta telas ISPF sinteticas para os testes.
 *
 * A sequencia de cada campo e sempre igual ao seu indice na lista, porque
 * {@code PluginData.getField(int)} indexa a lista por essa posicao - e
 * {@code DocumentPage} depende disso ao procurar o campo seguinte a um rotulo.
 */
// -----------------------------------------------------------------------------------//
final class ScreenBuilder
// -----------------------------------------------------------------------------------//
{
  static final int COLUMNS = 80;

  private final List<PluginField> fields = new ArrayList<> ();
  private int cursorLocation;

  // ---------------------------------------------------------------------------------//
  ScreenBuilder protectedField (int row, int column, String value)
  // ---------------------------------------------------------------------------------//
  {
    return add (row, column, value.length (), true, value);
  }

  // ---------------------------------------------------------------------------------//
  ScreenBuilder inputField (int row, int column, int length, String value)
  // ---------------------------------------------------------------------------------//
  {
    return add (row, column, length, false, value);
  }

  // ---------------------------------------------------------------------------------//
  ScreenBuilder cursorAt (int row, int column)
  // ---------------------------------------------------------------------------------//
  {
    cursorLocation = row * COLUMNS + column;
    return this;
  }

  // ---------------------------------------------------------------------------------//
  private ScreenBuilder add (int row, int column, int length, boolean isProtected,
      String value)
  // ---------------------------------------------------------------------------------//
  {
    fields.add (new PluginField (fields.size (),
        new ScreenLocation (row * COLUMNS + column), length, isProtected, true, true,
        false, value));
    return this;
  }

  // ---------------------------------------------------------------------------------//
  PluginData build ()
  // ---------------------------------------------------------------------------------//
  {
    return new PluginData (0, new ScreenLocation (cursorLocation),
        new ArrayList<> (fields));
  }

  // ---------------------------------------------------------------------------------//
  static List<PluginField> modifiableFields (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    List<PluginField> modifiable = new ArrayList<> ();
    for (PluginField field : data.screenFields)
      if (!field.isProtected)
        modifiable.add (field);

    return modifiable;
  }

  /**
   * Cabecalho comum de uma tela de EDIT do ISPF: titulo, faixa de colunas,
   * linha de comando e linha de scroll - exatamente o que
   * {@code DocumentPage.createPage} exige para aceitar a tela.
   */
  // ---------------------------------------------------------------------------------//
  static ScreenBuilder ispfEditScreen (String title, String columns)
  // ---------------------------------------------------------------------------------//
  {
    return new ScreenBuilder ()                                  //
        .protectedField (0, 0, title)                            // 0
        .protectedField (0, 60, columns)                         // 1
        .protectedField (1, 0, "Command ===>")                   // 2
        .inputField (1, 20, 40, "")                              // 3
        .protectedField (2, 0, "Scroll ===>")                    // 4
        .inputField (2, 20, 8, "PAGE");                          // 5
  }

  /** Uma linha de dados: numero na coluna 1 e conteudo na coluna 8. */
  // ---------------------------------------------------------------------------------//
  ScreenBuilder dataLine (int row, String number, String text)
  // ---------------------------------------------------------------------------------//
  {
    return inputField (row, 1, 6, number).inputField (row, 8, 72, text);
  }

  /** Uma linha recem inserida pelo comando I, ainda em branco. */
  // ---------------------------------------------------------------------------------//
  ScreenBuilder insertLine (int row)
  // ---------------------------------------------------------------------------------//
  {
    return dataLine (row, "''''''", "");
  }

  /**
   * A mensagem curta do ISPF, no canto direito da primeira linha. Fica depois do
   * indicador de colunas na lista de campos, como na tela real, onde ela ocupa
   * essa mesma area.
   */
  // ---------------------------------------------------------------------------------//
  ScreenBuilder shortMessage (String text)
  // ---------------------------------------------------------------------------------//
  {
    return protectedField (0, 45, text);
  }
}
