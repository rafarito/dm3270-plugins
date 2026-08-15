package com.bytezone.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// -----------------------------------------------------------------------------------//
public class UploadContext
// -----------------------------------------------------------------------------------//
{
  // ---------------------------------------------------------------------------------//
  public enum UploadMode
  // ---------------------------------------------------------------------------------//
  {
    REPLACE, APPEND
  }

  private final File localFile;
  private final int lrecl;
  private final Charset encoding;
  private final boolean truncateLines;
  private final boolean cobolNumbering;
  private final UploadMode mode;

  private List<String> lines;              // linhas preparadas
  private int nextLineIndex = 0;           // proxima linha a enviar

  // ---------------------------------------------------------------------------------//
  public UploadContext (File localFile, int lrecl, Charset encoding,
      boolean truncateLines, boolean cobolNumbering, UploadMode mode)
  // ---------------------------------------------------------------------------------//
  {
    this.localFile = localFile;
    this.lrecl = lrecl;
    this.encoding = encoding;
    this.truncateLines = truncateLines;
    this.cobolNumbering = cobolNumbering;
    this.mode = mode;
  }

  /**
   * Le o arquivo e prepara as linhas para upload.
   *
   * @return lista de erros de validacao (vazia se OK)
   */
  // ---------------------------------------------------------------------------------//
  public List<String> prepare () throws IOException
  // ---------------------------------------------------------------------------------//
  {
    List<String> errors = new ArrayList<> ();
    List<String> rawLines = Files.readAllLines (localFile.toPath (), encoding);
    lines = new ArrayList<> (rawLines.size ());

    for (int i = 0; i < rawLines.size (); i++)
    {
      String line = rawLines.get (i);

      if (cobolNumbering)
        line = stripCobolNumbering (line);

      if (line.length () > lrecl)
      {
        if (truncateLines)
          line = line.substring (0, lrecl);
        else
        {
          errors.add (String.format (
              "Linha %d excede LRECL (%d): comprimento %d",
              i + 1, lrecl, line.length ()));
        }
      }

      lines.add (line);
    }

    return errors;
  }

  // ---------------------------------------------------------------------------------//
  String stripCobolNumbering (String line)
  // ---------------------------------------------------------------------------------//
  {
    if (line.length () <= 6)
      return "";

    // Remove colunas 1-6 (numeracao) e 73-80 (identificacao)
    String content = line.substring (6);
    if (content.length () > 66)
      content = content.substring (0, 66);

    return content;
  }

  /** Retorna o proximo bloco de N linhas a enviar. */
  // ---------------------------------------------------------------------------------//
  public List<String> getNextBlock (int blockSize)
  // ---------------------------------------------------------------------------------//
  {
    if (isFinished ())
      return Collections.emptyList ();

    int end = Math.min (nextLineIndex + blockSize, lines.size ());
    List<String> block = lines.subList (nextLineIndex, end);
    nextLineIndex = end;
    return new ArrayList<> (block);
  }

  /** Verdadeiro quando todas as linhas foram consumidas. */
  // ---------------------------------------------------------------------------------//
  public boolean isFinished ()
  // ---------------------------------------------------------------------------------//
  {
    return lines == null || nextLineIndex >= lines.size ();
  }

  // ---------------------------------------------------------------------------------//
  public int getTotalLines ()
  // ---------------------------------------------------------------------------------//
  {
    return lines == null ? 0 : lines.size ();
  }

  // ---------------------------------------------------------------------------------//
  public int getLinesRemaining ()
  // ---------------------------------------------------------------------------------//
  {
    return lines == null ? 0 : lines.size () - nextLineIndex;
  }

  // ---------------------------------------------------------------------------------//
  public int getLinesSent ()
  // ---------------------------------------------------------------------------------//
  {
    return nextLineIndex;
  }

  // ---------------------------------------------------------------------------------//
  public UploadMode getMode ()
  // ---------------------------------------------------------------------------------//
  {
    return mode;
  }

  // ---------------------------------------------------------------------------------//
  public File getLocalFile ()
  // ---------------------------------------------------------------------------------//
  {
    return localFile;
  }

  // ---------------------------------------------------------------------------------//
  public int getLrecl ()
  // ---------------------------------------------------------------------------------//
  {
    return lrecl;
  }

  // ---------------------------------------------------------------------------------//
  @Override
  public String toString ()
  // ---------------------------------------------------------------------------------//
  {
    return String.format (
        "UploadContext[file=%s, lrecl=%d, mode=%s, total=%d, sent=%d, remaining=%d]",
        localFile.getName (), lrecl, mode, getTotalLines (),
        getLinesSent (), getLinesRemaining ());
  }
}
