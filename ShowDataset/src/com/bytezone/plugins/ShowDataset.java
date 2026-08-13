package com.bytezone.plugins;

import java.util.Map;
import java.util.TreeMap;

import com.bytezone.dm3270.commands.AIDCommand;
import com.bytezone.dm3270.plugins.DefaultPlugin;
import com.bytezone.dm3270.plugins.PluginData;
import com.bytezone.dm3270.plugins.PluginField;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShowDataset extends DefaultPlugin
{
  private static final Logger logger = LoggerFactory.getLogger (ShowDataset.class);

  private final Map<String, Document> documents = new TreeMap<> ();
  private final DatasetStage datasetStage = new DatasetStage ();
  private Document currentDocument;
  private boolean doesAuto;
  private boolean doesRequest;

  private int loopCount;
  private final int maxLoops = 20;
  private DocumentPage previousPage;

  private boolean pendingBottomRight;
  private boolean[][] visitedPages;
  private int unvisitedPages = -1;

  @Override
  public void activate ()
  {
    doesAuto = false;
    doesRequest = true;
  }

  @Override
  public void deactivate ()
  {
    if (datasetStage != null)
      datasetStage.hide ();

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
    loopCount = 0;
    pendingBottomRight = false;

    DocumentPage page = DocumentPage.createPage (data, getModifiableFields (data));
    if (page == null)
    {
      logger.warn ("Not a document page");
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
    logger.debug ("Loopcount {}", loopCount);
    if (++loopCount > maxLoops)
    {
      logger.warn ("loop count exceeded");
      doesAuto = false;
      showDocument ();
      return;
    }

    DocumentPage page = DocumentPage.createPage (data, getModifiableFields (data));
    if (page == null)
    {
      logger.warn ("Not a document page");
      doesAuto = false;
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
      showDocument ();
      return;
    }

    if (page.matches (previousPage))
    {
      logger.info ("We're done");
      doesAuto = false;
      showDocument ();
      return;
    }

    previousPage = page;

    if (currentDocument == null)
    {
      if (page.firstLine != 1)
      {
        logger.warn ("Not at document first document line");
        doesAuto = false;
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
        datasetStage.setDocument (currentDocument);
        datasetStage.show ();
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

  private void setMax (PluginData data)
  {
    PluginField commandField = data.getField ("Command ===>");
    if (commandField != null)
    {
      PluginField inputField = data.getField (commandField.sequence + 1);
      inputField.change ("m");
    }
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
    int visitedPages = pageRows + (pageColumns > 1 ? 1 : 0);
    unvisitedPages = pageRows * pageColumns - visitedPages;

    logger.debug ("Grid {} rows x {} columns", pageRows, pageColumns);
    logger.debug ("Visited: {}, unvisited: {}", visitedPages, unvisitedPages);
  }

  private void showDocument ()
  {
    if (currentDocument != null)
    {
      logger.info ("Showing document window");
      datasetStage.setDocument (currentDocument);
      datasetStage.show ();
    }
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