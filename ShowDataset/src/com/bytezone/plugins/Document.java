package com.bytezone.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Document
{
  private static final Logger logger = LoggerFactory.getLogger (Document.class);

  final String datasetName;
  final String memberName;
  int maxColumns;
  int totalLines;

  List<DocumentPage> pages = new ArrayList<> ();
  List<Line> lines = new ArrayList<> ();

  public Document (DocumentPage page)
  {
    datasetName = page.datasetName;
    memberName = page.memberName;

    addDocumentPage (page);
  }

  public void addDocumentPage (DocumentPage page)
  {
    assert datasetName.equals (page.datasetName);
    assert memberName.equals (page.memberName);

    // Invalidate cached stitched lines since pages are changing
    lines.clear ();

    boolean found = false;
    int index = 0;
    for (DocumentPage dp : pages)
    {
      if (dp.matches (page))
      {
        found = true;
        break;
      }
      index++;
    }

    if (!found)
    {
      pages.add (page);
      logger.debug ("adding");
    }
    else
    {
      pages.set (index, page);
      logger.debug ("replacing");
    }

    logger.debug ("{}", page);
  }

  public List<Line> getLines ()
  {
    if (lines.size () == 0)
      stitch ();
    return lines;
  }

  private void stitch ()
  {
    int lineNo = 0;
    Collections.sort (pages);

    for (DocumentPage page : pages)
    {
      if (page.rightColumn > maxColumns)
        maxColumns = page.rightColumn;

      if (page.leftColumn == 1)
      {
        int limit = Math.min (page.lines.size (), page.numbers.size ());
        for (int i = 0; i < limit; i++)
        {
          String number = page.numbers.get (i);
          Line line = new Line ();
          line.text = String.format ("%s %s", number, page.lines.get (i));
          line.leftColumn = page.leftColumn;
          line.rightColumn = page.rightColumn;
          lines.add (line);
        }
      }
      else
      {
        int col = page.leftColumn + 6;
        String format = "%-" + col + "." + col + "s%s";
        for (String text : page.lines)
        {
          if (lineNo >= lines.size ())
          {
            logger.warn ("stitch: lineNo {} exceeds lines size {}, skipping",
                lineNo, lines.size ());
            break;
          }
          Line line = lines.get (lineNo++);
          line.text = String.format (format, line.text, text);
          line.rightColumn = page.rightColumn;
        }
      }
    }
  }

  @Override
  public String toString ()
  {
    StringBuilder text = new StringBuilder ();

    text.append (String.format ("Dataset name ... %s%n", datasetName));
    text.append (String.format ("Member name .... %s%n", memberName));
    text.append (String.format ("Lines .......... %d%n", totalLines));
    text.append (String.format ("Columns ........ %d", maxColumns));

    return text.toString ();
  }

  class Line
  {
    String text;
    int leftColumn;
    int rightColumn;

    @Override
    public String toString ()
    {
      return text;
    }
  }
}