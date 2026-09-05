package com.bytezone.plugins;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Timer;
import java.util.concurrent.ScheduledExecutorService;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * A afirmacao central do trabalho e que a automacao nao depende de pausa de duracao
 * fixa: cada passo e disparado pela chegada da tela seguinte, ou seja, pelo evento de
 * restauracao de teclado que o computador central emite no caractere de controle de
 * escrita. Enquanto essa afirmacao vivesse apenas no texto, seria palavra do autor.
 *
 * Este teste a transforma em criterio executavel. Se alguem introduzir um
 * {@code Thread.sleep}, um {@code java.util.Timer} ou um agendador para "dar tempo"
 * de a tela chegar, a construcao reprova aqui, e nao em producao.
 */
@DisplayName ("Sincronia sem temporizador de duracao fixa")
class NoFixedDelayTest
{
  private static final JavaClasses PLUGINS =
      new ClassFileImporter ().withImportOption (ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                              .importPackages ("com.bytezone.plugins");

  // ---------------------------------------------------------------------------------//
  @Test
  @DisplayName ("nenhum plugin chama Thread.sleep")
  void nenhumPluginDormeEmDuracaoFixa ()
  // ---------------------------------------------------------------------------------//
  {
    ArchRule regra = noClasses ().should ()
        .callMethod (Thread.class, "sleep", long.class)
        .orShould ().callMethod (Thread.class, "sleep", long.class, int.class)
        .because ("a sincronia deve vir do evento de restauracao de teclado do protocolo, "
            + "nao de uma pausa arbitrada");

    regra.check (PLUGINS);
  }

  // ---------------------------------------------------------------------------------//
  @Test
  @DisplayName ("nenhum plugin agenda execucao por tempo")
  void nenhumPluginAgendaPorTempo ()
  // ---------------------------------------------------------------------------------//
  {
    ArchRule regra = noClasses ().should ()
        .dependOnClassesThat ().areAssignableTo (Timer.class)
        .orShould ().dependOnClassesThat ().areAssignableTo (ScheduledExecutorService.class)
        .because ("agendar por tempo reintroduz a aposta sobre o tempo de resposta do host, "
            + "que este trabalho se propos a eliminar");

    regra.check (PLUGINS);
  }
}
