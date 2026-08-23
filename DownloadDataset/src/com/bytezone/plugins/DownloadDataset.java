package com.bytezone.plugins;

import java.io.File;
import java.io.PrintWriter;
import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;

import com.bytezone.dm3270.commands.AIDCommand;
import com.bytezone.dm3270.plugins.DefaultPlugin;
import com.bytezone.dm3270.plugins.PluginData;
import com.bytezone.dm3270.plugins.PluginField;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.stage.FileChooser;

public class DownloadDataset extends DefaultPlugin
{
  private static final Logger logger = LoggerFactory.getLogger (DownloadDataset.class);

  private final Map<String, Document> documents = new TreeMap<> ();
  private Document currentDocument;
  private boolean doesAuto;
  private boolean doesRequest;

  private DocumentPage previousPage;

  private boolean probingLrecl;
  private boolean returningFromProbe;
  private int detectedLrecl = -1;
  private boolean scanningDown = true;

  private BiConsumer<AlertType, String> alertHandler = (type, msg) ->
      Platform.runLater (() ->
      {
        Alert alert = new Alert (type, msg);
        alert.getDialogPane ().setHeaderText (null);
        alert.showAndWait ();
      });

  void setAlertHandler (BiConsumer<AlertType, String> handler)
  {
    this.alertHandler = handler;
  }

  private void showAlert (AlertType type, String message)
  {
    if (alertHandler != null)
      alertHandler.accept (type, message);
  }

  private void abort (String reason)
  {
    logger.warn ("Download abortado: {}", reason);
    doesAuto = false;
    doesRequest = false;
    showAlert (AlertType.ERROR, reason);
  }

  @Override
  public void activate ()
  {
    doesAuto = false;
    doesRequest = true;
  }

  @Override
  public void deactivate ()
  {
    doesAuto = false;
    doesRequest = false;
  }

  @Override
  public boolean doesRequest ()
  {
    return doesRequest;
  }

  @Override
  public boolean doesAuto ()
  {
    return doesAuto;
  }

  @Override
  public void processRequest (PluginData data)
  {
    currentDocument = null;
    previousPage = null;
    probingLrecl = false;
    returningFromProbe = false;
    detectedLrecl = -1;
    scanningDown = true;

    DocumentPage page = DocumentPage.createPage (data, getModifiableFields (data));
    if (page == null)
    {
      abort ("A tela atual não parece conter um documento ou dataset suportado.");
      return;
    }

    // Remove any previously cached version so we get a fresh collection
    documents.remove (page.fullName);

    if (page.firstLine != 1)
    {
      data.key = AIDCommand.AID_PF7;
      setMax (data);
      doesAuto = true;
      return;
    }

    if (page.leftColumn != 1)
    {
      data.key = AIDCommand.AID_PF10;
      setMax (data);
      doesAuto = true;
      return;
    }

    setCurrentDocument (page);

    // Sondagem de LRECL: rola ao maximo para a direita para descobrir a ultima coluna
    data.key = AIDCommand.AID_PF11;
    setMax (data);
    probingLrecl = true;
    doesAuto = true;
  }

  @Override
  public void processAuto (PluginData data)
  {
    DocumentPage page = DocumentPage.createPage (data, getModifiableFields (data));
    if (page == null)
    {
      abort ("A tela mudou inesperadamente e não é mais reconhecida como um documento.");
      return;
    }


    // --- Resposta da sondagem de LRECL ---
    if (probingLrecl)
    {
      probingLrecl = false;
      detectedLrecl = page.rightColumn;

      if (page.leftColumn == 1)
      {
        // PF11 MAX nao rolou — LRECL cabe na area visivel
        logger.info ("LRECL detectado: {} (sem scroll horizontal)", detectedLrecl);

        if (page.hasEnd)
        {
          doesAuto = false;
          saveDocument ();
          return;
        }
        setPage (data);
        data.key = AIDCommand.AID_PF8;
        return;
      }

      // LRECL largo detectado — voltar para a coluna 1
      logger.info ("LRECL detectado: {} (scroll horizontal necessario)", detectedLrecl);
      data.key = AIDCommand.AID_PF10;
      setMax (data);
      returningFromProbe = true;
      return;
    }

    // --- Retornando da sondagem de LRECL ---
    if (returningFromProbe)
    {
      returningFromProbe = false;

      if (page.leftColumn != 1)
      {
        data.key = AIDCommand.AID_PF10;
        setMax (data);
        returningFromProbe = true;
        return;
      }

      // De volta na coluna 1 — adiciona pagina e decide direcao
      currentDocument.addDocumentPage (page);
      previousPage = page;

      if (page.hasEnd)
      {
        advanceRight (data);
        return;
      }

      setPage (data);
      data.key = AIDCommand.AID_PF8;
      return;
    }

    // --- Varredura normal ---

    if (page.lines.isEmpty ())
    {
      logger.info ("Pagina vazia detectada");
      if (needsMoreColumns (page))
      {
        advanceRight (data);
        return;
      }
      doesAuto = false;
      saveDocument ();
      return;
    }

    if (page.matches (previousPage))
    {
      logger.info ("Pagina repetida");
      if (needsMoreColumns (page))
      {
        advanceRight (data);
        return;
      }
      doesAuto = false;
      saveDocument ();
      return;
    }

    previousPage = page;

    if (currentDocument == null)
    {
      if (page.firstLine != 1)
      {
        abort ("O documento não está posicionado no início (linha 1). Role para o topo e tente novamente.");
        return;
      }

      if (page.leftColumn != 1)
      {
        data.key = AIDCommand.AID_PF10;
        setMax (data);
        return;
      }

      setCurrentDocument (page);

      // Sondagem de LRECL
      data.key = AIDCommand.AID_PF11;
      setMax (data);
      probingLrecl = true;
      return;
    }
    else
      currentDocument.addDocumentPage (page);

    logger.debug ("{}", currentDocument);

    // --- Navegacao em serpentina ---
    if (scanningDown)
    {
      if (page.hasEnd)
      {
        if (needsMoreColumns (page))
        {
          advanceRight (data);
          return;
        }
        doesAuto = false;
        saveDocument ();
        return;
      }
      data.key = AIDCommand.AID_PF8;
      return;
    }
    else
    {
      if (page.hasBeginning)
      {
        if (needsMoreColumns (page))
        {
          advanceRight (data);
          return;
        }
        doesAuto = false;
        saveDocument ();
        return;
      }
      data.key = AIDCommand.AID_PF7;
      return;
    }
  }

