package com.bytezone.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import com.bytezone.dm3270.commands.AIDCommand;
import com.bytezone.dm3270.plugins.DefaultPlugin;
import com.bytezone.dm3270.plugins.PluginData;
import com.bytezone.dm3270.plugins.PluginField;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// -----------------------------------------------------------------------------------//
public class UploadDataset extends DefaultPlugin
// -----------------------------------------------------------------------------------//
{
  private static final Logger logger =
      LoggerFactory.getLogger (UploadDataset.class);

  private static final Pattern EDIT_PATTERN = Pattern.compile (
      "(?i).*EDIT\\b.*");

  private boolean doesAuto;
  private boolean doesRequest;

  private UploadContext context;
  private UploadState state = UploadState.IDLE;
  private UploadStage uploadStage;

  // ---------------------------------------------------------------------------------//
  enum UploadState
  // ---------------------------------------------------------------------------------//
  {
    IDLE,             // Esperando o usuario ativar
    DELETING,         // DELETE ALL enviado, esperando confirmacao
    GOING_BOTTOM,     // BOTTOM enviado (modo append), esperando tela
    INSERTING_CMD,    // Comando INSERT enviado, esperando linhas em branco
    FILLING_LINES,    // Preenchendo linhas em branco com conteudo
    SAVING,           // SAVE enviado, esperando confirmacao
    DONE              // Upload concluido
  }

  // ---------------------------------------------------------------------------------//
  @Override
  public void activate ()
  // ---------------------------------------------------------------------------------//
  {
    doesAuto = false;
    doesRequest = true;
  }

  // ---------------------------------------------------------------------------------//
  @Override
  public void deactivate ()
  // ---------------------------------------------------------------------------------//
  {
    doesAuto = false;
    doesRequest = false;
    state = UploadState.IDLE;
    context = null;
  }

  // ---------------------------------------------------------------------------------//
  @Override
  public boolean doesRequest ()
  // ---------------------------------------------------------------------------------//
  {
    return doesRequest;
  }

  // ---------------------------------------------------------------------------------//
  @Override
  public boolean doesAuto ()
  // ---------------------------------------------------------------------------------//
  {
    return doesAuto;
  }

  // ---------------------------------------------------------------------------------//
  @Override
  public void processRequest (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    // Verificar se estamos numa tela de EDIT do ISPF
    if (!isEditScreen (data))
    {
      showAlert (AlertType.WARNING,
          "Esta tela não parece ser o ISPF EDIT.\n"
          + "Navegue até o dataset no EDIT e tente novamente.");
      return;
    }

    // Detectar nome do dataset da tela
    String datasetName = detectDatasetName (data);

    // Mostrar dialogo de configuracao
    if (uploadStage == null)
      uploadStage = new UploadStage ();

    if (datasetName != null)
      uploadStage.setDatasetName (datasetName);

    Optional<UploadContext> result = uploadStage.showAndWait ();
    if (!result.isPresent ())
      return;

    context = result.get ();

    // Ler e preparar o arquivo
    try
    {
      List<String> errors = context.prepare ();
      if (!errors.isEmpty ())
      {
        showAlert (AlertType.ERROR,
            "Erros de validação:\n" + String.join ("\n", errors));
        context = null;
        return;
      }
    }
    catch (Exception e)
    {
      showAlert (AlertType.ERROR,
          "Erro ao ler arquivo: " + e.getMessage ());
      logger.error ("Erro ao ler arquivo para upload", e);
      context = null;
      return;
    }

    logger.info ("Upload iniciado: {} ({} linhas)",
        context.getLocalFile ().getName (), context.getTotalLines ());

    // Iniciar o fluxo: depende do modo
    PluginField commandField = findCommandField (data);
    if (commandField == null)
    {
      showAlert (AlertType.ERROR, "Campo de comando não encontrado na tela.");
      context = null;
      return;
    }

    if (context.getMode () == UploadContext.UploadMode.REPLACE)
    {
      // DELETE ALL NX: apaga tudo sem pedir confirmacao
      commandField.change ("DELETE ALL NX", data);
      data.setKey (AIDCommand.AID_ENTER);
      state = UploadState.DELETING;
    }
    else
    {
      // Modo APPEND: ir para o final do dataset
      commandField.change ("BOTTOM", data);
      data.setKey (AIDCommand.AID_ENTER);
      state = UploadState.GOING_BOTTOM;
    }

    doesAuto = true;
  }

  // ---------------------------------------------------------------------------------//
  @Override
  public void processAuto (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    logger.debug ("processAuto: state={}, remaining={}",
        state, context == null ? -1 : context.getLinesRemaining ());

    if (!isEditScreen (data))
    {
      logger.warn ("Tela nao e EDIT — abortando upload");
      abort ();
      return;
    }

    switch (state)
    {
      case DELETING:
        handlePostDelete (data);
        break;

      case GOING_BOTTOM:
        handlePostBottom (data);
        break;

      case INSERTING_CMD:
        handlePostInsertCmd (data);
        break;

      case FILLING_LINES:
        handleFillingLines (data);
        break;

      case SAVING:
        handlePostSave (data);
        break;

      default:
        logger.warn ("Estado inesperado: {}", state);
        abort ();
        break;
    }
  }

  // ---------------------------------------------------------------
  // Handlers de estado
  // ---------------------------------------------------------------

  // ---------------------------------------------------------------------------------//
  private void handlePostDelete (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    // Apos DELETE ALL, o dataset esta vazio.
    // Agora precisamos inserir as linhas.
    issueInsertCommand (data);
  }

  // ---------------------------------------------------------------------------------//
  private void handlePostBottom (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    // Estamos no final do dataset. Inserir linhas aqui.
    issueInsertCommand (data);
  }

  // ---------------------------------------------------------------------------------//
  private void issueInsertCommand (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    // Procura o ULTIMO campo de numero de linha (input, col ~1, len 6)
    // para inserir as proximas linhas apos o que ja foi digitado
    PluginField numberField = findLastNumberField (data);

    if (numberField == null)
    {
      // Se nao ha linhas (dataset vazio apos DELETE ALL), usar Command "I"
      PluginField commandField = findCommandField (data);
      if (commandField != null)
      {
        int blockSize = Math.min (context.getLinesRemaining (), 20);
        commandField.change ("I" + blockSize, data);
        data.setKey (AIDCommand.AID_ENTER);
        state = UploadState.INSERTING_CMD;
        return;
      }
      logger.error ("Nao encontrou campo de numero nem de comando");
      abort ();
      return;
    }

    int blockSize = Math.min (context.getLinesRemaining (), 20);
    numberField.change ("I" + blockSize, data);
    data.setKey (AIDCommand.AID_ENTER);
    state = UploadState.INSERTING_CMD;
  }

  // ---------------------------------------------------------------------------------//
  private void handlePostInsertCmd (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    // Apos o comando I<n>, o ISPF insere linhas em branco
    // Precisamos preenche-las com o conteudo do arquivo
    fillEmptyLines (data);
  }

  // ---------------------------------------------------------------------------------//
  private void handleFillingLines (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    if (context.isFinished ())
    {
      // Upload concluido — salvar
      PluginField commandField = findCommandField (data);
      if (commandField != null)
      {
        commandField.change ("SAVE", data);
        data.setKey (AIDCommand.AID_ENTER);
        state = UploadState.SAVING;
      }
      else
        abort ();
      return;
    }

    // Verificar se ha linhas em branco restantes na tela
    List<PluginField> emptyFields = findEmptyInsertLines (data);
    if (!emptyFields.isEmpty ())
    {
      // Ainda ha linhas em branco — preencher
      fillEmptyLines (data);
    }
    else
    {
      // Sem linhas em branco — emitir novo INSERT
      issueInsertCommand (data);
    }
  }

  // ---------------------------------------------------------------------------------//
  private void handlePostSave (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    logger.info ("Upload concluido! {} linhas enviadas para o mainframe",
        context.getLinesSent ());
    doesAuto = false;
    state = UploadState.DONE;
    context = null;
  }

  // ---------------------------------------------------------------
  // Logica de preenchimento de linhas
  // ---------------------------------------------------------------

  // ---------------------------------------------------------------------------------//
  private void fillEmptyLines (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    List<PluginField> emptyFields = findEmptyInsertLines (data);

    if (emptyFields.isEmpty ())
    {
      // Nenhuma linha em branco encontrada — pode ser que o INSERT
      // ainda nao foi processado ou layout diferente
      logger.warn ("Nenhuma linha em branco encontrada para preencher");
      data.setKey (AIDCommand.AID_ENTER);
      state = UploadState.FILLING_LINES;
      return;
    }

    // Pegar bloco de linhas do contexto
    List<String> block = context.getNextBlock (emptyFields.size ());

    for (int i = 0; i < block.size () && i < emptyFields.size (); i++)
    {
      emptyFields.get (i).change (block.get (i), data);
    }

    data.setKey (AIDCommand.AID_ENTER);
    state = UploadState.FILLING_LINES;

    logger.debug ("Preenchidas {} linhas (total enviado: {}/{})",
        block.size (), context.getLinesSent (), context.getTotalLines ());
  }

  // ---------------------------------------------------------------
  // Deteccao de campos na tela ISPF EDIT
  // ---------------------------------------------------------------

  /** Verifica se a tela atual e uma tela de EDIT do ISPF. */
  // ---------------------------------------------------------------------------------//
  boolean isEditScreen (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    for (int i = 0; i < Math.min (10, data.screenFields.size ()); i++)
    {
      String text = data.trimField (i);
      if (EDIT_PATTERN.matcher (text).matches ())
        return true;
    }
    return false;
  }

  /** Detecta o nome do dataset a partir dos campos da tela. */
  // ---------------------------------------------------------------------------------//
  String detectDatasetName (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    for (int i = 0; i < Math.min (5, data.screenFields.size ()); i++)
    {
      String text = data.trimField (i);
      if (text.startsWith ("EDIT") || text.startsWith ("RFEEDIT"))
      {
        String[] parts = text.split ("\\s+");
        if (parts.length >= 2)
          return parts[1];
      }
    }
    return null;
  }

  /** Encontra o campo "Command ===>" e retorna o campo de input seguinte. */
  // ---------------------------------------------------------------------------------//
  PluginField findCommandField (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    for (int i = 0; i < data.screenFields.size () - 1; i++)
    {
      PluginField field = data.screenFields.get (i);
      String value = field.getFieldValue ();
      if (value != null && value.trim ().startsWith ("Command"))
      {
        PluginField next = data.getField (i + 1);
        if (next != null && next.isModifiable)
          return next;
      }
    }
    return null;
  }

  /**
   * Encontra o ultimo campo de numero de linha (input, col ~1, len 6) na tela.
   * Campos marcadores (******) sao ignorados, exceto "Top of Data" que e
   * guardado como fallback para datasets vazios — o ISPF aceita o comando
   * I nessa linha mas rejeita em "Bottom of Data".
   * Ao usar a ultima linha, garantimos que blocos de inserts subsequentes
   * appendam linhas ao final da tela, e nao no meio dos dados.
   */
  // ---------------------------------------------------------------------------------//
  PluginField findLastNumberField (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    PluginField topOfDataField = null;
    PluginField lastDataField = null;

    for (PluginField field : data.screenFields)
    {
      if (!field.isProtected && field.location.column <= 1
          && field.getLength () == 6
          && field.location.row >= 2)  // Abaixo do header (row 0-1)
      {
        String value = field.getFieldValue () == null ? ""
            : field.getFieldValue ().trim ();

        if ("******".equals (value))
        {
          // Guardar o primeiro marcador (Top of Data) como fallback
          if (topOfDataField == null)
            topOfDataField = field;
          continue;
        }

        // Campo de numero regular (000100, 000200 etc.)
        lastDataField = field;
      }
    }

    if (lastDataField != null)
      return lastDataField;

    // Nenhuma linha de dados — usar Top of Data se disponivel
    return topOfDataField;
  }

  /**
   * Encontra linhas em branco inseridas pelo comando I.
   * No ISPF EDIT, linhas inseridas aparecem como campos de conteudo
   * vazios ou com espacos, na area de conteudo (col > 6, col < 15).
   */
  // ---------------------------------------------------------------------------------//
  List<PluginField> findEmptyInsertLines (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    List<PluginField> emptyLines = new ArrayList<> ();
    List<PluginField> modifiable = getModifiableFields (data);

    for (PluginField field : modifiable)
    {
      // Campos de conteudo: column > 6, column < 15
      if (field.location.column > 6 && field.location.column < 15
          && field.location.row >= 3)
      {
        String value = field.getFieldValue ();
        if (value == null || value.trim ().isEmpty ())
          emptyLines.add (field);
      }
    }

    return emptyLines;
  }

  // ---------------------------------------------------------------
  // Utilitarios
  // ---------------------------------------------------------------

  // ---------------------------------------------------------------------------------//
  private void abort ()
  // ---------------------------------------------------------------------------------//
  {
    logger.warn ("Upload abortado");
    doesAuto = false;
    state = UploadState.IDLE;
    context = null;
  }

  // ---------------------------------------------------------------------------------//
  UploadState getState ()
  // ---------------------------------------------------------------------------------//
  {
    return state;
  }

  // ---------------------------------------------------------------------------------//
  UploadContext getContext ()
  // ---------------------------------------------------------------------------------//
  {
    return context;
  }

  // ---------------------------------------------------------------------------------//
  void setState (UploadState state)
  // ---------------------------------------------------------------------------------//
  {
    this.state = state;
  }

  // ---------------------------------------------------------------------------------//
  void setContext (UploadContext context)
  // ---------------------------------------------------------------------------------//
  {
    this.context = context;
  }

  // ---------------------------------------------------------------------------------//
  void setDoesAuto (boolean value)
  // ---------------------------------------------------------------------------------//
  {
    this.doesAuto = value;
  }

  private BiConsumer<AlertType, String> alertHandler = (type, msg) ->
      Platform.runLater (() ->
      {
        Alert alert = new Alert (type, msg);
        alert.getDialogPane ().setHeaderText (null);
        alert.showAndWait ();
      });

  // ---------------------------------------------------------------------------------//
  void setAlertHandler (BiConsumer<AlertType, String> handler)
  // ---------------------------------------------------------------------------------//
  {
    this.alertHandler = handler;
  }

  // ---------------------------------------------------------------------------------//
  private void showAlert (AlertType type, String message)
  // ---------------------------------------------------------------------------------//
  {
    alertHandler.accept (type, message);
  }
}
