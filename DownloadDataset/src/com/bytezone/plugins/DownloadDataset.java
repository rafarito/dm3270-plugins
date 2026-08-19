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

  private boolean pendingBottomRight;
  private boolean[][] visitedPages;
  private int unvisitedPages = -1;

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
    pendingBottomRight = false;

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

    if (page.hasEnd)
    {
      data.key = AIDCommand.AID_PF11;
      setMax (data);
      pendingBottomRight = true;
    }
    else
    {
      data.key = AIDCommand.AID_PF8;
    }
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

    if (pendingBottomRight)
    {
      pendingBottomRight = false;
      prepareVisitorGrid (page.lastLine, page.rightColumn);
    }

    // If page has no data lines, we've scrolled past the content
    if (page.lines.isEmpty ())
    {
      logger.info ("Empty page detected - done scrolling");
      doesAuto = false;
      saveDocument ();
      return;
    }

    if (page.matches (previousPage))
    {
      logger.info ("We're done");
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

      if (page.leftColumn != 1)        // this could loop
      {
        data.key = AIDCommand.AID_PF10;
        setMax (data);
        return;
      }

      setCurrentDocument (page);
    }
    else
      currentDocument.addDocumentPage (page);

    logger.debug ("{}", currentDocument);

    logger.debug ("Where to now?");
    // scroll to next page
    if (page.leftColumn == 1)
    {
      if (page.hasEnd)
      {
        data.key = AIDCommand.AID_PF11;       // go max right
        setMax (data);
        logger.debug ("go right max");
        pendingBottomRight = true;
        return;
      }
      else
      {
        data.key = AIDCommand.AID_PF8;        // go down
        logger.debug ("go down");
        return;
      }
    }
    else
    {
      if (page.hasBeginning)
      {
        data.key = AIDCommand.AID_PF10;       // go left (assumes only one circuit)
        setMax (data);
        doesAuto = false;
        logger.debug ("go left max");
        saveDocument ();
        return;
      }
      else
      {
        data.key = AIDCommand.AID_PF7;        // go up
        logger.debug ("go up");
        return;
      }
    }
  }

  // O "m" (MAX) pertence ao campo de scroll: escrito na linha de comando ele nao rola
  // nada e ainda deixa lixo no campo que o ISPF vai interpretar como comando.
  private void setMax (PluginData data)
  {
    PluginField scrollLabel = data.getField ("Scroll ===>");
    if (scrollLabel == null)
      return;

    PluginField scrollInput = data.getField (scrollLabel.sequence + 1);
    if (scrollInput != null && scrollInput.isModifiable)
      scrollInput.change ("m");
  }

  private void prepareVisitorGrid (int rows, int columns)
  {
    // divide these by the page size
    int pageRows = (rows - 1) / 20 + 1;
    int pageColumns = (columns - 1) / 72 + 1;

    currentDocument.maxColumns = columns;
    currentDocument.totalLines = rows;
    visitedPages = new boolean[pageRows][pageColumns];
    for (int i = 0; i < pageRows; i++)
      visitedPages[i][0] = true;
    visitedPages[pageRows - 1][pageColumns - 1] = true;
    int visitedPagesCount = pageRows + (pageColumns > 1 ? 1 : 0);
    unvisitedPages = pageRows * pageColumns - visitedPagesCount;

    logger.debug ("Grid {} rows x {} columns", pageRows, pageColumns);
    logger.debug ("Visited: {}, unvisited: {}", visitedPagesCount, unvisitedPages);
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