  // O "m" (MAX) pertence ao campo de scroll: escrito na linha de comando ele nao rola
  // nada e ainda deixa lixo no campo que o ISPF vai interpretar como comando.
  private void setScroll (PluginData data, String amount)
  {
    PluginField scrollLabel = data.getField ("Scroll ===>");
    if (scrollLabel == null)
      return;

    PluginField scrollInput = data.getField (scrollLabel.sequence + 1);
    if (scrollInput != null && scrollInput.isModifiable)
      scrollInput.change (amount);
  }

  private void setMax (PluginData data)
  {
    setScroll (data, "m");
  }

  // O campo Scroll e "pegajoso" no ISPF: uma vez em "m" (MAX), PF7/8/10/11
  // seguintes continuam pulando no maximo ate o campo ser trocado de volta.
  private void setPage (PluginData data)
  {
    setScroll (data, "page");
  }

  private boolean needsMoreColumns (DocumentPage page)
  {
    return detectedLrecl > 0 && page.rightColumn < detectedLrecl;
  }

  private void advanceRight (PluginData data)
  {
    setPage (data);
    scanningDown = !scanningDown;
    data.key = AIDCommand.AID_PF11;
    logger.debug ("Avancando para a direita, proxima direcao: {}",
        scanningDown ? "descendo" : "subindo");
  }

  // A captura termina aqui, e ela nao pode depender do toolkit do JavaFX: quem escolhe
  // onde gravar e uma etapa separada, trocavel em teste por writeTo ().
  private void saveDocument ()
  {
    if (currentDocument == null)
    {
      abort ("Ocorreu um erro: o documento interno está vazio após o download.");
      return;
    }

    Document document = currentDocument;
    logger.info ("Preparando para salvar o documento: {}", document.datasetName);
    documentSaver.accept (document);
  }

  // O nome sugerido no dialogo: o membro quando existe, senao o dataset.
  static String suggestedFileName (Document document)
  {
    String name = document.memberName.isEmpty () ? document.datasetName
        : document.memberName;

    return name + ".txt";
  }

  // Grava uma linha por registro. Visivel ao teste para que o formato do arquivo seja
  // verificavel sem abrir dialogo nenhum.
  static void writeTo (Document document, File file) throws IOException
  {
    try (PrintWriter writer = new PrintWriter (file))
    {
      for (Document.Line line : document.getLines ())
        writer.println (line.toString ());

      logger.info ("Documento salvo em: {}", file.getAbsolutePath ());
    }
  }

  private Consumer<Document> documentSaver = document -> Platform.runLater ( () ->
  {
    FileChooser fileChooser = new FileChooser ();
    fileChooser.setTitle ("Salvar Dataset");
    fileChooser.setInitialFileName (suggestedFileName (document));

    File file = fileChooser.showSaveDialog (null);
    if (file != null)
    {
      try
      {
        writeTo (document, file);
        showAlert (AlertType.INFORMATION, "Dataset salvo com sucesso em:\n" + file.getAbsolutePath ());
      }
      catch (IOException ex)
      {
        logger.error ("Erro ao salvar documento: {}", ex.getMessage (), ex);
        showAlert (AlertType.ERROR, "Erro ao gravar o arquivo localmente:\n" + ex.getMessage ());
      }
    }
  });

  void setDocumentSaver (Consumer<Document> documentSaver)
  {
    this.documentSaver = documentSaver;
  }

  private void setCurrentDocument (DocumentPage page)
  {
    String name = page.fullName;
    if (documents.containsKey (name))
    {
      currentDocument = documents.get (name);
      currentDocument.addDocumentPage (page);
    }
    else
    {
      currentDocument = new Document (page);
      documents.put (name, currentDocument);
    }
  }
}
