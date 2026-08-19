package com.bytezone.plugins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  /** Prefixo que o ISPF poe nas linhas inseridas e ainda nao confirmadas. */
  static final String INSERT_PREFIX = "''''''";

  /** Prefixo dos marcadores Top of Data / Bottom of Data. */
  static final String MARKER_PREFIX = "******";

  /** Primeira linha da tela que pode conter dados (0 = titulo, 1 = Command/Scroll). */
  private static final int FIRST_DATA_ROW = 2;

  /** Usado quando a tela nao permite deduzir quantas linhas cabem. */
  private static final int DEFAULT_BLOCK_SIZE = 20;

  /**
   * Quantas telas seguidas podem chegar sem que uma unica linha avance antes de
   * desistirmos. O ciclo normal gasta ate tres (POSITIONING, INSERTING_CMD e o
   * proprio SAVING), entao seis da margem sem deixar o plugin girar em falso.
   */
  private static final int MAX_STALLED_TICKS = 6;

  private boolean doesAuto;
  private boolean doesRequest;

  private UploadContext context;
  private UploadState state = UploadState.IDLE;
  private UploadStage uploadStage;

  private int stalledTicks;
  private int lastLinesSent;

  private List<String> currentBlock;
  private List<Integer> currentBlockRows;
  private int currentColOffset;

  // ---------------------------------------------------------------------------------//
  enum UploadState
  // ---------------------------------------------------------------------------------//
  {
    IDLE,             // Esperando o usuario ativar
    DELETING,         // DELETE ALL enviado, esperando confirmacao
    GOING_BOTTOM,     // BOTTOM enviado (modo append), esperando tela
    POSITIONING,      // LOCATE enviado para trazer a ancora ao topo da tela
    INSERTING_CMD,    // Comando INSERT enviado, esperando linhas em branco
    FILLING_LINES,    // Preenchendo linhas em branco com conteudo
    SCROLLING_RIGHT,  // Rolando a tela para a direita para textos longos
    SCROLLING_LEFT,   // Retornando a tela para a esquerda (LEFT MAX)
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
    currentBlock = null;
    currentBlockRows = null;
    currentColOffset = 0;
    resetProgress ();
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

    resetProgress ();
    currentBlock = null;
    currentBlockRows = null;
    currentColOffset = 0;

    if (context.getMode () == UploadContext.UploadMode.REPLACE)
    {
      // DELETE ALL NX: apaga tudo sem pedir confirmacao
      write (commandField, "DELETE ALL NX", data);
      data.setKey (AIDCommand.AID_ENTER);
      state = UploadState.DELETING;
    }
    else
    {
      // Modo APPEND: ir para o final do dataset
      write (commandField, "BOTTOM", data);
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
      abort ("A tela deixou de ser o ISPF EDIT.");
      return;
    }

    if (context == null)
    {
      abort ("Estado interno inconsistente: nenhum upload em andamento.");
      return;
    }

    if (!checkProgress ())
      return;

    switch (state)
    {
      case DELETING:
        handlePostDelete (data);
        break;

      case GOING_BOTTOM:
        handlePostBottom (data);
        break;

      case POSITIONING:
        handlePostPosition (data);
        break;

      case INSERTING_CMD:
        handlePostInsertCmd (data);
        break;

      case FILLING_LINES:
        handleFillingLines (data);
        break;

      case SCROLLING_RIGHT:
        handlePostScrollRight (data);
        break;

      case SCROLLING_LEFT:
        handlePostScrollLeft (data);
        break;

      case SAVING:
        handlePostSave (data);
        break;

      default:
        abort ("Estado inesperado da maquina de upload: " + state);
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
    String message = findShortMessage (data);
    if (isErrorMessage (message))
    {
      abort ("O ISPF recusou o DELETE ALL: " + message);
      return;
    }

    // O dataset ficou vazio: o Top of Data ja esta no topo da tela, entao da
    // para inserir direto, sem reposicionar.
    issueInsertCommand (data);
  }

  // ---------------------------------------------------------------------------------//
  private void handlePostBottom (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    // O BOTTOM deixa a ultima linha no rodape da tela, sem espaco abaixo dela
    // para as linhas inseridas aparecerem — reposicionar antes de inserir.
    repositionAndInsert (data);
  }

  // ---------------------------------------------------------------------------------//
  private void handlePostPosition (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    issueInsertCommand (data);
  }

  /**
   * Traz a ultima linha de dados para o topo do display com LOCATE, de modo que
   * o I<n> seguinte tenha a tela inteira livre abaixo da ancora. Sem isso o ISPF
   * insere linhas fora da area visivel e o preenchimento nao acha nada.
   */
  // ---------------------------------------------------------------------------------//
  private void repositionAndInsert (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    PluginField numberField = findLastNumberField (data);
    PluginField commandField = findCommandField (data);

    String lineNumber = numberField == null ? "" : trimmed (numberField);

    if (commandField == null || !lineNumber.matches ("\\d+"))
    {
      // Sem numero de linha para ancorar (dataset vazio, por exemplo): a tela ja
      // esta no lugar certo, entao insere de onde estamos.
      issueInsertCommand (data);
      return;
    }

    write (commandField, "LOCATE " + lineNumber, data);
    data.setKey (AIDCommand.AID_ENTER);
    state = UploadState.POSITIONING;
  }

  // ---------------------------------------------------------------------------------//
  private void issueInsertCommand (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    int lines = Math.min (context.getLinesRemaining (), blockSize (data));

    // Procura o ULTIMO campo de numero de linha (input, col ~1, len 6)
    // para inserir as proximas linhas apos o que ja foi digitado
    PluginField numberField = findLastNumberField (data);

    if (numberField != null)
    {
      write (numberField, "I" + lines, data);
      data.setKey (AIDCommand.AID_ENTER);
      state = UploadState.INSERTING_CMD;
      return;
    }

    // Se nao ha linhas (dataset vazio apos DELETE ALL), usar Command "I"
    PluginField commandField = findCommandField (data);
    if (commandField == null)
    {
      abort ("Não foi encontrado campo de número nem de comando para inserir.");
      return;
    }

    write (commandField, "I" + lines, data);
    data.setKey (AIDCommand.AID_ENTER);
    state = UploadState.INSERTING_CMD;
  }

  // ---------------------------------------------------------------------------------//
  private void handlePostInsertCmd (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    // Apos o comando I<n>, o ISPF insere linhas em branco
    // Precisamos preenche-las com o conteudo do arquivo
    List<PluginField> emptyFields = findEmptyInsertLines (data);

    if (emptyFields.isEmpty ())
    {
      // O INSERT nao produziu linhas visiveis. Devolve a tela e deixa a guarda
      // de progresso encerrar se isso se repetir.
      logger.warn ("Nenhuma linha em branco encontrada apos o INSERT");
      data.setKey (AIDCommand.AID_ENTER);
      state = UploadState.FILLING_LINES;
      return;
    }

    fillEmptyLines (data, emptyFields);
  }

  // ---------------------------------------------------------------------------------//
  private void handleFillingLines (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    if (context.isFinished () && currentBlock == null)
    {
      // Upload concluido — salvar
      issueSaveCommand (data);
      return;
    }

    if (currentBlock != null)
    {
      // Estamos no meio de um bloco rolando a tela horizontalmente.
      // Recuperar os campos usando as linhas salvas, pois o prefixo '''''' sumiu.
      List<PluginField> blockFields = new ArrayList<> ();
      List<PluginField> allContentFields = new ArrayList<> ();
      for (PluginField field : getModifiableFields (data))
      {
        if (field.location.column > 6 && field.location.column < 15)
          allContentFields.add (field);
      }
      for (Integer row : currentBlockRows)
      {
        for (PluginField field : allContentFields)
        {
          if (field.location.row == row)
          {
            blockFields.add (field);
            break;
          }
        }
      }

      fillEmptyLines (data, blockFields);
      return;
    }

    List<PluginField> emptyFields = findEmptyInsertLines (data);

    int remaining = context.getLinesRemaining ();
    int useful = Math.min (remaining, blockSize (data));

    // A cada ENTER o ISPF emenda UMA linha de continuacao depois da ultima linha
    // preenchida. Aceitar essa linha sozinha como se fosse um bloco faz o upload
    // andar a uma linha por ida-e-volta com o host — que era o comportamento
    // observado depois do primeiro bloco. So aproveitamos a tela quando ela
    // carrega pelo menos meio bloco, ou quando o que ha ali termina o arquivo.
    if (emptyFields.size () >= remaining || emptyFields.size () * 2 >= useful)
      fillEmptyLines (data, emptyFields);
    else
      repositionAndInsert (data);
  }

  // ---------------------------------------------------------------------------------//
  private void issueSaveCommand (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    PluginField commandField = findCommandField (data);
    if (commandField == null)
    {
      abort ("Campo de comando não encontrado para gravar (SAVE).");
      return;
    }

    write (commandField, "SAVE", data);
    data.setKey (AIDCommand.AID_ENTER);
    state = UploadState.SAVING;
  }

  // ---------------------------------------------------------------------------------//
  private void handlePostScrollRight (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    String message = findShortMessage (data);
    if (isErrorMessage (message))
    {
      abort ("O ISPF recusou a rolagem para a direita: " + message);
      return;
    }

    // Agora preenchemos o resto das colunas do bloco atual
    handleFillingLines (data);
  }

  // ---------------------------------------------------------------------------------//
  private void handlePostScrollLeft (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    String message = findShortMessage (data);
    if (isErrorMessage (message))
    {
      abort ("O ISPF recusou a rolagem para a esquerda: " + message);
      return;
    }

    // O bloco terminou de ser escrito. Limpa o bloco e segue adiante
    currentBlock = null;
    currentBlockRows = null;
    currentColOffset = 0;

    if (context.isFinished ())
    {
      issueSaveCommand (data);
    }
    else
    {
      repositionAndInsert (data);
    }
  }

  // ---------------------------------------------------------------------------------//
  private void handlePostSave (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    String message = findShortMessage (data);
    if (isErrorMessage (message))
    {
      abort ("O ISPF recusou o SAVE: " + message);
      return;
    }

    int sent = context.getLinesSent ();
    logger.info ("Upload concluido! {} linhas enviadas para o mainframe", sent);

    doesAuto = false;
    state = UploadState.DONE;
    context = null;
    currentBlock = null;
    currentBlockRows = null;
    currentColOffset = 0;
    resetProgress ();

    showAlert (AlertType.INFORMATION, String.format (
        "Upload concluído: %d linhas enviadas.%s", sent,
        message.isEmpty () ? "" : String.format ("%n%nISPF: %s", message)));
  }

  // ---------------------------------------------------------------
  // Logica de preenchimento de linhas
  // ---------------------------------------------------------------

  // ---------------------------------------------------------------------------------//
  private void fillEmptyLines (PluginData data, List<PluginField> emptyFields)
  // ---------------------------------------------------------------------------------//
  {
    if (currentBlock == null)
    {
      currentBlock = context.getNextBlock (emptyFields.size ());
      currentColOffset = 0;
      currentBlockRows = new ArrayList<> ();
      for (int i = 0; i < currentBlock.size () && i < emptyFields.size (); i++)
      {
        currentBlockRows.add (emptyFields.get (i).location.row);
      }
    }

    int fieldLength = emptyFields.isEmpty () ? 72 : emptyFields.get (0).getLength ();
    boolean needsScroll = false;

    // Calcular a coluna inicial real da tela do ISPF. O ISPF nao rola alem do LRECL.
    int lrecl = context.getLrecl ();
    int maxScrollOffset = Math.max (0, lrecl - fieldLength);
    int actualOffset = Math.min (currentColOffset, maxScrollOffset);

    for (int i = 0; i < currentBlock.size () && i < emptyFields.size (); i++)
    {
      String line = currentBlock.get (i);
      String chunk = "";

      if (line.length () > currentColOffset)
      {
        int end = Math.min (line.length (), actualOffset + fieldLength);
        chunk = line.substring (actualOffset, end);

        if (line.length () > currentColOffset + fieldLength)
          needsScroll = true;
      }

      // Sem padding: estas linhas foram recem inseridas pelo ISPF e estao
      // comprovadamente vazias, entao nao ha residuo para cobrir.
      emptyFields.get (i).change (chunk, data);
    }

    if (needsScroll)
    {
      PluginField commandField = findCommandField (data);
      if (commandField != null)
      {
        write (commandField, "RIGHT " + fieldLength, data);
        currentColOffset += fieldLength;
        data.setKey (AIDCommand.AID_ENTER);
        state = UploadState.SCROLLING_RIGHT;
        stalledTicks = 0; // Evitar abortar por stall durante a rolagem
      }
      else
      {
        abort ("Campo de comando não encontrado para rolar a tela (RIGHT).");
      }
    }
    else
    {
      // Fim do bloco atual
      if (currentColOffset > 0)
      {
        PluginField commandField = findCommandField (data);
        if (commandField != null)
        {
          write (commandField, "LEFT MAX", data);
          data.setKey (AIDCommand.AID_ENTER);
          state = UploadState.SCROLLING_LEFT;
          stalledTicks = 0; // Evitar abortar por stall durante a rolagem
        }
        else
        {
          abort ("Campo de comando não encontrado para rolar a tela (LEFT).");
        }
      }
      else
      {
        currentBlock = null;
        currentBlockRows = null;
        currentColOffset = 0;
        data.setKey (AIDCommand.AID_ENTER);
        state = UploadState.FILLING_LINES;
      }
    }

    logger.debug ("Preenchidas {} linhas (offset {}, total enviado: {}/{})",
        currentBlock == null ? 0 : currentBlock.size (), currentColOffset,
        context.getLinesSent (), context.getTotalLines ());
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
   * Le a mensagem curta do ISPF — o texto que aparece a direita nas duas
   * primeiras linhas da tela ("Member X saved", "Save error" etc). Devolve
   * string vazia quando nao ha mensagem: nem todo ISPF emite uma.
   */
  // ---------------------------------------------------------------------------------//
  String findShortMessage (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    String message = "";

    for (PluginField field : data.screenFields)
    {
      if (!field.isProtected || field.location.row >= FIRST_DATA_ROW
          || field.location.column < 40)
        continue;

      String value = trimmed (field);
      String lower = value.toLowerCase ();

      // Descarta os rotulos fixos do cabecalho do EDIT
      if (value.isEmpty () || lower.startsWith ("columns")
          || lower.startsWith ("col ") || lower.startsWith ("scroll"))
        continue;

      message = value;
    }

    return message;
  }

  // ---------------------------------------------------------------------------------//
  static boolean isErrorMessage (String message)
  // ---------------------------------------------------------------------------------//
  {
    String lower = message.toLowerCase ();
    return lower.contains ("error") || lower.contains ("invalid");
  }

  /**
   * Quantas linhas de dados cabem na area visivel, deduzido da maior linha
   * ocupada na tela — assim os modos de 32 e 43 linhas rendem blocos maiores em
   * vez de ficarem presos aos 20 de uma tela 24x80.
   *
   * Nunca desce abaixo de {@value #DEFAULT_BLOCK_SIZE}: numa tela esparsa (um
   * dataset recem esvaziado so tem os dois marcadores) a maior linha ocupada nao
   * diz nada sobre a altura real do terminal, e encolher o bloco ali seria
   * justamente recriar o upload linha a linha.
   */
  // ---------------------------------------------------------------------------------//
  int blockSize (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    int maxRow = 0;
    for (PluginField field : data.screenFields)
      if (field.location.row > maxRow)
        maxRow = field.location.row;

    return Math.max (maxRow - FIRST_DATA_ROW, DEFAULT_BLOCK_SIZE);
  }

  /**
   * Encontra o ultimo campo de numero de linha (input, col ~1, len 6) na tela.
   * Marcadores (******) e linhas de insercao pendente ('''''') sao ignorados —
   * ancorar o I<n> numa delas colocaria o bloco no lugar errado. O primeiro
   * marcador e guardado como fallback para datasets vazios: o ISPF aceita o
   * comando I no "Top of Data" mas o rejeita no "Bottom of Data".
   */
  // ---------------------------------------------------------------------------------//
  PluginField findLastNumberField (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    PluginField topOfDataField = null;
    PluginField lastDataField = null;

    for (PluginField field : data.screenFields)
    {
      if (!isPrefixField (field))
        continue;

      String value = trimmed (field);

      if (MARKER_PREFIX.equals (value))
      {
        // Guardar o primeiro marcador (Top of Data) como fallback
        if (topOfDataField == null)
          topOfDataField = field;
        continue;
      }

      if (INSERT_PREFIX.equals (value))
        continue;

      // Campo de numero regular (000100, 000200 etc.)
      lastDataField = field;
    }

    if (lastDataField != null)
      return lastDataField;

    // Nenhuma linha de dados — usar Top of Data se disponivel
    return topOfDataField;
  }

  /**
   * Encontra as linhas inseridas pelo comando I e ainda em branco. O criterio e
   * o prefixo '''''' da propria linha, e nao apenas "campo de conteudo vazio":
   * linhas em branco que ja existiam no dataset — ou que acabamos de gravar a
   * partir do arquivo — tambem tem conteudo vazio, e seriam sobrescritas com o
   * bloco seguinte, embaralhando a ordem do upload.
   */
  // ---------------------------------------------------------------------------------//
  List<PluginField> findEmptyInsertLines (PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    Map<Integer, String> prefixes = new HashMap<> ();
    List<PluginField> contentFields = new ArrayList<> ();

    for (PluginField field : getModifiableFields (data))
    {
      if (field.location.row < FIRST_DATA_ROW)
        continue;

      if (isPrefixField (field))
        prefixes.put (field.location.row, trimmed (field));
      else if (field.location.column > 6 && field.location.column < 15)
        contentFields.add (field);
    }

    List<PluginField> emptyLines = new ArrayList<> ();

    for (PluginField field : contentFields)
    {
      if (!INSERT_PREFIX.equals (prefixes.get (field.location.row)))
        continue;

      String value = field.getFieldValue ();
      if (value == null || value.trim ().isEmpty ())
        emptyLines.add (field);
    }

    return emptyLines;
  }

  // ---------------------------------------------------------------
  // Utilitarios
  // ---------------------------------------------------------------

  /** Campo de prefixo de linha: input de 6 posicoes na margem esquerda. */
  // ---------------------------------------------------------------------------------//
  private static boolean isPrefixField (PluginField field)
  // ---------------------------------------------------------------------------------//
  {
    return !field.isProtected && field.location.column <= 1
        && field.getLength () == 6 && field.location.row >= FIRST_DATA_ROW;
  }

  // ---------------------------------------------------------------------------------//
  private static String trimmed (PluginField field)
  // ---------------------------------------------------------------------------------//
  {
    String value = field.getFieldValue ();
    return value == null ? "" : value.trim ();
  }

  /**
   * Escreve num campo completando com espacos ate o tamanho exato dele.
   * {@code PluginsStage.processReply} grava com {@code Field.setText(byte[])},
   * que nao apaga o campo antes: sem o preenchimento, um "I20" sobre um prefixo
   * "000300" chegaria ao ISPF como "I20300".
   */
  // ---------------------------------------------------------------------------------//
  static void write (PluginField field, String text, PluginData data)
  // ---------------------------------------------------------------------------------//
  {
    int length = field.getLength ();
    String value = text.length () > length ? text.substring (0, length)
        : text + " ".repeat (length - text.length ());

    field.change (value, data);
  }

  /**
   * Conta as telas que chegaram sem que nenhuma linha avancasse. Sem isso, um
   * INSERT que nao produz linhas em branco faz o plugin alternar entre estados
   * batendo ENTER indefinidamente.
   *
   * @return false quando o upload foi abortado por falta de progresso
   */
  // ---------------------------------------------------------------------------------//
  private boolean checkProgress ()
  // ---------------------------------------------------------------------------------//
  {
    int sent = context.getLinesSent ();

    if (sent > lastLinesSent)
    {
      lastLinesSent = sent;
      stalledTicks = 0;
      return true;
    }

    if (++stalledTicks >= MAX_STALLED_TICKS)
    {
      abort (String.format (
          "O upload parou de progredir (%d telas sem avançar).",
          MAX_STALLED_TICKS));
      return false;
    }

    return true;
  }

  // ---------------------------------------------------------------------------------//
  private void resetProgress ()
  // ---------------------------------------------------------------------------------//
  {
    stalledTicks = 0;
    lastLinesSent = 0;
  }

  // ---------------------------------------------------------------------------------//
  private void abort (String reason)
  // ---------------------------------------------------------------------------------//
  {
    logger.warn ("Upload abortado: {}", reason);

    StringBuilder message = new StringBuilder (reason);

    if (context != null)
    {
      message.append (String.format ("%n%nLinhas enviadas: %d de %d.",
          context.getLinesSent (), context.getTotalLines ()));

      if (context.getMode () == UploadContext.UploadMode.REPLACE)
        message.append (String.format ("%nO DELETE ALL já tinha sido executado — "
            + "use CANCEL no ISPF para descartar a sessão de edição."));
    }

    doesAuto = false;
    state = UploadState.IDLE;
    context = null;
    currentBlock = null;
    currentBlockRows = null;
    currentColOffset = 0;
    resetProgress ();

    showAlert (AlertType.ERROR, message.toString ());
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
    resetProgress ();
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
