package com.bytezone.plugins;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;

// -----------------------------------------------------------------------------------//
public class UploadStage
// -----------------------------------------------------------------------------------//
{
  private final Dialog<UploadContext> dialog;

  // Campos do formulario
  private final TextField filePathField = new TextField ();
  private final TextField datasetField = new TextField ();
  private final ComboBox<String> encodingCombo = new ComboBox<> ();
  private final TextField lreclField = new TextField ("80");
  private final RadioButton replaceRadio =
      new RadioButton ("Replace (apagar existente)");
  private final RadioButton appendRadio =
      new RadioButton ("Append (adicionar ao final)");
  private final CheckBox truncateCheck =
      new CheckBox ("Truncar linhas que excedam LRECL");
  private final CheckBox cobolCheck =
      new CheckBox ("Numeração COBOL (cols 1-6 e 73-80)");

  private File selectedFile;

  private final ButtonType btnOK =
      new ButtonType ("Upload", ButtonData.OK_DONE);
  private final ButtonType btnCancel =
      new ButtonType ("Cancelar", ButtonData.CANCEL_CLOSE);

  // ---------------------------------------------------------------------------------//
  public UploadStage ()
  // ---------------------------------------------------------------------------------//
  {
    dialog = new Dialog<> ();
    dialog.setTitle ("Upload Dataset");
    dialog.setHeaderText ("Configuração de Upload para o Mainframe");
    dialog.getDialogPane ().getButtonTypes ().addAll (btnOK, btnCancel);

    Font labelFont = Font.font ("Monospaced", 13);

    GridPane grid = new GridPane ();
    grid.setPadding (new Insets (10, 20, 10, 20));
    grid.setHgap (10);
    grid.setVgap (10);

    // --- Arquivo local ---
    Button browseBtn = new Button ("Procurar...");
    browseBtn.setOnAction (e -> browseFile ());
    filePathField.setPrefWidth (350);
    filePathField.setEditable (false);
    filePathField.setFont (labelFont);

    HBox fileBox = new HBox (5, filePathField, browseBtn);
    grid.add (new Label ("Arquivo local:"), 0, 0);
    grid.add (fileBox, 1, 0);

    // --- Dataset destino (informativo) ---
    datasetField.setPrefWidth (350);
    datasetField.setFont (labelFont);
    datasetField.setPromptText ("Detectado automaticamente da tela ISPF");
    datasetField.setEditable (false);
    grid.add (new Label ("Dataset destino:"), 0, 1);
    grid.add (datasetField, 1, 1);

    // --- Encoding ---
    encodingCombo.setItems (FXCollections.observableArrayList (
        "UTF-8", "ASCII (US-ASCII)", "ISO-8859-1 (Latin-1)"));
    encodingCombo.getSelectionModel ().selectFirst ();
    grid.add (new Label ("Encoding do arquivo:"), 0, 2);
    grid.add (encodingCombo, 1, 2);

    // --- LRECL ---
    lreclField.setPrefWidth (80);
    lreclField.setFont (labelFont);
    grid.add (new Label ("LRECL:"), 0, 3);
    grid.add (lreclField, 1, 3);

    // --- Modo Replace/Append ---
    ToggleGroup modeGroup = new ToggleGroup ();
    replaceRadio.setToggleGroup (modeGroup);
    appendRadio.setToggleGroup (modeGroup);
    replaceRadio.setSelected (true);
    HBox modeBox = new HBox (15, replaceRadio, appendRadio);
    grid.add (new Label ("Modo:"), 0, 4);
    grid.add (modeBox, 1, 4);

    // --- Opcoes ---
    truncateCheck.setSelected (true);
    grid.add (new Label ("Opções:"), 0, 5);
    grid.add (truncateCheck, 1, 5);
    grid.add (cobolCheck, 1, 6);

    dialog.getDialogPane ().setContent (grid);

    // Desabilita OK ate selecionar arquivo
    dialog.getDialogPane ().lookupButton (btnOK).setDisable (true);
    filePathField.textProperty ().addListener ((obs, oldVal, newVal) ->
        dialog.getDialogPane ().lookupButton (btnOK)
            .setDisable (newVal == null || newVal.trim ().isEmpty ()));

    // Converter resultado
    dialog.setResultConverter (btn ->
    {
      if (btn != btnOK || selectedFile == null)
        return null;

      int lrecl;
      try
      {
        lrecl = Integer.parseInt (lreclField.getText ().trim ());
      }
      catch (NumberFormatException e)
      {
        lrecl = 80;
      }

      Charset charset = parseCharset (
          encodingCombo.getSelectionModel ().getSelectedItem ());

      UploadContext.UploadMode mode = replaceRadio.isSelected ()
          ? UploadContext.UploadMode.REPLACE
          : UploadContext.UploadMode.APPEND;

      return new UploadContext (selectedFile, lrecl, charset,
          truncateCheck.isSelected (), cobolCheck.isSelected (), mode);
    });
  }

  // ---------------------------------------------------------------------------------//
  public void setDatasetName (String name)
  // ---------------------------------------------------------------------------------//
  {
    datasetField.setText (name);
  }

  // ---------------------------------------------------------------------------------//
  public Optional<UploadContext> showAndWait ()
  // ---------------------------------------------------------------------------------//
  {
    return dialog.showAndWait ();
  }

  // ---------------------------------------------------------------------------------//
  private void browseFile ()
  // ---------------------------------------------------------------------------------//
  {
    FileChooser chooser = new FileChooser ();
    chooser.setTitle ("Selecionar arquivo para upload");
    chooser.getExtensionFilters ().addAll (
        new FileChooser.ExtensionFilter ("Todos os arquivos", "*.*"),
        new FileChooser.ExtensionFilter ("Fonte COBOL", "*.cbl", "*.cob"),
        new FileChooser.ExtensionFilter ("JCL", "*.jcl"),
        new FileChooser.ExtensionFilter ("REXX", "*.rex", "*.rexx"),
        new FileChooser.ExtensionFilter ("Texto", "*.txt"));

    File file = chooser.showOpenDialog (dialog.getOwner ());
    if (file != null)
    {
      selectedFile = file;
      filePathField.setText (file.getAbsolutePath ());
    }
  }

  // ---------------------------------------------------------------------------------//
  private Charset parseCharset (String label)
  // ---------------------------------------------------------------------------------//
  {
    if (label == null)
      return StandardCharsets.UTF_8;
    if (label.startsWith ("ASCII"))
      return StandardCharsets.US_ASCII;
    if (label.startsWith ("ISO"))
      return StandardCharsets.ISO_8859_1;
    return StandardCharsets.UTF_8;
  }
}
