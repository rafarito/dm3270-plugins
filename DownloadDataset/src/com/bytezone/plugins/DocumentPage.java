package com.bytezone.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bytezone.dm3270.plugins.PluginData;
import com.bytezone.dm3270.plugins.PluginField;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentPage implements Comparable<DocumentPage>
{
  private static final Logger logger = LoggerFactory.getLogger (DocumentPage.class);

  private static final String START_DATA =
      "***************************** Top of Dat" + "a ******************************";
  private static final String END_DATA =
      "**************************** Bottom of D" + "ata ****************************";
  // getDatasetName () sabe ler os quatro cabecalhos; recusar BROWSE e VIEW aqui deixava
  // metade dessa logica inalcancavel.
  private static final Pattern EDIT_PATTERN = Pattern.compile (
      "(?i).*\\b(RFEEDIT|EDIT|BROWSE|VIEW)\\b.*");
  private static final Pattern p = Pattern.compile (
      "([A-Z0-9]{1,8}(\\.[A-Z0-9]{1,8})*)" // dataset name
          + "(\\([A-Z0-9]{1,8}\\))?"      // member name
          + "( - [0-9]{2}\\.[0-9]{2})?");  // editing data

  String datasetName;
  String memberName;
  String fullName;

  int firstLine = -1;
  int lastLine;

  boolean hasBeginning;
  boolean hasEnd;

  int leftColumn;
  int rightColumn;

  List<String> numbers = new ArrayList<> ();
  List<String> lines = new ArrayList<> ();

  public static DocumentPage createPage (PluginData data,
      List<PluginField> modifiableFields)
  {
    PluginField editField = findFieldMatching (EDIT_PATTERN, data);
    if (editField == null)
    {
      logger.warn ("Can't find editor mode field");
      return null;
    }

    PluginField commandLabelField = findFieldContaining ("command", data);
    PluginField commandInputField = getNextModifiableField (commandLabelField, data);
    if (commandLabelField == null || commandInputField == null)
    {
      logger.warn ("Can't find command field");
      return null;
    }

    if (editField.sequence > commandLabelField.sequence)
    {
      logger.warn ("Command field is before editor mode field");
      return null;
    }

    PluginField scrollLabelField = findFieldContaining ("scroll", data);
    PluginField scrollInputField = getNextModifiableField (scrollLabelField, data);
    if (scrollLabelField == null || scrollInputField == null
        || scrollLabelField.sequence < commandLabelField.sequence)
    {
      logger.warn ("Can't find valid scroll field");
      return null;
    }

    try
    {
      return new DocumentPage (data, modifiableFields);
    }
    catch (Exception e)
    {
      logger.error ("Error creating DocumentPage", e);
      return null;
    }
  }

  private DocumentPage (PluginData data, List<PluginField> modifiableFields)
  {
    getDatasetName (data);
    getColumns (data);

    // Build a list of number fields (column 1) and content fields (column > 6)
    // grouped by their row, so we can pair them correctly.
    List<PluginField> numberFields = new ArrayList<> ();
    List<PluginField> contentFields = new ArrayList<> ();

    for (PluginField sf : modifiableFields)
    {
      if (sf.location.column == 1)
      {
        if (sf.getLength () == 6 && sf.getFieldValue ().equals ("******"))
        {
          PluginField nextField = data.getField (sf.sequence + 1);
          if (nextField != null && nextField.isProtected
              && nextField.getLength () >= 72)
          {
            if (nextField.getFieldValue ().startsWith (START_DATA))
              hasBeginning = true;
            else if (nextField.getFieldValue ().startsWith (END_DATA))
              hasEnd = true;
          }
        }
        else
          numberFields.add (sf);
      }
      else if (sf.location.column > 6 && sf.location.column < 15)
      {
        contentFields.add (sf);
      }
    }

    // Pair numbers and lines by matching rows
    if (numberFields.size () == contentFields.size ())
    {
      // Sizes match — assume they are already paired in order
      for (int i = 0; i < numberFields.size (); i++)
      {
        numbers.add (numberFields.get (i).getFieldValue ());
        lines.add (contentFields.get (i).getFieldValue ());
      }
    }
    else
    {
      // Sizes don't match — pair by row number for robustness
      logger.warn ("Field count mismatch: {} numbers vs {} lines, pairing by row",
          numberFields.size (), contentFields.size ());

      // Index content fields by row for quick lookup
      java.util.Map<Integer, PluginField> contentByRow = new java.util.LinkedHashMap<> ();
      for (PluginField cf : contentFields)
        contentByRow.put (cf.location.row, cf);

      for (PluginField nf : numberFields)
      {
        PluginField cf = contentByRow.remove (nf.location.row);
        if (cf != null)
        {
          numbers.add (nf.getFieldValue ());
          lines.add (cf.getFieldValue ());
        }
        else
        {
          logger.warn ("No content field for number at row {}: {}",
              nf.location.row, nf.getFieldValue ());
        }
      }

      // Also handle content fields without numbers (e.g. marker lines)
      for (java.util.Map.Entry<Integer, PluginField> entry : contentByRow.entrySet ())
      {
        logger.warn ("Unpaired content field at row {}: {}",
            entry.getKey (), entry.getValue ().getFieldValue ());
      }
    }

    // Scan ALL screen fields (including protected) for Top/Bottom of Data
    // markers. In RPF, these markers are protected fields, not modifiable,
    // so the modifiable-fields loop above won't find them.
    if (!hasBeginning || !hasEnd)
    {
      for (PluginField sf : data.screenFields)
      {
        String val = sf.getFieldValue ();
        if (val == null)
          continue;
        if (!hasBeginning && val.contains ("Top of Data"))
          hasBeginning = true;
        if (!hasEnd && val.contains ("Bottom of Data"))
          hasEnd = true;
      }
    }

    if (numbers.size () > 0)
    {
      try
      {
        firstLine = Integer.parseInt (numbers.get (0).trim ());
        lastLine = Integer.parseInt (numbers.get (numbers.size () - 1).trim ());
      }
      catch (NumberFormatException e)
      {
        logger.warn ("Cannot parse line numbers: first='{}', last='{}'",
            numbers.get (0), numbers.get (numbers.size () - 1), e);
        firstLine = -1;
        lastLine = -1;
      }
    }
  }

  public boolean matches (DocumentPage otherPage)
  {
    if (otherPage == null)
      return false;

    if (leftColumn != otherPage.leftColumn || rightColumn != otherPage.rightColumn)
      return false;

    // Both pages have data lines - compare first line numbers
    if (firstLine >= 0 && otherPage.firstLine >= 0)
      return firstLine == otherPage.firstLine;

    // Both pages are empty (no data lines) - we're stuck
    if (firstLine < 0 && otherPage.firstLine < 0)
      return true;

    return false;
  }

  private void getDatasetName (PluginData data)
  {
    for (int i = 0; i < Math.min(25, data.screenFields.size()); i++)
    {
      String text = data.trimField (i);
      if (text == null || text.isEmpty()) continue;

      if (text.startsWith("RFEEDIT") || text.startsWith("EDIT") || 
          text.startsWith("BROWSE") || text.startsWith("VIEW"))
      {
        String[] parts = text.split("\\s+");
        if (parts.length >= 2)
        {
          text = parts[1];
          if (parts.length >= 4 && parts[2].equals("-"))
            text = text + " - " + parts[3];
        }
      }

      Matcher m = p.matcher (text);
      if (m.matches ())
      {
        if (text.contains(".") || text.contains("("))
        {
          datasetName = m.group (1);
          String name = m.group (3);
          if (name != null)
          {
            memberName = name.substring (1, name.length () - 1);
            fullName = String.format ("%s(%s)", datasetName, memberName);
          }
          else
          {
            memberName = "";
            fullName = datasetName;
          }
          return;
        }
      }
    }
    datasetName = "UNKNOWN";
    memberName = "";
    fullName = "UNKNOWN";
  }

  private void getColumns (PluginData data)
  {
    // "Columns 00001 00072" na tela cheia, "Col 1 72" quando o ISPF abrevia
    PluginField columnsField = findFieldContaining ("columns", data);
    if (columnsField == null)
      columnsField = findFieldContaining ("col", data);

    if (columnsField == null || columnsField.isModifiable)
      return;

    String text = columnsField.getFieldValue().trim().toLowerCase();
    
    String[] parts = text.split("\\s+");
    for (int i = 0; i < parts.length - 2; i++) {
        if (parts[i].equals("columns") || parts[i].equals("col")) {
            try {
                leftColumn = Integer.parseInt(parts[i+1]);
                rightColumn = Integer.parseInt(parts[i+2]);
                return;
            } catch (NumberFormatException e) {
                // fall through
            }
        }
    }

    String col1 = data.trimField (columnsField.sequence + 1);
    String col2 = data.trimField (columnsField.sequence + 2);

    try
    {
      leftColumn = Integer.parseInt (col1);
      rightColumn = Integer.parseInt (col2);
    }
    catch (NumberFormatException e)
    {
      logger.warn ("Bad column number format", e);
      leftColumn = 0;
      rightColumn = 0;
    }
  }

  private static PluginField findField (String text, PluginData data)
  {
    for (PluginField screenField : data.screenFields)
      if (text.equals (screenField.getFieldValue ().trim ()))
        return screenField;
    return null;
  }

  private static PluginField findFieldContaining (String text, PluginData data)
  {
    for (PluginField screenField : data.screenFields)
    {
      String value = screenField.getFieldValue ().trim ().toLowerCase ();
      if (value.contains (text))
        return screenField;
    }
    return null;
  }

  private static PluginField findFieldMatching (Pattern pattern, PluginData data)
  {
    for (PluginField screenField : data.screenFields)
      if (pattern.matcher (screenField.getFieldValue ().trim ()).matches ())
        return screenField;
    return null;
  }

  private static PluginField getNextModifiableField (PluginField field, PluginData data)
  {
    if (field == null)
      return null;

    PluginField nextField = data.getField (field.sequence + 1);
    if (nextField != null && nextField.isModifiable)
      return nextField;

    return null;
  }

  @Override
  public String toString ()
  {
    StringBuilder text = new StringBuilder ();

    text.append (String.format ("Dataset name .... %s%n", datasetName));
    text.append (String.format ("Member name ..... %s%n", memberName));
    text.append (String.format ("Lines ........... %d%n", lines.size ()));
    text.append (String.format ("First line ...... %d%n", firstLine));
    text.append (String.format ("Last line ....... %d%n", lastLine));
    text.append (String.format ("Has first ....... %s%n", hasBeginning));
    text.append (String.format ("Has last ........ %s%n", hasEnd));
    text.append (String.format ("Left column ..... %d%n", leftColumn));
    text.append (String.format ("Right column .... %d%n", rightColumn));
    text.append ("\n");

    int limit = Math.min (numbers.size (), lines.size ());
    for (int i = 0; i < limit; i++)
      text.append (String.format ("%s %s%n", numbers.get (i), lines.get (i)));
    // Append any remaining lines without numbers
    for (int i = limit; i < lines.size (); i++)
      text.append (String.format ("       %s%n", lines.get (i)));

    return text.toString ();
  }

  @Override
  public int compareTo (DocumentPage o)
  {
    if (leftColumn == o.leftColumn)
      return firstLine - o.firstLine;
    return leftColumn - o.leftColumn;
  }
}
